package dev.whispr.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.whispr.data.auth.TokenSource
import dev.whispr.data.db.AccountEntity
import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.messaging.EngineTimings
import dev.whispr.data.messaging.MessagingEngine
import dev.whispr.data.messaging.Payload
import dev.whispr.data.messaging.PayloadCodec
import dev.whispr.data.messaging.RoomMessagingRepository
import dev.whispr.data.messaging.RoomSettingsRepository
import dev.whispr.data.network.ServerConfig
import dev.whispr.data.network.WhisprApi
import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.MessageNotice
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ConnectivityRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.signal.libsignal.protocol.IdentityKeyPair

/** End-to-end encryption through the engine: what the server sees, and the hard cases. */
@RunWith(RobolectricTestRunner::class)
class EncryptedMessagingTest {
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
    private lateinit var engine: MessagingEngine
    private lateinit var repo: RoomMessagingRepository

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = gateway
        server.start()
        db.accountDao().upsert(AccountEntity(userId = me.value, displayName = "Me", avatarPath = null))
        val identity = IdentityKeyPair.generate()
        gateway.registerSelf(me.value, identity.publicKey.serialize())
        pin(peer.value, gateway.peer(peer.value).identity.publicKey.serialize())
        val client = OkHttpClient()
        val url = server.url("/").toString()
        val tokens = object : TokenSource {
            override suspend fun bearerToken() = "token"
            override fun invalidate() = Unit
        }
        val accounts = object : AccountRepository {
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
        val crypto = DeviceCrypto(db, client, url, tokens, { identity }) { me.value }
        engine = MessagingEngine(
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
            EngineTimings(resendAfterMs = 500, backoffBaseMs = 50, backoffMaxMs = 200, parkRetryMs = 200),
        )
        repo = RoomMessagingRepository(db, engine, accounts, RoomSettingsRepository(db.settingDao()))
        engine.setForeground(true)
        engine.start()
        eventually("connected") { gateway.current != null }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        gateway.closePeers()
        db.close()
    }

    private suspend fun pin(id: String, key: ByteArray) = db.contactDao().upsert(ContactEntity(id, "Peer", key, 0))

    private suspend fun messages(c: ConversationId = conversation): List<Message> = repo.observeMessages(c).first()

    private fun text(body: String, mid: String? = null, replaces: String? = null) =
        String(PayloadCodec.encode(Payload.Text(body, mid = mid, replaces = replaces)))

    @Test
    fun theServerNeverSeesPlaintextInEitherDirection() = runBlocking {
        val outgoing = "OUT-MARKER-4b1d"
        val incoming = "IN-MARKER-9c2e"
        RoomSettingsRepository(db.settingDao()).setReadReceipts(true)
        repo.sendText(peer, outgoing)
        gateway.push(gateway.envelope(peer.value, text(incoming)))
        eventually("both stored") { messages().size == 2 && messages().any { it.status == MessageStatus.Sent } }
        repo.markRead(conversation)
        eventually("read receipt sent") { gateway.sends.size >= 2 }

        assertEquals(outgoing, gateway.payloadBody(gateway.sends.first()))
        for (frame in gateway.wire) {
            assertFalse("plaintext on the wire: $frame", frame.contains(outgoing) || frame.contains(incoming))
            assertFalse(
                "unencrypted payload JSON on the wire",
                frame.contains("\"t\":\"text\"") || frame.contains("\"t\":\"read\""),
            )
        }
    }

    @Test
    fun tamperedMessageIsRejectedThenResetRecoversItInPlace() = runBlocking {
        gateway.push(gateway.envelope(peer.value, text("before")))
        eventually("setup") { messages().size == 1 }
        // Reply so the peer's next message is a Whisper message: in a PreKey message the
        // trailing bytes carry the handshake, which is ignored once the session exists.
        repo.sendText(peer, "reply")
        eventually("replied") { gateway.sends.isNotEmpty() }
        gateway.payloadText(gateway.sends.single())

        val mid = UUID.randomUUID().toString()
        val sealed = gateway.seal(peer.value, text("secret", mid = mid))
        sealed[sealed.size - 5] = (sealed[sealed.size - 5].toInt() xor 1).toByte()
        gateway.push(gateway.rawEnvelope(peer.value, sealed, id = mid))
        gateway.push(gateway.envelope(peer.value, text("after")))

        eventually("placeholder") { messages().any { it.notice == MessageNotice.Pending } }
        assertFalse("tampered content never stored", messages().any { it.text == "secret" })
        // The device asks the peer to resend exactly that envelope.
        eventually("reset sent") {
            gateway.sends.any {
                (PayloadCodec.decode(gateway.payloadText(it).toByteArray()) as? Payload.SessionReset)?.failed ==
                    listOf(mid)
            }
        }
        // The peer resends it (new transport ID, same mid, replaces the failed one).
        gateway.push(gateway.envelope(peer.value, text("secret", mid = mid, replaces = mid)))
        eventually("recovered") { messages().none { it.notice != null } }
        assertEquals(
            "recovered in its original position",
            listOf("before", "reply", "secret", "after"),
            messages().map {
                it.text
            },
        )
    }

    @Test
    fun replayedEnvelopesAreDroppedEvenUnderANewId() = runBlocking {
        val frame = gateway.envelope(peer.value, text("once"))
        gateway.push(frame)
        eventually("stored") { messages().size == 1 }
        // Same ciphertext, new transport ID and sequence: libsignal rejects it.
        val replay = frame.replace(
            Regex("\"id\":\"[^\"]+\""),
            "\"id\":\"${UUID.randomUUID()}\"",
        ).replace(Regex("\"seq\":\\d+"), "\"seq\":999")
        gateway.push(replay)
        eventually("acked") { 999L in gateway.acks }
        Thread.sleep(100)
        assertEquals(listOf("once"), messages().map { it.text })
        assertTrue("no placeholder for a replay", messages().none { it.notice != null })
    }

    @Test
    fun messageUnderAChangedKeyIsHeldUntilAcknowledged() = runBlocking {
        val stale = IdentityKeyPair.generate().publicKey.serialize()
        pin(peer.value, stale) // what we pinned differs from the key the peer now uses
        gateway.push(gateway.envelope(peer.value, text("trust me")))
        eventually("held") { messages().singleOrNull()?.notice == MessageNotice.Held }
        assertTrue(messages().none { it.text == "trust me" })
        assertEquals("KeyChanged", db.contactDao().get(peer.value)!!.trust)

        db.contactDao().acceptPendingKey(peer.value)
        engine.onKeyChangeAcknowledged(peer)
        eventually("released") { messages().singleOrNull()?.text == "trust me" }
        assertNull(messages().single().notice)
    }

    @Test
    fun aPeerWithoutKeysDoesNotBlockOtherChats() = runBlocking {
        val newcomer = UUID.randomUUID().toString()
        gateway.users[newcomer] = "Newcomer"
        gateway.keyServer.register(newcomer, IdentityKeyPair.generate().publicKey.serialize()) // registered, no keys
        pin(newcomer, ByteArray(0))
        repo.sendText(UserId(newcomer), "waiting for keys")
        repo.sendText(peer, "goes through")
        eventually("other chat sent") { messages().singleOrNull()?.status == MessageStatus.Sent }
        val waiting = messages(ConversationId.direct(me, UserId(newcomer))).single()
        assertEquals("first message to a peer without keys waits", MessageStatus.Sending, waiting.status)
    }

    @Test
    fun resetCanOnlyAskForMessagesSentToThatPeer() = runBlocking {
        val other = UUID.randomUUID().toString()
        pin(other, gateway.peer(other, "Other").identity.publicKey.serialize())
        repo.sendText(UserId(other), "for other only")
        eventually("sent") { messages(ConversationId.direct(me, UserId(other))).single().status == MessageStatus.Sent }
        val sentId = messages(ConversationId.direct(me, UserId(other))).single().id
        gateway.payloadText(gateway.sends.single()) // the other peer reads it

        gateway.push(gateway.envelope(peer.value, String(PayloadCodec.encode(Payload.SessionReset(listOf(sentId))))))
        eventually("answered") {
            gateway.sends.any {
                (PayloadCodec.decode(gateway.payloadText(it).toByteArray()) as? Payload.ResetDone)?.lost ==
                    listOf(sentId)
            }
        }
        assertTrue(
            "nothing resent to the wrong peer",
            gateway.sends.filter { it.s("recipient_id") == peer.value }
                .none { (PayloadCodec.decode(gateway.payloadText(it).toByteArray()) as? Payload.Text) != null },
        )
    }

    @Test
    fun answersAResetByResendingOnce() = runBlocking {
        gateway.push(gateway.envelope(peer.value, text("hi"))) // session exists both ways
        eventually("stored") { messages().size == 1 }
        repo.sendText(peer, "lost in transit")
        eventually("sent") { messages().any { it.status == MessageStatus.Sent } }
        val id = messages().first { it.outgoing }.id
        gateway.payloadText(gateway.sends.single())

        val reset = String(PayloadCodec.encode(Payload.SessionReset(listOf(id))))
        gateway.push(gateway.envelope(peer.value, reset))
        eventually("resent") {
            gateway.sends.any {
                (PayloadCodec.decode(gateway.payloadText(it).toByteArray()) as? Payload.Text)?.replaces ==
                    id
            }
        }
        val resend = PayloadCodec.decode(
            gateway.payloadText(
                gateway.sends.first {
                    it.s("id") != id &&
                        gateway.payloadText(it).contains("replaces")
                },
            ).toByteArray(),
        ) as Payload.Text
        assertEquals("lost in transit", resend.body)
        assertEquals("same logical message", id, resend.mid)

        // Asking again for the same ID gets "lost", not a second copy.
        gateway.push(gateway.envelope(peer.value, reset))
        eventually("second answer") {
            gateway.sends.any {
                (PayloadCodec.decode(gateway.payloadText(it).toByteArray()) as? Payload.ResetDone)?.lost ==
                    listOf(id)
            }
        }
    }

    private fun eventually(what: String, cond: suspend () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 15_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(25)
        }
    }
}
