package dev.whispr.data

import dev.whispr.data.media.MediaCrypto
import dev.whispr.data.media.PreparedMedia
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.AttachmentState
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.GroupResult
import dev.whispr.domain.model.MediaSource
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.SendResult
import dev.whispr.domain.model.UserId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Encrypted media round trips through real engines and a relay that stores the blobs. */
@RunWith(RobolectricTestRunner::class)
class MediaTest {
    private val server = MockWebServer()
    private val relay = FakeRelay()
    private lateinit var alice: RelayDevice
    private lateinit var bob: RelayDevice
    private lateinit var carol: RelayDevice

    private val marker = "whispr-media-marker-${System.nanoTime()}"

    @Before
    fun setUp() {
        server.dispatcher = relay
        server.start()
        val url = server.url("/").toString()
        alice = RelayDevice("Alice", relay, url)
        bob = RelayDevice("Bob", relay, url)
        carol = RelayDevice("Carol", relay, url)
        alice.knows(bob)
        bob.knows(alice)
        alice.knows(carol)
        carol.knows(alice)
        listOf(alice, bob, carol).forEach { it.start() }
        eventually("keys") { listOf(alice, bob, carol).all { relay.keyServer.oneTimeLeft(it.id) > 0 } }
    }

    @After
    fun tearDown() {
        listOf(alice, bob, carol).forEach { it.close() }
        server.close()
    }

    /** A "JPEG" whose bytes contain [marker] in the clear, like EXIF text would. */
    private fun picture(): ByteArray = ("ÿØÿ" + marker.repeat(50)).toByteArray()

    private fun assertOnlyCiphertextStored(plaintext: ByteArray) {
        assertTrue("nothing uploaded", relay.blobs.isNotEmpty())
        for (blob in relay.blobs.values) {
            assertFalse("marker in stored blob", String(blob, Charsets.ISO_8859_1).contains(marker))
            assertFalse("plaintext prefix in stored blob", blob.copyOf(3).contentEquals(plaintext.copyOf(3)))
            assertEquals(plaintext.size + MediaCrypto.OVERHEAD, blob.size)
        }
        // Not in any frame or HTTP body either (keys travel only inside ciphertext).
        relay.wire.forEach { assertFalse(it.contains(marker)) }
    }

    @Test
    fun imageRoundTripOneToOneAndObjectStorageHoldsOnlyCiphertext() = runBlocking {
        val bytes = picture()
        alice.preparer.files["content://photo"] = PreparedMedia(
            bytes,
            "image/jpeg",
            null,
            width = 640,
            height = 480,
            thumbnail = byteArrayOf(1, 2, 3),
        )
        val conv = alice.direct(bob)
        assertEquals(SendResult.Ok, alice.repo.sendMedia(conv, MediaSource("content://photo", AttachmentKind.Image)))
        val bobConv = bob.direct(alice)
        eventually("bob gets the image message") { bob.messages(bobConv).any { it.attachment != null } }
        val received = bob.messages(bobConv).single { it.attachment != null }
        assertEquals(AttachmentState.Remote, received.attachment!!.state)
        assertArrayEquals(byteArrayOf(1, 2, 3), received.attachment!!.thumbnail)
        assertEquals(640, received.attachment!!.width)

        bob.repo.download(bobConv, received.id)
        eventually("downloaded") {
            bob.messages(bobConv).single { it.attachment != null }.attachment!!.state ==
                AttachmentState.Ready
        }
        assertArrayEquals(bytes, bob.repo.attachmentBytes(bobConv, received.id))
        eventually("alice sees delivered") {
            alice.messages(conv).single().status == MessageStatus.Delivered
        }
        assertOnlyCiphertextStored(bytes)
    }

    @Test
    fun voiceAndFilesWorkInGroups() = runBlocking {
        val g = (alice.groups.create("Band", listOf(UserId(bob.id), UserId(carol.id))) as GroupResult.Ok).group
        eventually("members joined") {
            listOf(bob, carol).all { d -> d.groups.observeGroup(g).first()?.members?.size == 3 }
        }
        val voice = (marker + "voice").toByteArray()
        bob.preparer.files["content://voice"] = PreparedMedia(voice, "audio/mp4", null, durationMs = 4_200)
        val doc = (marker + "pdf").toByteArray()
        bob.preparer.files["content://doc"] = PreparedMedia(doc, "application/pdf", "../../etc/notes.pdf")
        assertEquals(
            SendResult.Ok,
            bob.repo.sendMedia(g.conversation, MediaSource("content://voice", AttachmentKind.Voice)),
        )
        assertEquals(
            SendResult.Ok,
            bob.repo.sendMedia(g.conversation, MediaSource("content://doc", AttachmentKind.File)),
        )
        for (d in listOf(alice, carol)) {
            eventually("${d.name} gets both") { d.messages(g.conversation).count { it.attachment != null } == 2 }
            val items = d.messages(g.conversation).filter { it.attachment != null }
            items.forEach { d.repo.download(g.conversation, it.id) }
            val voiceMsg = items.single { it.attachment!!.kind == AttachmentKind.Voice }
            val docMsg = items.single { it.attachment!!.kind == AttachmentKind.File }
            assertEquals(4_200L, voiceMsg.attachment!!.durationMs)
            // Names are reduced to a bare file name: no path from the sender.
            assertEquals("notes.pdf", docMsg.attachment!!.fileName)
            assertEquals("Bob", voiceMsg.authorName)
            assertArrayEquals(voice, d.repo.attachmentBytes(g.conversation, voiceMsg.id))
            assertArrayEquals(doc, d.repo.attachmentBytes(g.conversation, docMsg.id))
        }
        // One upload per file, however many members: fan-out shares the blob.
        assertEquals(2, relay.blobs.size)
        relay.blobs.values.forEach { assertFalse(String(it, Charsets.ISO_8859_1).contains(marker)) }
    }

    @Test
    fun tamperedOrExpiredBlobsAreRejected() = runBlocking {
        val bytes = picture()
        alice.preparer.files["content://a"] = PreparedMedia(bytes, "image/jpeg", null)
        alice.preparer.files["content://b"] = PreparedMedia(bytes, "image/jpeg", null)
        val conv = alice.direct(bob)
        alice.repo.sendMedia(conv, MediaSource("content://a", AttachmentKind.Image))
        alice.repo.sendMedia(conv, MediaSource("content://b", AttachmentKind.Image))
        val bobConv = bob.direct(alice)
        eventually("both arrive") { bob.messages(bobConv).count { it.attachment != null } == 2 }
        val (first, second) = bob.messages(bobConv).filter { it.attachment != null }

        // The server flips a byte in one blob and deletes the other (retention).
        val ids = relay.blobs.keys.toList()
        val firstId = bob.engine.transaction {
            bob.db.groupDao().attachment(bob.db.groupDao().messageIn(bobConv.value, first.id)!!.localOrder)!!.remoteId!!
        }
        relay.blobs[firstId] = relay.blobs.getValue(firstId).also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        relay.blobs.remove(ids.single { it != firstId })

        bob.repo.download(bobConv, first.id)
        bob.repo.download(bobConv, second.id)
        eventually("states settle") {
            bob.messages(bobConv).filter { it.attachment != null }.map { it.attachment!!.state }.toSet() ==
                setOf(AttachmentState.Corrupt, AttachmentState.Expired)
        }
        assertNull(bob.repo.attachmentBytes(bobConv, first.id))
        assertNull(bob.repo.attachmentBytes(bobConv, second.id))
    }

    @Test
    fun unreadableFilesAndBlockedChatsAreRefused() = runBlocking {
        val conv = alice.direct(bob)
        assertEquals(
            SendResult.Unreadable,
            alice.repo.sendMedia(conv, MediaSource("content://missing", AttachmentKind.File)),
        )
        // Nobody we know: refused.
        assertEquals(
            SendResult.NotAllowed,
            alice.repo.sendMedia(
                ConversationId("00000000-0000-4000-8000-000000000000"),
                MediaSource("x", AttachmentKind.File),
            ),
        )
    }

    @Test
    fun mediaCryptoAuthenticatesEverything() {
        val plain = "attack at dawn".toByteArray()
        val sealed = MediaCrypto.seal(plain)
        assertArrayEquals(plain, MediaCrypto.open(sealed.blob, sealed.key, sealed.digest))
        assertEquals(32, sealed.key.size)
        // Fresh key and nonce every time.
        val again = MediaCrypto.seal(plain)
        assertFalse(sealed.key.contentEquals(again.key))
        assertFalse(sealed.blob.contentEquals(again.blob))
        // Wrong digest, wrong key, flipped byte, truncation: all refused.
        assertNull(MediaCrypto.open(sealed.blob, sealed.key, again.digest))
        assertNull(MediaCrypto.open(sealed.blob, again.key, sealed.digest))
        for (i in sealed.blob.indices) {
            val bad = sealed.blob.copyOf().also { it[i] = (it[i] + 1).toByte() }
            // Even with a matching digest the GCM tag catches it.
            assertNull("byte $i", MediaCrypto.open(bad, sealed.key, MediaCrypto.sha256(bad)))
        }
        val short = sealed.blob.copyOf(sealed.blob.size - 1)
        assertNull(MediaCrypto.open(short, sealed.key, MediaCrypto.sha256(short)))
    }

    private fun eventually(what: String, cond: suspend () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 20_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(25)
        }
    }
}
