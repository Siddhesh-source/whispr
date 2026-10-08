package dev.whispr.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.whispr.data.auth.TokenSource
import dev.whispr.data.calls.RoomCallSignalingRepository
import dev.whispr.data.db.AccountEntity
import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.media.MediaFiles
import dev.whispr.data.media.MediaPreparer
import dev.whispr.data.media.MediaService
import dev.whispr.data.media.Prepared
import dev.whispr.data.media.PreparedMedia
import dev.whispr.data.messaging.EngineTimings
import dev.whispr.data.messaging.MessagingEngine
import dev.whispr.data.messaging.RoomGroupsRepository
import dev.whispr.data.messaging.RoomMessagingRepository
import dev.whispr.data.messaging.RoomSettingsRepository
import dev.whispr.data.network.MediaApi
import dev.whispr.data.network.ServerConfig
import dev.whispr.data.network.WhisprApi
import dev.whispr.data.status.RoomStatusRepository
import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.MediaSource
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.repository.IdentityRepository
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.signal.libsignal.protocol.IdentityKeyPair

/** Hands out picked "files" by URI; images are taken as already re-encoded. */
class FakePreparer : MediaPreparer {
    val files = mutableMapOf<String, PreparedMedia>()

    override suspend fun prepare(source: MediaSource): Prepared =
        files[source.uri]?.let { Prepared.Ok(it) } ?: Prepared.Unreadable

    override suspend fun groupAvatar(uri: String, maxBytes: Int): ByteArray? =
        files[uri]?.bytes?.takeIf { it.size <= maxBytes }
}

/**
 * One phone on a [FakeRelay]: its own encrypted-at-rest database (in memory
 * here), libsignal identity, engine, repositories and media service.
 */
class RelayDevice(val name: String, private val relay: FakeRelay, url: String) {
    val id: String = UUID.randomUUID().toString()
    val identity: IdentityKeyPair = IdentityKeyPair.generate()
    val db: WhisprDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WhisprDatabase::class.java).build()
    private val client = OkHttpClient()
    private val tokens = object : TokenSource {
        override suspend fun bearerToken() = id
        override fun invalidate() = Unit
    }
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
    private val identityRepo = object : IdentityRepository {
        override suspend fun hasIdentity() = true
        override suspend fun getOrCreatePublicKey(): ByteArray = identity.publicKey.serialize()
        override suspend fun sign(message: ByteArray): ByteArray = identity.privateKey.calculateSignature(message)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val crypto = DeviceCrypto(db, client, url, tokens, { identity }) { id }
    val engine = MessagingEngine(
        db,
        client,
        WhisprApi(client, ServerConfig(url), tokens),
        tokens,
        accounts,
        object : ConnectivityRepository {
            override val isOnline = flowOf(true)
        },
        scope,
        crypto.crypto,
        crypto.maintainer,
        EngineTimings(resendAfterMs = 500, backoffBaseMs = 50, backoffMaxMs = 200, parkRetryMs = 200, sweepMs = 100),
    )
    val preparer = FakePreparer()
    private val mediaDir = Files.createTempDirectory("whispr-media-$name").toFile()
    val media = MediaService(
        db,
        crypto.crypto,
        engine.groups,
        MediaApi(client, ServerConfig(url), tokens),
        preparer,
        MediaFiles(java.io.File(mediaDir, "blobs"), java.io.File(mediaDir, "open")),
        { UserId(id) },
        scope,
    )
    val repo = RoomMessagingRepository(db, engine, accounts, RoomSettingsRepository(db.settingDao()), media)
    val groups = RoomGroupsRepository(db, engine, accounts, identityRepo, preparer)
    val statuses = RoomStatusRepository(db, engine, accounts, media, scope)
    val calls = RoomCallSignalingRepository(db, engine, WhisprApi(client, ServerConfig(url), tokens), accounts)

    init {
        relay.register(id, name, identity.publicKey.serialize())
        runBlocking { db.accountDao().upsert(AccountEntity(userId = id, displayName = name, avatarPath = null)) }
    }

    fun start() {
        engine.setForeground(true)
        engine.start()
    }

    /** Adds [other] as a contact with their key pinned, as a QR scan would. */
    fun knows(other: RelayDevice) = runBlocking {
        db.contactDao().upsert(ContactEntity(other.id, other.name, other.identity.publicKey.serialize(), 0))
    }

    fun direct(other: RelayDevice) = ConversationId.direct(UserId(id), UserId(other.id))

    suspend fun messages(conversation: ConversationId): List<Message> = repo.observeMessages(conversation).first()

    suspend fun texts(conversation: ConversationId): List<String> =
        messages(conversation).filter { !it.system && it.notice == null }.map { it.text }

    fun close() {
        scope.cancel()
        db.close()
        mediaDir.deleteRecursively()
    }
}
