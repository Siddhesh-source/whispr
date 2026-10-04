package dev.whispr.data

import dev.whispr.data.crypto.DecryptResult
import dev.whispr.data.crypto.EncryptResult
import dev.whispr.data.crypto.ParkReason
import dev.whispr.data.crypto.SessionStatus
import dev.whispr.data.crypto.WireFormat
import dev.whispr.domain.model.TrustState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.signal.libsignal.protocol.IdentityKeyPair

/** Two real libsignal parties (PQXDH, then the Double Ratchet) on real databases. */
@RunWith(RobolectricTestRunner::class)
class SessionCryptoTest {
    private val server = FakeKeyServer()
    private lateinit var alice: CryptoDevice
    private lateinit var bob: CryptoDevice

    @Before
    fun setUp() {
        alice = CryptoDevice(server)
        bob = CryptoDevice(server)
        alice.publishKeys()
        bob.publishKeys()
        alice.pin(bob)
        bob.pin(alice)
    }

    @After
    fun tearDown() {
        alice.close()
        bob.close()
    }

    private fun CryptoDevice.seal(to: CryptoDevice, text: String): ByteArray = runBlocking {
        assertEquals(SessionStatus.Ready, crypto.ensureSession(to.userId))
        when (val r = crypto.encrypt(to.userId, text.toByteArray()) { it }) {
            is EncryptResult.Ok -> r.value
            is EncryptResult.Blocked -> error("encrypt blocked: ${r.reason}")
        }
    }

    private fun CryptoDevice.open(from: CryptoDevice, wire: ByteArray): DecryptResult<String> = runBlocking {
        crypto.decrypt(from.userId, wire) { String(it) }
    }

    @Test
    fun firstMessageUsesPqxdhThenTheRatchetCarriesTheReply() {
        val first = alice.seal(bob, "hello bob")
        assertEquals(WireFormat.TYPE_PREKEY, WireFormat.decode(first)!!.first)
        val opened = bob.open(alice, first) as DecryptResult.Ok
        assertEquals("hello bob", opened.value)
        assertTrue("first message consumes one of bob's one-time keys", opened.usedOneTimeKey)

        val reply = bob.seal(alice, "hi alice")
        assertEquals(WireFormat.TYPE_WHISPER, WireFormat.decode(reply)!!.first)
        assertEquals("hi alice", (alice.open(bob, reply) as DecryptResult.Ok).value)
        // Once Alice has heard back, her messages are plain ratchet messages.
        assertEquals(WireFormat.TYPE_WHISPER, WireFormat.decode(alice.seal(bob, "again"))!!.first)
    }

    @Test
    fun ciphertextNeverContainsThePlaintextAndHidesExactLength() {
        val marker = "MARKER-7f3a-plaintext"
        bob.open(alice, alice.seal(bob, "setup"))
        alice.open(bob, bob.seal(alice, "ack"))
        val short = alice.seal(bob, marker)
        val longer = alice.seal(bob, marker + "x".repeat(100))
        assertFalse(String(short, Charsets.ISO_8859_1).contains(marker))
        // 21 and 121 bytes both pad to one 160-byte block: same wire size.
        assertEquals(short.size, longer.size)
    }

    @Test
    fun outOfOrderMessagesStillDecrypt() {
        bob.open(alice, alice.seal(bob, "setup"))
        alice.open(bob, bob.seal(alice, "ack"))
        val m1 = alice.seal(bob, "one")
        val m2 = alice.seal(bob, "two")
        val m3 = alice.seal(bob, "three")
        assertEquals("three", (bob.open(alice, m3) as DecryptResult.Ok).value)
        assertEquals("one", (bob.open(alice, m1) as DecryptResult.Ok).value)
        assertEquals("two", (bob.open(alice, m2) as DecryptResult.Ok).value)
    }

    @Test
    fun replayedCiphertextIsRejected() {
        val m = alice.seal(bob, "once")
        bob.open(alice, m) as DecryptResult.Ok
        assertEquals(DecryptResult.Replay, bob.open(alice, m))
        val m2 = alice.seal(bob, "twice?")
        bob.open(alice, m2) as DecryptResult.Ok
        assertEquals(DecryptResult.Replay, bob.open(alice, m2))
    }

    @Test
    fun tamperedCiphertextIsRejectedAndLeavesTheSessionIntact() {
        bob.open(alice, alice.seal(bob, "setup"))
        alice.open(bob, bob.seal(alice, "ack"))
        val m = alice.seal(bob, "secret")
        assertEquals(WireFormat.TYPE_WHISPER, WireFormat.decode(m)!!.first)
        // Body, ciphertext and MAC: every authenticated byte matters.
        for (i in listOf(3, 10, m.size / 2, m.size - 9, m.size - 1)) {
            val tampered = m.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertTrue("byte $i", bob.open(alice, tampered) is DecryptResult.Failed)
        }
        // The genuine message still decrypts: failures changed no state.
        assertEquals("secret", (bob.open(alice, m) as DecryptResult.Ok).value)
    }

    @Test
    fun plaintextInjectedByTheServerIsRejected() {
        val injected = """{"t":"text","body":"from the server"}""".toByteArray()
        assertTrue(bob.open(alice, injected) is DecryptResult.Failed)
    }

    @Test
    fun failureInsideTheTransactionRollsTheRatchetBack() = runBlocking {
        bob.open(alice, alice.seal(bob, "setup"))
        val m = alice.seal(bob, "store me")
        try {
            bob.crypto.decrypt(alice.userId, m) { error("disk full") }
            fail("work exceptions must propagate so the envelope is not acked")
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }
        // Redelivery after the crash decrypts: the ratchet step was rolled back.
        assertEquals("store me", (bob.open(alice, m) as DecryptResult.Ok).value)
    }

    @Test
    fun sessionsSurviveARestart() {
        val file = File(Files.createTempDirectory("whispr-crypto").toFile(), "dave.db")
        val daveKeys = IdentityKeyPair.generate()
        var dave = CryptoDevice(server, identity = daveKeys, dbFile = file)
        dave.publishKeys()
        dave.pin(alice)
        alice.pin(dave)
        alice.open(dave, dave.seal(alice, "before restart")) as DecryptResult.Ok
        dave.open(alice, alice.seal(dave, "reply")) as DecryptResult.Ok

        // Process death: reopen the same database file.
        val id = dave.userId
        dave.close()
        dave = CryptoDevice(server, userId = id, identity = daveKeys, dbFile = file)
        val after = dave.seal(alice, "after restart")
        assertEquals(
            "ratchet continues, no new PreKey message",
            WireFormat.TYPE_WHISPER,
            WireFormat.decode(after)!!.first,
        )
        assertEquals("after restart", (alice.open(dave, after) as DecryptResult.Ok).value)
        dave.close()
    }

    @Test
    fun preKeyMessageReplayedAfterTheSessionWasDeletedIsDroppedAsAReplay() = runBlocking {
        val first = alice.seal(bob, "first")
        bob.open(alice, first) as DecryptResult.Ok
        // Bob's session is gone (e.g. reset); the one-time key was consumed.
        withContext(bob.dispatcher) { bob.db.cryptoDao().deleteSession(alice.userId) }
        assertEquals(DecryptResult.Replay, bob.open(alice, first))
    }

    @Test
    fun lastResortKeyRejectsAReplayedBaseKey() = runBlocking {
        server.drainOneTimeKeys(bob.userId)
        val first = alice.seal(bob, "via last resort")
        bob.open(alice, first) as DecryptResult.Ok
        withContext(bob.dispatcher) { bob.db.cryptoDao().deleteSession(alice.userId) }
        assertEquals(DecryptResult.Replay, bob.open(alice, first))
    }

    @Test
    fun bundleWithADifferentIdentityThanThePinIsRefusedAndFlagged() = runBlocking {
        // The server hands out a bundle for Bob under a key Alice never pinned.
        val impostor = IdentityKeyPair.generate()
        alice.pin(bob, impostor.publicKey.serialize())
        assertEquals(SessionStatus.Blocked(ParkReason.KeyChanged), alice.crypto.ensureSession(bob.userId))
        val contact = alice.db.contactDao().get(bob.userId)!!
        assertEquals(TrustState.KeyChanged.name, contact.trust)
        assertArrayEquals(impostor.publicKey.serialize(), contact.identityKey)
        assertArrayEquals(bob.identity.publicKey.serialize(), contact.pendingKey)
        assertFalse(alice.crypto.hasSession(bob.userId))
    }

    @Test
    fun messageUnderAChangedIdentityIsUntrustedAndCarriesTheNewKey() {
        bob.pin(alice, IdentityKeyPair.generate().publicKey.serialize())
        val result = bob.open(alice, alice.seal(bob, "who am I?"))
        assertTrue(result is DecryptResult.Untrusted)
        assertArrayEquals(alice.identity.publicKey.serialize(), (result as DecryptResult.Untrusted).identityKey)
    }

    @Test
    fun strangersFirstMessageCreatesARequestWithTheirKeyPinned() = runBlocking {
        val stranger = CryptoDevice(server).also {
            it.publishKeys()
            it.pin(bob)
        }
        assertEquals("hi", (bob.open(stranger, stranger.seal(bob, "hi")) as DecryptResult.Ok).value)
        val contact = bob.db.contactDao().get(stranger.userId)
        assertNotNull(contact)
        assertTrue(contact!!.isRequest)
        assertArrayEquals(stranger.identity.publicKey.serialize(), contact.identityKey)
        stranger.close()
    }

    @Test
    fun noKeysOnTheServerBlocksWithNoKeys() = runBlocking {
        val newcomer = CryptoDevice(server) // registered, never uploaded keys
        alice.pin(newcomer)
        assertEquals(SessionStatus.Blocked(ParkReason.NoKeys), alice.crypto.ensureSession(newcomer.userId))
        newcomer.close()
    }

    @Test
    fun typingIsOnlyEncryptedOverAnExistingSession() = runBlocking {
        assertEquals(null, alice.crypto.encryptTransient(bob.userId, "typing".toByteArray()))
        val before = server.oneTimeLeft(bob.userId)
        alice.seal(bob, "now there is a session")
        assertNotNull(alice.crypto.encryptTransient(bob.userId, "typing".toByteArray()))
        assertEquals("typing never fetches bundles", before - 1, server.oneTimeLeft(bob.userId))
    }
}
