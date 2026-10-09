package dev.whispr.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.whispr.data.auth.TokenSource
import dev.whispr.data.db.AccountEntity
import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.messaging.EngineTimings
import dev.whispr.data.messaging.MessagingEngine
import dev.whispr.data.messaging.RoomMessagingRepository
import dev.whispr.data.messaging.RoomSettingsRepository
import dev.whispr.data.network.ServerConfig
import dev.whispr.data.network.WhisprApi
import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.ConnectionState
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ConnectivityRepository
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.signal.libsignal.protocol.IdentityKeyPair

/** The engine against a scripted gateway: ordering, acks, retries, receipts. */
@RunWith(RobolectricTestRunner::class)
class MessagingEngineTest {
    private val me = UserId(UUID.randomUUID().toString())
    private val peer = UserId(UUID.randomUUID().toString())
    private val conversation = ConversationId.direct(me, peer)

    private val server = MockWebServer()
    private val gateway = FakeGateway()
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        WhisprDatabase::class.java,
    ).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokens = FakeTokens()
    private val online = MutableStateFlow(true)
    private lateinit var engine: MessagingEngine
    private lateinit var repo: RoomMessagingRepository

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = gateway
        server.start()
        db.accountDao().upsert(AccountEntity(userId = me.value, displayName = "Me", avatarPath = null))
        val identity = IdentityKeyPair.generate()
        gateway.registerSelf(me.value, identity.publicKey.serialize())
        val peerDevice = gateway.peer(peer.value, "Peer")
        db.contactDao().upsert(ContactEntity(peer.value, "Peer", peerDevice.identity.publicKey.serialize(), 0))
        val client = OkHttpClient()
        val url = server.url("/").toString()
        val api = WhisprApi(client, ServerConfig(url), tokens)
        val accounts = DbAccounts(db)
        val crypto = DeviceCrypto(db, client, url, tokens, { identity }) { me.value }
        engine = MessagingEngine(
            db,
            client,
            api,
            tokens,
            accounts,
            connectivity = object : ConnectivityRepository {
                override val isOnline: Flow<Boolean> = online
            },
            scope = scope,
            crypto = crypto.crypto,
            maintainer = crypto.maintainer,
            timings = EngineTimings(resendAfterMs = 500, backoffBaseMs = 50, backoffMaxMs = 200, typingVisibleMs = 300),
        )
        repo = RoomMessagingRepository(db, engine, accounts, RoomSettingsRepository(db.settingDao()))
        engine.setForeground(true)
        engine.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        gateway.closePeers()
        db.close()
    }

    private suspend fun messages(): List<Message> = repo.observeMessages(conversation).first()

    @Test
    fun outboxDrainsInOrderOneAtATime() = runBlocking {
        val inFlight = AtomicInteger()
        var maxInFlight = 0
        gateway.onSend = { f ->
            maxInFlight = maxOf(maxInFlight, inFlight.incrementAndGet())
            Thread.sleep(20)
            inFlight.decrementAndGet()
            gateway.accepted(f.s("id"))
        }
        repeat(10) { repo.sendText(peer, "m$it") }

        eventually("all sent") { messages().all { it.status == MessageStatus.Sent } }
        assertEquals((0 until 10).map { "m$it" }, gateway.sends.map { gateway.payloadBody(it) }.distinct())
        assertEquals(1, maxInFlight)
        assertEquals(0, db.outboxDao().observeCount().first())
        assertEquals(conversation.value, gateway.sends.first().s("conversation_id"))
    }

    @Test
    fun messagesWrittenOfflineAreSentWhenNetworkReturns() = runBlocking {
        online.value = false
        eventually("offline") { engine.connection.value == ConnectionState.Offline }
        repo.sendText(peer, "queued while offline")
        Thread.sleep(300)
        assertTrue(gateway.sends.isEmpty())
        assertEquals(MessageStatus.Sending, messages().single().status)

        online.value = true
        eventually("sent after reconnect") { messages().single().status == MessageStatus.Sent }
    }

    @Test
    fun unacceptedHeadIsResentAfterReconnectAndServerDedupHandlesIt() = runBlocking {
        gateway.onSend = { null } // server goes silent mid-send
        repo.sendText(peer, "flaky")
        eventually("first attempt") { gateway.sends.isNotEmpty() }
        gateway.onSend = { f -> gateway.accepted(f.s("id")) }
        gateway.drop()

        eventually("accepted after reconnect") { messages().single().status == MessageStatus.Sent }
        assertTrue(gateway.connections.size >= 2)
        assertEquals("same message ID on every attempt", 1, gateway.sends.map { it.s("id") }.distinct().size)
    }

    @Test
    fun tokenExpiryCloseDropsTheTokenAndReconnects() = runBlocking {
        eventually("connected") { gateway.connections.size == 1 }
        gateway.drop(4001, "token expired")
        eventually("reconnected") { gateway.connections.size >= 2 }
        assertEquals(1, tokens.invalidations.get())
    }

    @Test
    fun incomingIsStoredBeforeAckAndRedeliveryIsDeduplicated() = runBlocking {
        eventually("connected") { gateway.current != null }
        val id = UUID.randomUUID().toString()
        gateway.push(gateway.envelope(peer.value, """{"t":"text","body":"hi"}""", id = id, seq = 100))
        eventually("acked") { 100L in gateway.acks }
        assertEquals(listOf("hi"), messages().map { it.text })

        // Same envelope again, as after a reconnect before the ack arrived.
        gateway.push(gateway.envelope(peer.value, """{"t":"text","body":"hi"}""", id = id, seq = 100))
        eventually("acked twice") { gateway.acks.count { it == 100L } == 2 }
        assertEquals("stored exactly once", 1, messages().size)
    }

    @Test
    fun incomingOrderIsPreserved() = runBlocking {
        eventually("connected") { gateway.current != null }
        (1..20).forEach { gateway.push(gateway.envelope(peer.value, """{"t":"text","body":"n$it"}""")) }
        eventually("all stored") { messages().size == 20 }
        assertEquals((1..20).map { "n$it" }, messages().map { it.text })
    }

    @Test
    fun spoofedConversationIdIsIgnored() = runBlocking {
        eventually("connected") { gateway.current != null }
        val bogus = UUID.randomUUID().toString()
        gateway.push(gateway.envelope(peer.value, """{"t":"text","body":"sneaky"}""", conversation = bogus))
        eventually("stored") { messages().isNotEmpty() }
        assertEquals("stored under the derived 1:1 conversation", "sneaky", messages().single().text)
    }

    @Test
    fun deliveredAndReadReceiptsAdvanceStatus() = runBlocking {
        RoomSettingsRepository(db.settingDao()).setReadReceipts(true)
        repo.sendText(peer, "hello")
        eventually("sent") { messages().single().status == MessageStatus.Sent }
        val id = messages().single().id

        gateway.push(gateway.delivered(peer.value, id))
        eventually("delivered") { messages().single().status == MessageStatus.Delivered }

        gateway.push(gateway.envelope(peer.value, """{"t":"read","ids":["$id"]}"""))
        eventually("read") { messages().single().status == MessageStatus.Read }
    }

    @Test
    fun readStatusHiddenWhenOwnReadReceiptsAreOff() = runBlocking {
        RoomSettingsRepository(db.settingDao()).setReadReceipts(false)
        repo.sendText(peer, "hello")
        eventually("sent") { messages().single().status == MessageStatus.Sent }
        val id = messages().single().id
        gateway.push(gateway.envelope(peer.value, """{"t":"read","ids":["$id"]}"""))
        eventually("receipt processed") { gateway.acks.isNotEmpty() }
        Thread.sleep(100)
        // Read is shown as Delivered (a read message was delivered) because
        // read receipts are reciprocal and ours are off.
        assertEquals(MessageStatus.Delivered, messages().single().status)
    }

    @Test
    fun permanentRejectionFailsAndRetryRequeues() = runBlocking {
        gateway.onSend = { f -> """{"type":"rejected","id":"${f.s("id")}","code":"unknown_recipient"}""" }
        repo.sendText(peer, "to nowhere")
        eventually("failed") { messages().single().status == MessageStatus.Failed }
        assertEquals(0, db.outboxDao().observeCount().first())

        gateway.onSend = { f -> gateway.accepted(f.s("id")) }
        repo.retry(messages().single().id)
        eventually("sent on retry") { messages().single().status == MessageStatus.Sent }
    }

    @Test
    fun rateLimitedSendIsRetriedNotFailed() = runBlocking {
        var first = true
        gateway.onSend = { f ->
            if (first) {
                first = false
                """{"type":"rejected","id":"${f.s("id")}","code":"rate_limited"}"""
            } else {
                gateway.accepted(f.s("id"))
            }
        }
        repo.sendText(peer, "busy")
        eventually("eventually sent") { messages().single().status == MessageStatus.Sent }
    }

    @Test
    fun unknownSenderBecomesContactFromServerProfile() = runBlocking {
        eventually("connected") { gateway.current != null }
        val stranger = UUID.randomUUID().toString()
        gateway.users[stranger] = "Stranger"
        gateway.push(gateway.envelope(stranger, """{"t":"text","body":"hey"}"""))
        eventually("contact created") { db.contactDao().get(stranger)?.displayName == "Stranger" }
    }

    @Test
    fun readReceiptsOnlySentWhenEnabled() = runBlocking {
        RoomSettingsRepository(db.settingDao()).setReadReceipts(false)
        eventually("connected") { gateway.current != null }
        gateway.push(gateway.envelope(peer.value, """{"t":"text","body":"one"}"""))
        eventually("stored") { messages().size == 1 }
        repo.markRead(conversation)
        Thread.sleep(300)
        assertTrue("no receipt with receipts off", gateway.sends.isEmpty())

        RoomSettingsRepository(db.settingDao()).setReadReceipts(true)
        gateway.push(gateway.envelope(peer.value, """{"t":"text","body":"two"}"""))
        eventually("stored") { messages().size == 2 }
        repo.markRead(conversation)
        eventually("receipt sent") { gateway.sends.any { gateway.payloadText(it).contains("\"read\"") } }
    }

    @Test
    fun typingIsTransientAndExpires() = runBlocking {
        RoomSettingsRepository(db.settingDao()).setTypingIndicators(true)
        eventually("connected") { gateway.current != null }
        // Typing is only sent over an existing session (it never fetches keys).
        repo.onTyping(peer)
        Thread.sleep(300)
        assertTrue("no typing without a session", gateway.transients.isEmpty())
        gateway.push(gateway.envelope(peer.value, """{"t":"text","body":"hi"}"""))
        eventually("session from their message") { messages().size == 1 }
        Thread.sleep(3_100) // typing is throttled to once every 3 s
        repo.onTyping(peer)
        eventually("typing sent") { gateway.transients.isNotEmpty() }
        assertTrue("typing never goes through the outbox", gateway.sends.isEmpty())
        assertEquals(
            """{"t":"typing"}""",
            gateway.payloadText(
                gateway.transients.first().let { t ->
                    kotlinx.serialization.json.buildJsonObject {
                        t.forEach { (k, v) -> put(k, v) }
                        put("id", kotlinx.serialization.json.JsonPrimitive("typing-" + System.nanoTime()))
                    }
                },
            ),
        )

        gateway.push(gateway.typingFrom(peer.value))
        eventually("peer typing") { repo.observePeerTyping(conversation).first() }
        eventually("typing expires") { !repo.observePeerTyping(conversation).first() }
    }

    @Test
    fun rejectedTokenIsInvalidated() = runBlocking {
        gateway.rejectAuth = true
        gateway.drop()
        eventually("token invalidated") { tokens.invalidations.get() > 0 }
        gateway.rejectAuth = false
        eventually("reconnected") { engine.connection.value == ConnectionState.Connected }
    }

    private fun eventually(what: String, cond: suspend () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(25)
        }
    }
}

private class FakeTokens : TokenSource {
    val invalidations = AtomicInteger()
    override suspend fun bearerToken() = "token"
    override fun invalidate() {
        invalidations.incrementAndGet()
    }
}

private class DbAccounts(private val db: WhisprDatabase) : AccountRepository {
    override fun observeAccount() = kotlinx.coroutines.flow.flow {
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
