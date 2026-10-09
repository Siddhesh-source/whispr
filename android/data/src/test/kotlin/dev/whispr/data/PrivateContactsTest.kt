package dev.whispr.data

import dev.whispr.data.messaging.ContactLink
import dev.whispr.data.messaging.RoomSettingsRepository
import dev.whispr.domain.model.AddContactResult
import dev.whispr.domain.model.GroupResult
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.UserId
import java.io.File
import java.nio.file.Files
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

/**
 * Private by default: a request is the only thing that reaches someone who
 * hasn't accepted you. Plus what accepted contacts share (profile photos,
 * status views and likes, group names). Real libsignal, fake relay.
 */
@RunWith(RobolectricTestRunner::class)
class PrivateContactsTest {
    private val server = MockWebServer()
    private val relay = FakeRelay()
    private lateinit var alice: RelayDevice
    private lateinit var bob: RelayDevice
    private lateinit var carol: RelayDevice
    private val devices get() = listOf(alice, bob, carol)

    @Before
    fun setUp() {
        server.dispatcher = relay
        server.start()
        val url = server.url("/").toString()
        alice = RelayDevice("Alice", relay, url)
        bob = RelayDevice("Bob", relay, url)
        carol = RelayDevice("Carol", relay, url)
        devices.forEach { it.start() }
        eventually("keys") { devices.all { relay.keyServer.oneTimeLeft(it.id) > 0 } }
    }

    @After
    fun tearDown() {
        devices.forEach { it.close() }
        server.close()
    }

    @Test
    fun nothingButTheRequestFlowsUntilItIsAccepted() = runBlocking {
        val added = alice.contacts.addById(bob.id)
        assertTrue(added is AddContactResult.Added)
        assertTrue((added as AddContactResult.Added).contact.awaitingAccept)
        // Alice can't write or call yet.
        assertFalse(alice.repo.sendText(UserId(bob.id), "hi?"))
        assertFalse(alice.calls.send(UserId(bob.id), dev.whispr.domain.model.CallSignal.Hangup("c", HANGUP)))

        eventually("Bob sees the request") { bob.contacts.contact(UserId(alice.id))?.isRequest == true }
        assertEquals("Alice", bob.contacts.contact(UserId(alice.id))!!.displayName)
        assertFalse(bob.repo.sendText(UserId(alice.id), "who?"))

        bob.contacts.acceptRequest(UserId(alice.id))
        eventually("Alice is connected") { alice.contacts.contact(UserId(bob.id))?.connected == true }
        assertTrue(alice.texts(alice.direct(bob)).isEmpty())
        assertTrue(alice.messages(alice.direct(bob)).any { it.system && it.text == "Bob accepted your request" })

        assertTrue(alice.repo.sendText(UserId(bob.id), "hello"))
        eventually("Bob reads it") { bob.texts(bob.direct(alice)) == listOf("hello") }
    }

    @Test
    fun messagesFromSomeoneNotAcceptedLeaveNoTrace() = runBlocking {
        // Carol has Bob's key (say from an old client) and writes without a request.
        carol.knows(bob)
        assertTrue(carol.repo.sendText(UserId(bob.id), "buy now"))
        eventually("Bob's phone processed it") {
            carol.messages(carol.direct(bob)).single().status == MessageStatus.Delivered
        }
        assertTrue(bob.messages(bob.direct(carol)).isEmpty())
        assertTrue(bob.contacts.observeContacts().first().isEmpty())
        // Nor do statuses or calls get through.
        assertTrue(carol.statuses.postText("look", background = 0))
        carol.calls.send(UserId(bob.id), dev.whispr.domain.model.CallSignal.Hangup("c1", HANGUP))
        assertTrue(carol.repo.sendText(UserId(bob.id), "again"))
        eventually("processed") { carol.messages(carol.direct(bob)).all { it.status == MessageStatus.Delivered } }
        assertTrue(bob.statuses.observeFeed().first().recent.isEmpty())
        assertTrue(bob.messages(bob.direct(carol)).isEmpty())
    }

    @Test
    fun twoPeopleWhoAskEachOtherAreConnected() = runBlocking {
        alice.contacts.addById(bob.id)
        bob.contacts.addById(alice.id)
        eventually("both connected") {
            alice.contacts.contact(UserId(bob.id))?.connected == true &&
                bob.contacts.contact(UserId(alice.id))?.connected == true
        }
        assertTrue(bob.repo.sendText(UserId(alice.id), "hey"))
        eventually("delivered") { alice.texts(alice.direct(bob)) == listOf("hey") }
    }

    @Test
    fun profilePhotoGoesToAcceptedContacts() = runBlocking {
        connect(alice, bob)
        val dir = Files.createTempDirectory("avatar").toFile()
        val photo = File(dir, "avatar-1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val small = byteArrayOf(9, 8, 7, 6)
        File(photo.path + ContactLink.SHARE_SUFFIX).writeBytes(small)
        alice.db.accountDao().setAvatarPath(photo.path)
        alice.engine.transaction { ContactLink.broadcastProfile(alice.db.cryptoDao(), System::currentTimeMillis) }
        eventually("Bob has Alice's photo") { bob.contacts.contact(UserId(alice.id))?.avatar != null }
        assertArrayEquals(small, bob.contacts.contact(UserId(alice.id))!!.avatar)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun statusViewsFollowReadReceiptsAndLikesAlwaysCount() = runBlocking {
        connect(alice, bob)
        connect(alice, carol)
        RoomSettingsRepository(carol.db.settingDao()).setReadReceipts(false)
        assertTrue(alice.statuses.postText("Sunrise", background = 1))
        for (d in listOf(bob, carol)) {
            eventually("${d.name} has it") { d.statuses.observeFeed().first().recent.isNotEmpty() }
        }
        val item = bob.statuses.observeFeed().first().recent.single().items.single()
        bob.statuses.markViewed(UserId(alice.id), item.id)
        carol.statuses.markViewed(UserId(alice.id), item.id)
        bob.statuses.like(UserId(alice.id), item.id, liked = true)
        eventually("Alice sees one view and one like") {
            alice.statuses.observeFeed().first().mine.single().let { it.views == 1 && it.likes == 1 }
        }
        val viewers = alice.statuses.observeViewers(item.id).first()
        assertEquals(listOf("Bob"), viewers.map { it.name })
        assertTrue(viewers.single().liked)
        // Carol, with receipts off, liking still counts (it's explicit), as a view too.
        carol.statuses.like(UserId(alice.id), item.id, liked = true)
        eventually("two likes") { alice.statuses.observeFeed().first().mine.single().likes == 2 }
    }

    @Test
    fun anyMemberCanRenameTheGroupAndChangeItsPicture() = runBlocking {
        connect(alice, bob)
        connect(alice, carol)
        bob.knows(carol)
        carol.knows(bob)
        val g = (alice.groups.create("Hikers", listOf(UserId(bob.id), UserId(carol.id))) as GroupResult.Ok).group
        eventually("everyone joined") {
            listOf(bob, carol).all { it.groups.observeGroup(g).first()?.status == GroupStatus.Active }
        }
        // Bob is not an admin.
        assertTrue(bob.groups.rename(g, "Lake trip") is GroupResult.Ok)
        eventually("renamed everywhere") {
            listOf(alice, carol).all { it.groups.observeGroup(g).first()?.name == "Lake trip" }
        }
        // A later admin update (adding nobody new) keeps the newer name.
        assertTrue(alice.groups.setRole(g, UserId(bob.id), dev.whispr.domain.model.GroupRole.Admin) is GroupResult.Ok)
        eventually("role arrives") {
            bob.groups.observeGroup(g).first()!!.members.single { it.isMe }.role ==
                dev.whispr.domain.model.GroupRole.Admin
        }
        devices.forEach { assertEquals("Lake trip", it.groups.observeGroup(g).first()!!.name) }
    }

    @Test
    fun aLeftGroupStaysWithItsMessages() = runBlocking {
        connect(alice, bob)
        val g = (alice.groups.create("Book club", listOf(UserId(bob.id))) as GroupResult.Ok).group
        eventually("joined") { bob.groups.observeGroup(g).first()?.status == GroupStatus.Active }
        assertTrue(alice.repo.sendGroupText(g, "Chapter 3 tonight"))
        eventually("Bob reads it") { bob.texts(g.conversation).contains("Chapter 3 tonight") }
        assertTrue(bob.groups.leave(g) is GroupResult.Ok)
        val left = bob.repo.observeConversations().first().single { it.group?.id == g }
        assertEquals(GroupStatus.Left, left.group!!.status)
        assertTrue(bob.texts(g.conversation).contains("Chapter 3 tonight"))
    }

    /** Both have each other pinned and accepted, as after a request was accepted. */
    private fun connect(a: RelayDevice, b: RelayDevice) {
        a.knows(b)
        b.knows(a)
    }

    private fun eventually(what: String, cond: suspend () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 15_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(25)
        }
    }

    private companion object {
        val HANGUP = dev.whispr.domain.model.HangupReason.Hangup
    }
}
