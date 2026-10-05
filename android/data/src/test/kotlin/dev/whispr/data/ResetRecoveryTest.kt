package dev.whispr.data

import dev.whispr.data.crypto.EncryptResult
import dev.whispr.data.crypto.ParkReason
import dev.whispr.data.crypto.SessionCrypto
import dev.whispr.data.crypto.SessionStatus
import dev.whispr.data.db.ParkedRecipientEntity
import dev.whispr.data.db.Placeholder
import dev.whispr.data.messaging.IncomingEnvelope
import dev.whispr.data.messaging.IncomingPipeline
import dev.whispr.data.messaging.Payload
import dev.whispr.data.messaging.PayloadCodec
import dev.whispr.data.messaging.PipelineEvents
import dev.whispr.data.messaging.ResetCoordinator
import dev.whispr.domain.model.ConversationId
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Session resets and the incoming pipeline's durable state, driven directly
 * with a controllable clock: cooldown coalescing, the delivery-based retry
 * clock (D7), parked lanes (D8), restarts, and the poison guard.
 */
@RunWith(RobolectricTestRunner::class)
class ResetRecoveryTest {
    private val server = FakeKeyServer()
    private val dir: File = Files.createTempDirectory("reset").toFile()
    private val bobFile = File(dir, "bob.db")
    private var now = 1_000_000_000_000L
    private val clock = { now }
    private lateinit var alice: CryptoDevice
    private lateinit var bob: CryptoDevice
    private lateinit var pipeline: IncomingPipeline
    private lateinit var resets: ResetCoordinator

    private val events = object : PipelineEvents {
        override fun onText(conversation: ConversationId, senderName: String, body: String) = Unit
        override fun onTyping(conversation: ConversationId) = Unit
        override fun onOneTimeKeyUsed() = Unit
        override fun onDecryptFailure() = Unit
    }

    @Before
    fun setUp() {
        alice = CryptoDevice(server)
        alice.publishKeys()
        openBob(CryptoDevice(server, dbFile = bobFile, clock = clock))
        bob.publishKeys()
        alice.pin(bob)
        bob.pin(alice)
    }

    @After
    fun tearDown() {
        alice.close()
        bob.close()
        dir.deleteRecursively()
    }

    /** (Re)starts Bob's app on [device]: fresh pipeline and coordinator over its database. */
    private fun openBob(device: CryptoDevice) {
        bob = device
        pipeline = IncomingPipeline(bob.db, bob.crypto, { null }, events, clock)
        resets = ResetCoordinator(bob.db, bob.crypto, clock)
    }

    private fun restartBob() {
        val (id, identity) = bob.userId to bob.identity
        bob.close()
        openBob(CryptoDevice(server, userId = id, identity = identity, dbFile = bobFile, clock = clock))
    }

    private fun envelope(sender: String, payload: ByteArray, id: String = UUID.randomUUID().toString()) =
        IncomingEnvelope(
            seq = 0,
            id = id,
            sender = sender,
            kind = "envelope",
            refId = null,
            serverTs = now,
            payload = payload,
        )

    private fun CryptoDevice.seal(to: CryptoDevice, body: String): ByteArray = runBlocking {
        assertEquals(SessionStatus.Ready, crypto.ensureSession(to.userId))
        (crypto.encrypt(to.userId, PayloadCodec.encode(Payload.Text(body))) { it } as EncryptResult.Ok).value
    }

    private fun receive(e: IncomingEnvelope) = runBlocking { assertTrue(pipeline.process(bob.userId, e)) }

    /** An envelope from [from] that can't be decrypted: a placeholder and a queued reset. */
    private fun failFrom(from: CryptoDevice, id: String = UUID.randomUUID().toString()) =
        id.also { receive(envelope(from.userId, byteArrayOf(9, 9, 9), it)) }

    private fun runResets() = runBlocking { resets.run(bob.userId) }

    /** Raw database access, on Bob's crypto thread (Room refuses the main thread). */
    private fun <T> onBob(block: () -> T): T = runBlocking { bob.crypto.transaction(block) }

    /** SessionReset requests in Bob's outbox, oldest first: (outbox id, failed ids). */
    private fun sentResets(): List<Pair<String, Set<String>>> = onBob {
        bob.db.query("SELECT messageId, payload FROM outbox ORDER BY seq", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val reset = PayloadCodec.decode(c.getBlob(1)) as? Payload.SessionReset ?: continue
                    add(c.getString(0) to reset.failed.toSet())
                }
            }
        }
    }

    private fun placeholder(sender: CryptoDevice, id: String) =
        onBob { bob.db.cryptoDao().message(sender.userId, id)?.placeholder }

    private fun delivered(resetId: String) = receive(
        IncomingEnvelope(0, UUID.randomUUID().toString(), alice.userId, "delivered", resetId, now, ByteArray(0)),
    )

    private fun session(peer: CryptoDevice): ByteArray =
        onBob { bob.store.loadSession(SessionCrypto.address(peer.userId)).serialize() }

    @Test
    fun failuresWithinTheCooldownGoOutTogetherInTheNextReset() {
        val first = List(3) { failFrom(alice) }
        runResets()
        assertEquals(listOf(first.toSet()), sentResets().map { it.second })

        val late = failFrom(alice) // arrives while the first reset is outstanding
        now += 60_000
        runResets()
        assertEquals("cooldown: no second reset yet", 1, sentResets().size)
        assertEquals(Placeholder.Pending.name, placeholder(alice, late))

        now += ResetCoordinator.COOLDOWN_MS
        runResets()
        assertEquals(listOf(first.toSet(), setOf(late)), sentResets().map { it.second })
    }

    @Test
    fun anOfflineSenderStillRecoversBecauseTheClockStartsOnDelivery() {
        val id = failFrom(alice)
        runResets()
        val (resetId, _) = sentResets().single()

        now += 4 * DAY // the sender's phone is off; the reset is never delivered
        runResets()
        assertEquals(1, sentResets().size)
        assertEquals("still waiting, not given up", Placeholder.Pending.name, placeholder(alice, id))

        delivered(resetId)
        now += 23 * HOUR
        runResets()
        assertEquals("the 24 h window has not passed", 1, sentResets().size)

        now += 2 * HOUR
        runResets()
        assertEquals("unanswered: asked again", 2, sentResets().size)

        delivered(sentResets().last().first)
        now += 25 * HOUR
        runResets()
        delivered(sentResets().last().first)
        now += 25 * HOUR
        runResets()
        assertEquals("three attempts, then it gives up", 3, sentResets().size)
        assertEquals(Placeholder.Unrecoverable.name, placeholder(alice, id))
    }

    @Test
    fun aResetToAParkedContactWaitsAndKeepsTheSession() {
        receive(envelope(alice.userId, alice.seal(bob, "hello")))
        onBob { bob.db.cryptoDao().park(ParkedRecipientEntity(alice.userId, ParkReason.KeyChanged.name, now + DAY)) }
        val id = failFrom(alice)
        assertEquals(Placeholder.Waiting.name, placeholder(alice, id))
        val before = session(alice)

        runResets()
        assertTrue("no reset while parked", sentResets().isEmpty())
        assertEquals(Placeholder.Waiting.name, placeholder(alice, id))
        assertTrue("the old session is kept", before.contentEquals(session(alice)))

        onBob { bob.db.cryptoDao().unpark(alice.userId) }
        runResets()
        assertEquals(listOf(setOf(id)), sentResets().map { it.second })
        assertEquals(Placeholder.Pending.name, placeholder(alice, id))
    }

    @Test
    fun aFailureAcknowledgedBeforeAKillIsStillResetAfterRestart() {
        val id = failFrom(alice) // acknowledged to the server; then the app is killed
        restartBob()
        runResets()
        assertEquals(listOf(setOf(id)), sentResets().map { it.second })
    }

    @Test
    fun twoSendersMayUseTheSameTransportId() {
        val carol = CryptoDevice(server).apply { publishKeys() }
        try {
            carol.pin(bob)
            bob.pin(carol)
            receive(envelope(alice.userId, alice.seal(bob, "from alice"), id = "same"))
            receive(envelope(carol.userId, carol.seal(bob, "from carol"), id = "same"))
            val bodies = onBob {
                bob.db.query("SELECT body FROM messages ORDER BY localOrder", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0)) }
                }
            }
            assertEquals(listOf("from alice", "from carol"), bodies)
        } finally {
            carol.close()
        }
    }

    @Test
    fun aPoisonEnvelopeIsFinalisedAfterThreeAttemptsAcrossRestarts() {
        // Storing this message always fails, as an unexpected storage error would.
        onBob {
            bob.db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER poison BEFORE INSERT ON messages WHEN NEW.body = 'poison' " +
                    "BEGIN SELECT RAISE(ABORT, 'poison'); END",
            )
        }
        val e = envelope(alice.userId, alice.seal(bob, "poison"))
        repeat(2) { attempt ->
            try {
                runBlocking { pipeline.process(bob.userId, e) }
                fail("attempt ${attempt + 1} must not be acknowledged")
            } catch (_: Exception) {
            }
            restartBob()
        }
        runBlocking { assertTrue("third attempt gives up and acks", pipeline.process(bob.userId, e)) }
        assertEquals(Placeholder.Pending.name, placeholder(alice, e.id))
        runResets()
        assertEquals(listOf(setOf(e.id)), sentResets().map { it.second })
        assertFalse(
            onBob {
                bob.db.query("SELECT 1 FROM messages WHERE body = 'poison'", null).use { it.moveToFirst() }
            },
        )
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
