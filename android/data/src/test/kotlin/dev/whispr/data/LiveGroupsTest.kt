package dev.whispr.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.whispr.data.auth.SessionAuthRepository
import dev.whispr.data.crypto.GroupDecryptResult
import dev.whispr.data.crypto.SecretFileStore
import dev.whispr.data.db.AccountEntity
import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.identity.LibsignalIdentityRepository
import dev.whispr.data.media.MediaFiles
import dev.whispr.data.media.MediaService
import dev.whispr.data.media.PreparedMedia
import dev.whispr.data.messaging.EngineTimings
import dev.whispr.data.messaging.MessagingEngine
import dev.whispr.data.messaging.RoomGroupsRepository
import dev.whispr.data.messaging.RoomMessagingRepository
import dev.whispr.data.messaging.RoomSettingsRepository
import dev.whispr.data.network.AuthApi
import dev.whispr.data.network.DownloadResult
import dev.whispr.data.network.MediaApi
import dev.whispr.data.network.ServerConfig
import dev.whispr.data.network.WhisprApi
import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.AttachmentState
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.GroupResult
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.MediaSource
import dev.whispr.domain.model.SendResult
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ConnectivityRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Three real clients through the real Go server and S3-compatible storage
 * (MinIO): a group with a removal, and encrypted media. Runs only when
 * WHISPR_SERVER_URL is set (docker compose up -d). WHISPR_E2E_MARKER, if
 * set, is the text CI later searches for in the database dump and bucket.
 */
@RunWith(RobolectricTestRunner::class)
class LiveGroupsTest {
    private val marker = System.getenv("WHISPR_E2E_MARKER") ?: "whispr-live-marker-${System.nanoTime()}"

    private class Device(val name: String, url: String) {
        val db: WhisprDatabase =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                WhisprDatabase::class.java,
            ).build()
        private val client = OkHttpClient()
        private val accounts = object : AccountRepository {
            override fun observeAccount() = flow {
                db.accountDao().observe().collect { e ->
                    emit(e?.let { Account(it.userId?.let(::UserId), it.displayName, null) })
                }
            }
            override suspend fun getAccount() = db.accountDao().get()?.let {
                Account(it.userId?.let(::UserId), it.displayName, null)
            }
            override suspend fun saveProfile(displayName: String, avatar: AvatarSource?) = Unit
            override suspend fun markRegistered(userId: UserId) = Unit
        }
        private val dir: File = Files.createTempDirectory("whispr-live-$name").toFile()
        val identity =
            LibsignalIdentityRepository(SecretFileStore(File(dir, "secrets"), SoftwareKeyWrapper()), Dispatchers.IO)
        val auth = SessionAuthRepository(AuthApi(client, ServerConfig(url)), identity, accounts)
        val mediaApi = MediaApi(client, ServerConfig(url), auth)
        private val crypto = DeviceCrypto(db, client, url, auth, identity) { id.value }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = MessagingEngine(
            db,
            client,
            WhisprApi(client, ServerConfig(url), auth),
            auth,
            accounts,
            object : ConnectivityRepository {
                override val isOnline = flowOf(true)
            },
            scope,
            crypto.crypto,
            crypto.maintainer,
            // Short lane retries: a peer may upload its keys a moment after we first ask for them.
            EngineTimings(backoffBaseMs = 100, backoffMaxMs = 500, noKeysRetryMs = 500, parkRetryMs = 500),
        ).also { it.setForeground(true) }
        val preparer = FakePreparer()
        val media = MediaService(
            db,
            crypto.crypto,
            engine.groups,
            mediaApi,
            preparer,
            MediaFiles(File(dir, "media"), File(dir, "open")),
            { id },
            scope,
        )
        val repo = RoomMessagingRepository(db, engine, accounts, RoomSettingsRepository(db.settingDao()), media)
        val groups = RoomGroupsRepository(db, engine, accounts, identity, preparer)
        val decrypt = crypto.crypto
        private var userId: UserId? = null
        val id: UserId get() = checkNotNull(userId)

        suspend fun register() {
            userId = (auth.register(name) as AuthResult.Ok).value
            db.accountDao().upsert(AccountEntity(userId = id.value, displayName = name, avatarPath = null))
            check(auth.authenticate() is AuthResult.Ok)
            engine.start()
        }

        suspend fun knows(other: Device) =
            db.contactDao().upsert(ContactEntity(other.id.value, other.name, other.identity.getOrCreatePublicKey(), 0))

        suspend fun texts(c: ConversationId) = repo.observeMessages(c).first().filter {
            !it.system && it.notice == null && it.attachment == null
        }.map { it.text }

        fun close() {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun threeUsersGroupRemovalAndEncryptedMediaThroughTheRealServer() = runBlocking {
        val url = System.getenv("WHISPR_SERVER_URL")
        assumeTrue("WHISPR_SERVER_URL not set", !url.isNullOrBlank())
        val alice = Device("Alice", url!!)
        val bob = Device("Bob", url)
        val carol = Device("Carol", url)
        try {
            listOf(alice, bob, carol).forEach { it.register() }
            alice.knows(bob)
            alice.knows(carol)
            bob.knows(alice)
            carol.knows(alice)
            // Give everyone a moment to publish prekeys.
            eventually("keys up") { listOf(alice, bob, carol).all { it.engine.connection.value.name == "Connected" } }

            val g = (alice.groups.create("Live group", listOf(bob.id, carol.id)) as GroupResult.Ok).group
            eventually("members active") {
                listOf(bob, carol).all { it.groups.observeGroup(g).first()?.status == GroupStatus.Active }
            }
            alice.repo.sendGroupText(g, "$marker group hello")
            bob.repo.sendGroupText(g, "$marker from bob")
            eventually("all three read both") {
                listOf(alice, bob, carol).all { d ->
                    d.texts(g.conversation).containsAll(listOf("$marker group hello", "$marker from bob"))
                }
            }

            // Media: Bob sends a picture to the group; Alice and Carol download and open it.
            val picture = ("ÿØÿ" + "$marker exif ".repeat(40)).toByteArray()
            bob.preparer.files["content://p"] = PreparedMedia(picture, "image/jpeg", null, width = 10, height = 10)
            assertEquals(
                SendResult.Ok,
                bob.repo.sendMedia(g.conversation, MediaSource("content://p", AttachmentKind.Image)),
            )
            for (d in listOf(alice, carol)) {
                eventually("${d.name} gets the picture") {
                    d.repo.observeMessages(g.conversation).first().any { it.attachment != null }
                }
                val m = d.repo.observeMessages(g.conversation).first().single { it.attachment != null }
                d.repo.download(g.conversation, m.id)
                eventually("${d.name} downloaded") {
                    d.repo.observeMessages(g.conversation).first().single { it.attachment != null }
                        .attachment!!.state == AttachmentState.Ready
                }
                assertArrayEquals(picture, d.repo.attachmentBytes(g.conversation, m.id))
            }
            // What object storage holds, fetched raw: ciphertext only.
            // (Read the message ID first: a Room query inside this transaction would wait on itself.)
            val pictureId = alice.repo.observeMessages(g.conversation).first().single { it.attachment != null }.id
            val remoteId = alice.engine.transaction {
                val row = alice.db.groupDao().messageIn(g.value, pictureId)!!.localOrder
                alice.db.groupDao().attachment(row)!!.remoteId!!
            }
            val raw = (alice.mediaApi.download(remoteId, 1 shl 20) as DownloadResult.Ok).blob
            assertFalse("marker in stored blob", String(raw, Charsets.ISO_8859_1).contains(marker))
            assertFalse("JPEG header in stored blob", raw.copyOf(3).contentEquals(picture.copyOf(3)))

            // Carol is removed; what follows is unreadable to her.
            assertEquals(true, alice.groups.removeMember(g, carol.id) is GroupResult.Ok)
            eventually("carol removed") { carol.groups.observeGroup(g).first()?.status == GroupStatus.Removed }
            eventually("bob saw it") { bob.groups.observeGroup(g).first()!!.members.size == 2 }
            alice.repo.sendGroupText(g, "$marker after removal")
            bob.repo.sendGroupText(g, "$marker bob after removal")
            eventually("alice and bob read on") {
                alice.texts(g.conversation).contains("$marker bob after removal") &&
                    bob.texts(g.conversation).contains("$marker after removal")
            }
            Thread.sleep(1_000)
            val carolTexts = carol.texts(g.conversation)
            assertFalse(carolTexts.contains("$marker after removal"))
            assertFalse(carolTexts.contains("$marker bob after removal"))
            // Even the ciphertext Bob sent before removal decrypts for Carol only under the old key:
            // a fresh group ciphertext under Bob's new key is unknown to her.
            val probe = bob.engine.transaction {
                val group = bob.db.groupDao().group(g.value)!!
                bob.decrypt.encryptGroup(java.util.UUID.fromString(group.myDistributionId), "probe".toByteArray())
            }
            assertEquals(GroupDecryptResult.NoSenderKey, carol.decrypt.decryptGroup(bob.id.value, probe) { it })
        } finally {
            listOf(alice, bob, carol).forEach { it.close() }
        }
    }

    private fun eventually(what: String, cond: suspend () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 30_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(50)
        }
    }
}
