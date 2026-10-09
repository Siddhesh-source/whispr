package dev.whispr.data

import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.db.StatusEntity
import dev.whispr.data.media.PreparedMedia
import dev.whispr.data.messaging.Payload
import dev.whispr.data.messaging.PayloadCodec
import dev.whispr.domain.model.CallSignal
import dev.whispr.domain.model.HangupReason
import dev.whispr.domain.model.IceCandidate
import dev.whispr.domain.model.IncomingCallSignal
import dev.whispr.domain.model.SendResult
import dev.whispr.domain.model.StatusKind
import dev.whispr.domain.model.StatusSendState
import dev.whispr.domain.model.UserId
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

/** Statuses and call signaling between real devices (real libsignal) on a fake relay. */
@RunWith(RobolectricTestRunner::class)
class StatusAndCallsTest {
    private val server = MockWebServer()
    private val relay = FakeRelay()
    private lateinit var alice: RelayDevice
    private lateinit var bob: RelayDevice
    private lateinit var carol: RelayDevice

    /** Mallory has Alice as a contact; Alice never accepted Mallory. */
    private lateinit var mallory: RelayDevice

    @Before
    fun setUp() {
        server.dispatcher = relay
        server.start()
        val url = server.url("/").toString()
        alice = RelayDevice("Alice", relay, url)
        bob = RelayDevice("Bob", relay, url)
        carol = RelayDevice("Carol", relay, url)
        mallory = RelayDevice("Mallory", relay, url)
        alice.knows(bob)
        bob.knows(alice)
        alice.knows(carol)
        carol.knows(alice)
        mallory.knows(alice)
        listOf(alice, bob, carol, mallory).forEach { it.start() }
        eventually("keys") { listOf(alice, bob, carol, mallory).all { relay.keyServer.oneTimeLeft(it.id) > 0 } }
    }

    @After
    fun tearDown() {
        listOf(alice, bob, carol, mallory).forEach { it.close() }
        server.close()
    }

    // ---- Status ----

    @Test
    fun textStatusReachesEveryAcceptedContact() = runBlocking {
        assertTrue(alice.statuses.postText("Climbing today", background = 2))
        for (d in listOf(bob, carol)) {
            eventually("${d.name} sees it") { d.statuses.observeFeed().first().recent.isNotEmpty() }
            val author = d.statuses.observeFeed().first().recent.single()
            assertEquals(alice.id, author.author.value)
            assertEquals("Alice", author.name)
            assertEquals("Climbing today", author.items.single().text)
            assertEquals(2, author.items.single().background)
        }
        val mine = alice.statuses.observeFeed().first().mine.single()
        assertTrue(mine.mine)
        assertEquals(StatusSendState.Sent, mine.sendState)
        // Mallory added Alice, but Alice never accepted: nothing was sent to Mallory.
        Thread.sleep(300)
        assertTrue(mallory.statuses.observeFeed().first().recent.isEmpty())
    }

    @Test
    fun viewingMovesAnAuthorToViewedAndSendsNothingWithReceiptsOff() = runBlocking {
        dev.whispr.data.messaging.RoomSettingsRepository(bob.db.settingDao()).setReadReceipts(false)
        alice.statuses.postText("one", 0)
        eventually("bob has it") { bob.statuses.observeFeed().first().recent.isNotEmpty() }
        val item = bob.statuses.observeFeed().first().recent.single().items.single()
        val before = relay.sentFrom(bob.id)
        bob.statuses.markViewed(UserId(alice.id), item.id)
        val feed = bob.statuses.observeFeed().first()
        assertTrue(feed.recent.isEmpty())
        assertTrue(feed.viewed.single().allViewed)
        Thread.sleep(300)
        assertEquals("viewing must not send anything", before, relay.sentFrom(bob.id))
    }

    @Test
    fun photoStatusIsUploadedOnceAndOpensForEachContact() = runBlocking {
        val photo = ByteArray(5_000) { (it % 251).toByte() }
        alice.preparer.files["content://photo"] =
            PreparedMedia(photo, "image/jpeg", null, width = 40, height = 30, thumbnail = byteArrayOf(1, 2, 3))
        val before = relay.blobs.size
        assertEquals(SendResult.Ok, alice.statuses.postImage("content://photo", "summit"))
        for (d in listOf(bob, carol)) {
            eventually("${d.name} has the photo status") { d.statuses.observeFeed().first().recent.isNotEmpty() }
            val item = d.statuses.observeFeed().first().recent.single().items.single()
            assertEquals(StatusKind.Image, item.kind)
            assertEquals("summit", item.text)
            assertArrayEquals(photo, d.statuses.imageBytes(item.author, item.id))
        }
        assertEquals("one blob for every contact", before + 1, relay.blobs.size)
        // The server only ever held ciphertext.
        assertFalse(relay.blobs.values.any { it.contentEquals(photo) })
    }

    @Test
    fun failedPhotoUploadShowsNotSentAndRetrySendsIt() = runBlocking {
        alice.preparer.files["content://p"] = PreparedMedia(ByteArray(100) { 7 }, "image/jpeg", null)
        relay.failUploads = true
        alice.statuses.postImage("content://p", "")
        eventually("failed") { alice.statuses.observeFeed().first().mine.single().sendState == StatusSendState.Failed }
        assertTrue(bob.statuses.observeFeed().first().recent.isEmpty())
        relay.failUploads = false
        alice.statuses.retry(alice.statuses.observeFeed().first().mine.single().id)
        eventually("bob has it after retry") { bob.statuses.observeFeed().first().recent.isNotEmpty() }
        assertEquals(StatusSendState.Sent, alice.statuses.observeFeed().first().mine.single().sendState)
    }

    @Test
    fun authorDeletesEverywhereButNobodyElseCan() = runBlocking {
        alice.statuses.postText("short-lived", 1)
        eventually("both have it") {
            listOf(bob, carol).all { it.statuses.observeFeed().first().recent.isNotEmpty() }
        }
        val sid = alice.statuses.observeFeed().first().mine.single().id
        // Bob forges a delete for Alice's status: he can only delete his own.
        forge(bob, alice, Payload.StatusDelete(sid, System.currentTimeMillis()))
        bob.repo.sendText(UserId(alice.id), "marker")
        eventually("alice got the marker") { alice.texts(alice.direct(bob)).contains("marker") }
        assertEquals(1, alice.statuses.observeFeed().first().mine.size)

        alice.statuses.delete(sid)
        assertTrue(alice.statuses.observeFeed().first().mine.isEmpty())
        for (d in listOf(bob, carol)) {
            eventually("${d.name} lost it") { d.statuses.observeFeed().first().recent.isEmpty() }
        }
    }

    @Test
    fun statusesFromStrangersOrTooOldAreDropped() = runBlocking {
        val now = System.currentTimeMillis()
        // Mallory is not Alice's accepted contact.
        forge(mallory, alice, Payload.Status(UUID.randomUUID().toString(), now, "text", "hi"))
        mallory.repo.sendText(UserId(alice.id), "hello")
        // Bob is, but this one is 26 hours old.
        forge(bob, alice, Payload.Status(UUID.randomUUID().toString(), now - 26 * HOUR, "text", "old"))
        // And this one claims a photo with no attachment.
        forge(bob, alice, Payload.Status(UUID.randomUUID().toString(), now, "image", "no pointer"))
        bob.repo.sendText(UserId(alice.id), "marker")
        eventually("alice got the marker") { alice.texts(alice.direct(bob)).contains("marker") }
        eventually("alice got mallory's request") { alice.db.contactDao().get(mallory.id) != null }
        val feed = alice.statuses.observeFeed().first()
        assertTrue(feed.recent.isEmpty() && feed.viewed.isEmpty())
    }

    @Test
    fun expiredStatusesAndTheirPhotosAreSwept() = runBlocking {
        val blob = File.createTempFile("status", ".bin").apply { writeBytes(byteArrayOf(1)) }
        val past = System.currentTimeMillis() - 25 * HOUR
        alice.engine.transaction {
            alice.db.statusDao().put(
                StatusEntity(bob.id, "old", "image", "", 0, past, past + 24 * HOUR, blobPath = blob.absolutePath),
            )
        }
        alice.engine.sweepExpired()
        assertNull(alice.engine.transaction { alice.db.statusDao().get(bob.id, "old") })
        assertFalse(blob.exists())
    }

    @Test
    fun statusEntriesNeverGoAheadOfCallsAndStaleOnesAreDropped() = runBlocking {
        // A phone whose engine never starts: nothing sends or prunes this outbox meanwhile.
        val idle = RelayDevice("Idle", relay, server.url("/").toString())
        try {
            val now = System.currentTimeMillis()
            val dao = idle.db.cryptoDao()
            idle.engine.transaction {
                repeat(3) {
                    dao.enqueue(entry(bob, OutboxEntity.PRIORITY_STATUS, now - 25 * HOUR))
                    dao.enqueue(entry(bob, OutboxEntity.PRIORITY_NORMAL, now))
                }
                dao.enqueue(entry(bob, OutboxEntity.PRIORITY_CALL, now))
            }
            val (head, dropped) = idle.engine.transaction {
                dao.outboxHead(now) to dao.dropStaleStatus(now - 24 * HOUR)
            }
            assertEquals(OutboxEntity.PRIORITY_CALL, head!!.priority)
            assertEquals(3, dropped)
        } finally {
            idle.close()
        }
    }

    // ---- Calls ----

    @Test
    fun callSignalsArriveInOrderWithTheServerTime() = runBlocking {
        val got = Collections.synchronizedList(mutableListOf<IncomingCallSignal>())
        val job = alice.scope.launch { bob.calls.incoming.collect { got += it } }
        Thread.sleep(100)
        val cid = UUID.randomUUID().toString()
        assertTrue(alice.calls.send(UserId(bob.id), CallSignal.Offer(cid, "v=0 offer", video = true)))
        assertTrue(
            alice.calls.send(UserId(bob.id), CallSignal.Ice(cid, listOf(IceCandidate("0", 0, "candidate:1 1 udp")))),
        )
        assertTrue(alice.calls.send(UserId(bob.id), CallSignal.Hangup(cid, HangupReason.Timeout)))
        eventually("three signals") { got.size == 3 }
        job.cancel()
        assertEquals(alice.id, got[0].peer.value)
        assertEquals(CallSignal.Offer(cid, "v=0 offer", video = true), got[0].signal)
        assertEquals("candidate:1 1 udp", (got[1].signal as CallSignal.Ice).candidates.single().sdp)
        assertEquals(HangupReason.Timeout, (got[2].signal as CallSignal.Hangup).reason)
        // Freshness is judged on the server's clock, which the relay stamps.
        assertTrue(Math.abs(got[0].serverTime.toEpochMilli() - System.currentTimeMillis()) < 10_000)
    }

    @Test
    fun strangersCannotRingAndRequestsCannotBeCalled() = runBlocking {
        val got = Collections.synchronizedList(mutableListOf<IncomingCallSignal>())
        val job = alice.scope.launch { alice.calls.incoming.collect { got += it } }
        Thread.sleep(100)
        // Mallory calls Alice, who never accepted Mallory.
        forge(mallory, alice, Payload.CallOffer("c1", "sdp", video = false, ts = System.currentTimeMillis()))
        mallory.repo.sendText(UserId(alice.id), "hello")
        eventually("alice got mallory's request") { alice.db.contactDao().get(mallory.id) != null }
        Thread.sleep(300)
        job.cancel()
        assertTrue(got.isEmpty())
        // And Alice can't call someone whose request she hasn't accepted.
        assertFalse(alice.calls.send(UserId(mallory.id), CallSignal.Offer("c2", "sdp", video = false)))
    }

    private suspend fun forge(from: RelayDevice, to: RelayDevice, payload: Payload) {
        val now = System.currentTimeMillis()
        from.db.outboxDao().enqueue(
            OutboxEntity(
                messageId = UUID.randomUUID().toString(),
                conversationId = from.direct(to).value,
                recipientId = to.id,
                payload = PayloadCodec.encode(payload),
                clientTs = now,
            ),
        )
    }

    private fun entry(to: RelayDevice, priority: Int, ts: Long) = OutboxEntity(
        messageId = UUID.randomUUID().toString(),
        conversationId = alice.direct(to).value,
        recipientId = to.id,
        payload = PayloadCodec.encode(Payload.Typing),
        clientTs = ts,
        priority = priority,
    )

    private fun eventually(what: String, cond: suspend () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 15_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(25)
        }
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
