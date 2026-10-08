package dev.whispr.data

import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.media.PreparedMedia
import dev.whispr.data.messaging.Payload
import dev.whispr.data.messaging.PayloadCodec
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.AttachmentState
import dev.whispr.domain.model.GroupResult
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.MediaSource
import dev.whispr.domain.model.SendResult
import dev.whispr.domain.model.UserId
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Reply, forward, delete, disappearing messages and search between real devices. */
@RunWith(RobolectricTestRunner::class)
class MessageActionsTest {
    private val server = MockWebServer()
    private val relay = FakeRelay()
    private lateinit var alice: RelayDevice
    private lateinit var bob: RelayDevice
    private lateinit var carol: RelayDevice

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

    private suspend fun aliceSaysToBob(text: String): String {
        alice.repo.sendText(UserId(bob.id), text)
        eventually("bob has: $text") { bob.texts(bob.direct(alice)).contains(text) }
        return alice.messages(alice.direct(bob)).single { it.text == text }.id
    }

    @Test
    fun replyCarriesAQuoteResolvedLocally() = runBlocking {
        val original = aliceSaysToBob("lunch?")
        assertTrue(bob.repo.sendText(UserId(alice.id), "yes", replyTo = original))
        eventually("alice has the reply") { alice.texts(alice.direct(bob)).contains("yes") }
        val quote = alice.messages(alice.direct(bob)).single { it.text == "yes" }.quote!!
        assertEquals(original, quote.messageId)
        assertEquals("lunch?", quote.text)
        assertTrue(quote.outgoing)
        assertTrue(quote.found)
    }

    @Test
    fun deleteForEveryoneLeavesATombstoneOnBothSides() = runBlocking {
        val id = aliceSaysToBob("oops")
        assertTrue(alice.repo.deleteForEveryone(alice.direct(bob), id))
        eventually("bob sees it deleted") {
            bob.messages(bob.direct(alice)).single { it.id == id }.let { it.deleted && it.text.isEmpty() }
        }
        assertTrue(alice.messages(alice.direct(bob)).single { it.id == id }.deleted)
        // Nobody else's message can be deleted for everyone.
        assertFalse(bob.repo.deleteForEveryone(bob.direct(alice), id))
    }

    @Test
    fun forgedDeleteFromTheOtherSideIsIgnored() = runBlocking {
        val id = aliceSaysToBob("keep me")
        val now = System.currentTimeMillis()
        // A modified client: Bob asks to delete Alice's message.
        bob.db.outboxDao().enqueue(
            OutboxEntity(
                messageId = UUID.randomUUID().toString(),
                conversationId = bob.direct(alice).value,
                recipientId = alice.id,
                payload = PayloadCodec.encode(Payload.Delete(id, now)),
                clientTs = now,
            ),
        )
        bob.repo.sendText(UserId(alice.id), "after")
        eventually("alice has the later message") { alice.texts(alice.direct(bob)).contains("after") }
        val kept = alice.messages(alice.direct(bob)).single { it.id == id }
        assertFalse(kept.deleted)
        assertEquals("keep me", kept.text)
    }

    @Test
    fun timerFromSomeoneOutsideTheGroupIsIgnored() = runBlocking {
        val g = (alice.groups.create("Pair", listOf(UserId(bob.id))) as GroupResult.Ok).group
        eventually("bob joined") { bob.groups.observeGroup(g).first()?.status == GroupStatus.Active }
        val now = System.currentTimeMillis()
        // Carol is Alice's contact but not in the group, and claims to set its timer.
        carol.db.outboxDao().enqueue(
            OutboxEntity(
                messageId = UUID.randomUUID().toString(),
                conversationId = carol.direct(alice).value,
                recipientId = alice.id,
                payload = PayloadCodec.encode(Payload.Timer(3600, now, g = g.value)),
                clientTs = now,
            ),
        )
        carol.repo.sendText(UserId(alice.id), "after")
        eventually("alice has the later message") { alice.texts(alice.direct(carol)).contains("after") }
        assertEquals(0L, alice.repo.observeTimer(g.conversation).first())
        assertTrue(alice.messages(g.conversation).none { it.system && it.text.contains("disappearing") })
    }

    @Test
    fun deleteForMeIsLocalOnly() = runBlocking {
        val id = aliceSaysToBob("just mine")
        bob.repo.deleteForMe(bob.direct(alice), bob.messages(bob.direct(alice)).single { it.text == "just mine" }.id)
        assertFalse(bob.texts(bob.direct(alice)).contains("just mine"))
        assertEquals("just mine", alice.messages(alice.direct(bob)).single { it.id == id }.text)
    }

    @Test
    fun disappearingMessagesExpireForSenderAndAfterReadingForReceiver() = runBlocking {
        val conv = alice.direct(bob)
        val bobConv = bob.direct(alice)
        assertTrue(alice.repo.setTimer(conv, 1))
        eventually("bob's timer follows") { bob.repo.observeTimer(bobConv).first() == 1L }
        alice.repo.sendText(UserId(bob.id), "self-destruct")
        eventually("bob has it") { bob.texts(bobConv).contains("self-destruct") }
        val received = bob.messages(bobConv).single { it.text == "self-destruct" }
        assertEquals(Duration.ofSeconds(1), received.expiresIn)
        eventually("gone for alice") { !alice.texts(conv).contains("self-destruct") }
        // Unread: the clock has not started on Bob's side.
        assertTrue(bob.texts(bobConv).contains("self-destruct"))
        bob.repo.markRead(bobConv)
        eventually("gone for bob after reading") { !bob.texts(bobConv).contains("self-destruct") }
        // Both sides recorded the change as a notice.
        assertTrue(bob.messages(bobConv).any { it.system && it.text.contains("1 second") })
    }

    @Test
    fun forwardedMediaIsResealedAndReadable() = runBlocking {
        val bytes = "forward-me".repeat(20).toByteArray()
        alice.preparer.files["content://pic"] = PreparedMedia(bytes, "image/jpeg", null, width = 1, height = 1)
        assertEquals(
            SendResult.Ok,
            alice.repo.sendMedia(alice.direct(bob), MediaSource("content://pic", AttachmentKind.Image)),
        )
        eventually("uploaded") {
            alice.messages(alice.direct(bob)).single().attachment?.state == AttachmentState.Ready
        }
        val id = alice.messages(alice.direct(bob)).single().id
        assertEquals(SendResult.Ok, alice.repo.forward(alice.direct(bob), id, alice.direct(carol)))
        val carolConv = carol.direct(alice)
        eventually("carol gets it") { carol.messages(carolConv).any { it.attachment != null } }
        val got = carol.messages(carolConv).single { it.attachment != null }
        assertTrue(got.forwarded)
        carol.repo.download(carolConv, got.id)
        eventually("downloaded") {
            carol.messages(carolConv).single().attachment!!.state == AttachmentState.Ready
        }
        assertArrayEquals(bytes, carol.repo.attachmentBytes(carolConv, got.id))
        // A fresh key and blob: the server cannot link the two conversations.
        assertEquals(2, relay.blobs.size)
    }

    @Test
    fun searchFindsTextAndTreatsWildcardsLiterally() = runBlocking {
        aliceSaysToBob("meet at 50% off sale")
        aliceSaysToBob("meet at 50 percent")
        aliceSaysToBob("snake_case_name")
        assertEquals(listOf("meet at 50% off sale"), alice.repo.search("50%").map { it.message.text })
        assertEquals(listOf("snake_case_name"), alice.repo.search("e_c").map { it.message.text })
        assertEquals(2, alice.repo.search("MEET").size)
        val hit = alice.repo.search("snake").single()
        assertEquals("Bob", hit.title)
        assertEquals(UserId(bob.id), hit.peer)
        assertTrue(alice.repo.search(" ").isEmpty())
    }

    private fun eventually(what: String, cond: suspend () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 20_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(25)
        }
    }
}
