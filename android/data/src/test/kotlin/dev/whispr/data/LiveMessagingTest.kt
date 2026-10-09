package dev.whispr.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.whispr.data.auth.SessionAuthRepository
import dev.whispr.data.contacts.RoomContactsRepository
import dev.whispr.data.crypto.SecretFileStore
import dev.whispr.data.db.AccountEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.identity.LibsignalIdentityRepository
import dev.whispr.data.messaging.EngineTimings
import dev.whispr.data.messaging.MessagingEngine
import dev.whispr.data.messaging.RoomMessagingRepository
import dev.whispr.data.messaging.RoomSettingsRepository
import dev.whispr.data.network.AuthApi
import dev.whispr.data.network.ServerConfig
import dev.whispr.data.network.WhisprApi
import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AddContactResult
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.model.VerifyResult
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
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Two real clients (real libsignal identities, real auth, real engines with
 * their own databases) chatting through the real Go server. Runs only when
 * WHISPR_SERVER_URL is set:
 *   docker compose up -d && WHISPR_SERVER_URL=http://127.0.0.1:8080/ ./gradlew :data:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
class LiveMessagingTest {

    private class Device(val name: String, url: String) {
        val db: WhisprDatabase = Room.inMemoryDatabaseBuilder(
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
        private val secrets: File = Files.createTempDirectory("whispr-$name").toFile()
        private val identity =
            LibsignalIdentityRepository(SecretFileStore(secrets, SoftwareKeyWrapper()), Dispatchers.IO)
        val auth = SessionAuthRepository(AuthApi(client, ServerConfig(url)), identity, accounts)
        private val api = WhisprApi(client, ServerConfig(url), auth)
        private val crypto = DeviceCrypto(db, client, url, auth, identity) { id.value }

        /** True once this device's prekeys are on the server (others can encrypt to it). */
        suspend fun keysPublished() =
            ((crypto.keys.counts() as? dev.whispr.data.network.ApiResult.Success)?.body?.oneTime ?: 0) > 0
        val contacts = RoomContactsRepository(db, api, accounts, identity, allowInsecureLoopback = true) {
            engine.onKeyChangeAcknowledged(it)
        }
        private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var engine = newEngine()
        var repo = repoFor(engine)
        private var userId: UserId? = null
        val id: UserId get() = checkNotNull(userId) { "not registered" }

        private fun newEngine() = MessagingEngine(
            db,
            client,
            api,
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

        private fun repoFor(e: MessagingEngine) =
            RoomMessagingRepository(db, e, accounts, RoomSettingsRepository(db.settingDao()))

        suspend fun register() {
            userId = (auth.register(name) as AuthResult.Ok).value
            db.accountDao().upsert(AccountEntity(userId = id.value, displayName = name, avatarPath = null))
            check(auth.authenticate() is AuthResult.Ok)
        }

        fun goOnline() = engine.start()

        /** Kills the engine (like the process dying) and brings up a fresh one on the same database. */
        fun restart() {
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            engine = newEngine()
            repo = repoFor(engine)
        }

        fun close() {
            scope.cancel()
            db.close()
            secrets.deleteRecursively()
        }
    }

    @Test
    fun twoDevicesChatAndOfflineDeliveryIsExactlyOnceInOrder() = runBlocking {
        val url = System.getenv("WHISPR_SERVER_URL")
        assumeTrue("WHISPR_SERVER_URL not set", !url.isNullOrBlank())
        val alice = Device("Alice", url!!)
        val bob = Device("Bob", url)
        try {
            alice.register()
            bob.register()
            val conversation = ConversationId.direct(alice.id, bob.id)

            // Both install the app (publishing keys) and connect; then Bob goes offline and
            // Alice sends a burst.
            alice.goOnline()
            bob.goOnline()
            eventually("bob published keys") { bob.keysPublished() }
            connect(alice, bob)
            bob.restart()
            val texts = (1..10).map { "offline-$it" }
            texts.forEach { check(alice.repo.sendText(bob.id, it)) }
            eventually("all accepted by server") {
                alice.repo.observeMessages(conversation).first().filter { !it.system }
                    .all { it.status == MessageStatus.Sent }
            }

            // Bob comes online: everything arrives once, in order.
            bob.goOnline()
            eventually("bob received all") { bob.repo.observeMessages(conversation).first().size == texts.size }
            assertEquals(texts, bob.repo.observeMessages(conversation).first().map { it.text })
            eventually("alice sees delivered") {
                alice.repo.observeMessages(conversation).first().filter { !it.system }
                    .all { it.status == MessageStatus.Delivered }
            }

            // Real time, both directions.
            bob.repo.sendText(alice.id, "live reply")
            eventually("alice got reply") {
                alice.repo.observeMessages(conversation).first().any {
                    it.text ==
                        "live reply"
                }
            }
            alice.repo.sendText(bob.id, "live again")
            eventually("bob got it") { bob.repo.observeMessages(conversation).first().any { it.text == "live again" } }

            // Bob's process dies and restarts: no duplicates, nothing lost.
            bob.restart()
            alice.repo.sendText(bob.id, "after restart")
            bob.goOnline()
            eventually("bob got post-restart message") {
                bob.repo.observeMessages(conversation).first().any { it.text == "after restart" }
            }
            val bobTexts = bob.repo.observeMessages(conversation).first().map { it.text }
            assertEquals("no duplicates", bobTexts.distinct(), bobTexts)
            assertEquals(texts + listOf("live reply", "live again", "after restart"), bobTexts)
        } finally {
            alice.close()
            bob.close()
        }
    }

    @Test
    fun scanAddsBothSidesAndSafetyNumbersVerify() = runBlocking {
        val url = System.getenv("WHISPR_SERVER_URL")
        assumeTrue("WHISPR_SERVER_URL not set", !url.isNullOrBlank())
        val alice = Device("Alice", url!!)
        val bob = Device("Bob", url)
        try {
            alice.register()
            bob.register()
            alice.goOnline()
            bob.goOnline()

            // Bob scans Alice's code: Alice is pinned on Bob's side immediately.
            val added = bob.contacts.addFromCode(alice.contacts.myContactCode())
            check(added is AddContactResult.Added) { "scan failed: $added" }

            // Alice receives a contact request carrying Bob's key, and accepts it.
            eventually("alice got request") { alice.contacts.contact(bob.id)?.isRequest == true }
            alice.contacts.acceptRequest(bob.id)
            assertEquals(TrustState.Unverified, alice.contacts.contact(bob.id)!!.trust)
            eventually("bob learns he was accepted") { bob.contacts.contact(alice.id)?.connected == true }

            // Both can chat without scanning again.
            val conversation = ConversationId.direct(alice.id, bob.id)
            check(alice.repo.sendText(bob.id, "hi from alice"))
            check(bob.repo.sendText(alice.id, "hi from bob"))
            eventually("bob got alice's") {
                bob.repo.observeMessages(conversation).first().any {
                    it.text ==
                        "hi from alice"
                }
            }
            eventually("alice got bob's") {
                alice.repo.observeMessages(conversation).first().any {
                    it.text ==
                        "hi from bob"
                }
            }

            // In person: both see the same 60 digits and each scans the other's code.
            val aliceSn = alice.contacts.safetyNumber(bob.id)!!
            val bobSn = bob.contacts.safetyNumber(alice.id)!!
            assertEquals(60, aliceSn.digits.length)
            assertEquals(aliceSn.digits, bobSn.digits)
            assertEquals(VerifyResult.Match, alice.contacts.verifyScanned(bob.id, bobSn.qrCode))
            assertEquals(VerifyResult.Match, bob.contacts.verifyScanned(alice.id, aliceSn.qrCode))
            assertEquals(TrustState.Verified, alice.contacts.contact(bob.id)!!.trust)
            assertEquals(TrustState.Verified, bob.contacts.contact(alice.id)!!.trust)
        } finally {
            alice.close()
            bob.close()
        }
    }

    /**
     * Sends WHISPR_E2E_MARKER for CI (.github/workflows/e2e.yml) to look for
     * afterwards in a dump of the server database and in captured traffic.
     * Carol goes offline before it is sent, so her copy is still queued on
     * the server when the database is dumped.
     */
    @Test
    fun markerTravelsAndRestsOnlyAsCiphertext() = runBlocking {
        val url = System.getenv("WHISPR_SERVER_URL")
        val marker = System.getenv("WHISPR_E2E_MARKER")
        assumeTrue("WHISPR_SERVER_URL or WHISPR_E2E_MARKER not set", !url.isNullOrBlank() && !marker.isNullOrBlank())
        val alice = Device("Alice", url!!)
        val bob = Device("Bob", url)
        val carol = Device("Carol", url)
        try {
            listOf(alice, bob, carol).forEach { it.register() }
            listOf(alice, bob, carol).forEach { it.goOnline() }
            listOf(bob, carol).forEach { eventually("${it.id} published keys") { it.keysPublished() } }
            connect(alice, bob)
            connect(alice, carol)
            val withCarol = ConversationId.direct(alice.id, carol.id)
            alice.repo.sendText(carol.id, "warm-up")
            eventually("carol has a session") { carol.repo.observeMessages(withCarol).first().isNotEmpty() }
            carol.restart() // offline from here on

            alice.repo.sendText(bob.id, marker!!)
            alice.repo.sendText(carol.id, marker)
            val withBob = ConversationId.direct(alice.id, bob.id)
            eventually("bob decrypted it") { bob.repo.observeMessages(withBob).first().any { it.text == marker } }
            eventually("carol's copy queued on the server") {
                alice.repo.observeMessages(withCarol).first().last().status == MessageStatus.Sent
            }
        } finally {
            listOf(alice, bob, carol).forEach { it.close() }
        }
    }

    /** [a] asks to connect and [b] accepts (accounts are private); both must be online. */
    private suspend fun connect(a: Device, b: Device) {
        check(a.contacts.addById(b.id.value) is AddContactResult.Added)
        eventually("${b.id} got the request") { b.contacts.contact(a.id)?.isRequest == true }
        b.contacts.acceptRequest(a.id)
        eventually("${a.id} was accepted") { a.contacts.contact(b.id)?.connected == true }
    }

    private fun eventually(what: String, cond: suspend () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 20_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(50)
        }
    }
}
