package dev.whispr.data

import dev.whispr.data.crypto.GroupDecryptResult
import dev.whispr.data.messaging.IncomingEnvelope
import dev.whispr.data.messaging.IncomingPipeline
import dev.whispr.domain.model.GroupId
import dev.whispr.domain.model.GroupResult
import dev.whispr.domain.model.GroupRole
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.UserId
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Three real devices (own databases, libsignal identities, engines) sharing
 * groups through a relay that, like the real server, knows nothing about
 * groups. Alice knows Bob and Carol; Bob and Carol only know each other
 * through the group.
 */
@RunWith(RobolectricTestRunner::class)
class GroupMessagingTest {
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
        alice.knows(carol)
        bob.knows(alice)
        carol.knows(alice)
        listOf(alice, bob, carol).forEach { it.start() }
        // Everyone has published keys before anyone needs a session.
        eventually("keys published") {
            listOf(alice, bob, carol).all { relay.keyServer.oneTimeLeft(it.id) > 0 }
        }
    }

    @After
    fun tearDown() {
        listOf(alice, bob, carol).forEach { it.close() }
        server.close()
    }

    private suspend fun createGroup(name: String = "Hikers"): GroupId {
        val r = alice.groups.create(name, listOf(UserId(bob.id), UserId(carol.id)))
        val id = (r as GroupResult.Ok).group
        eventually("everyone is in the group") {
            listOf(bob, carol).all { d ->
                d.groups.observeGroup(id).first()?.let { g -> g.status == GroupStatus.Active && g.members.size == 3 } ==
                    true
            }
        }
        return id
    }

    private suspend fun distribution(d: RelayDevice, g: GroupId) =
        d.engine.transaction { d.db.groupDao().group(g.value)!!.myDistributionId }

    @Test
    fun threeUsersShareAGroupAndARemovedMemberCannotReadLaterMessages() = runBlocking {
        val g = createGroup()
        val conv = g.conversation

        assertTrue(alice.repo.sendGroupText(g, "hello all"))
        eventually("bob and carol read alice") {
            bob.texts(conv) == listOf("hello all") && carol.texts(conv) == listOf("hello all")
        }
        // Bob and Carol were never contacts: the group gave them each other's keys.
        assertTrue(bob.repo.sendGroupText(g, "hi from bob"))
        eventually("alice and carol read bob") {
            alice.texts(conv).contains("hi from bob") && carol.texts(conv).contains("hi from bob")
        }
        // Encrypted once, fanned out by the server: one send_multi per message.
        val aliceSend = relay.sent.single {
            it.s("type") == "send_multi" &&
                it.s("conversation_id") == g.value &&
                it.getValue("recipient_ids").jsonArray.size == 2 &&
                relay.history.any { h -> h.messageId == it.s("id") && h.sender == alice.id }
        }
        assertEquals(
            setOf(bob.id, carol.id),
            aliceSend.getValue("recipient_ids").jsonArray.map {
                it.jsonPrimitive.content
            }.toSet(),
        )
        eventually("alice sees delivered once both have it") {
            alice.messages(conv).first { it.text == "hello all" }.status == MessageStatus.Delivered
        }

        val aliceKeyBefore = distribution(alice, g)
        val bobKeyBefore = distribution(bob, g)
        assertTrue(alice.groups.removeMember(g, UserId(carol.id)) is GroupResult.Ok)
        eventually("bob and carol see the removal") {
            bob.groups.observeGroup(g).first()!!.members.none { it.userId.value == carol.id } &&
                carol.groups.observeGroup(g).first()!!.status == GroupStatus.Removed
        }
        // Everyone left in the group rotated their sender key.
        assertNotEquals(aliceKeyBefore, distribution(alice, g))
        eventually("bob rotated") { distribution(bob, g) != bobKeyBefore }

        val carolBefore = relay.payloadsFor(carol.id).size
        assertTrue(alice.repo.sendGroupText(g, "after removal from alice"))
        assertTrue(bob.repo.sendGroupText(g, "after removal from bob"))
        eventually("alice and bob read each other") {
            alice.texts(conv).contains("after removal from bob") && bob.texts(conv).contains("after removal from alice")
        }
        // The server never queued anything new for Carol...
        val later = relay.sent.filter { it.s("type") == "send_multi" }.takeLast(2)
        later.forEach { f ->
            assertFalse(
                carol.id in f.getValue("recipient_ids").jsonArray.map { it.jsonPrimitive.content },
            )
        }
        assertEquals(carolBefore, relay.payloadsFor(carol.id).size)
        // ...and even handed the ciphertext (a compromised server), Carol can't read it.
        for (f in later) {
            val payload = Base64.getDecoder().decode(f.s("payload"))
            val sender = relay.history.first { it.messageId == f.s("id") }.sender
            val r = carol.crypto.crypto.decryptGroup(sender, payload) { String(it) }
            assertEquals("removed member decrypted a later message", GroupDecryptResult.NoSenderKey, r)
            carol.engine.pipeline.process(
                carol.id,
                IncomingEnvelope(999, f.s("id"), sender, IncomingPipeline.KIND_ENVELOPE, null, 0, payload),
            )
        }
        assertEquals(listOf("hello all", "hi from bob"), carol.texts(conv))
    }

    @Test
    fun leavingRotatesKeysAndTheLeaverReadsNothingAfter() = runBlocking {
        val g = createGroup()
        val before = distribution(alice, g)
        assertTrue(carol.groups.leave(g) is GroupResult.Ok)
        eventually("alice and bob drop carol") {
            listOf(alice, bob).all { d ->
                d.groups.observeGroup(g).first()!!.members.none { it.userId.value == carol.id }
            }
        }
        assertNotEquals(before, distribution(alice, g))
        alice.repo.sendGroupText(g, "carol has gone")
        eventually("bob reads it") { bob.texts(g.conversation).contains("carol has gone") }
        assertFalse(carol.texts(g.conversation).contains("carol has gone"))
        assertEquals(GroupStatus.Left, carol.groups.observeGroup(g).first()!!.status)
    }

    @Test
    fun renameAndPictureReachEveryoneAndNonAdminsCannotChangeTheGroup() = runBlocking {
        val g = createGroup()
        alice.preparer.files["content://pic"] =
            dev.whispr.data.media.PreparedMedia(ByteArray(500) { 7 }, "image/jpeg", null)
        assertTrue(alice.groups.rename(g, "Summit crew") is GroupResult.Ok)
        assertTrue(alice.groups.setAvatar(g, dev.whispr.domain.model.AvatarSource("content://pic")) is GroupResult.Ok)
        eventually("bob and carol see the new name and picture") {
            listOf(bob, carol).all { d ->
                d.groups.observeGroup(g).first()!!.let { it.name == "Summit crew" && it.avatar?.size == 500 }
            }
        }
        // Bob is a member, not an admin: refused locally...
        assertEquals(GroupResult.NotAllowed, bob.groups.rename(g, "Bob's group"))
        // ...and a hand-crafted update from him is ignored by everyone else.
        val forgery = UUID.randomUUID().toString()
        bob.engine.transaction {
            val state = dev.whispr.data.messaging.GroupState(
                id = g.value,
                rev = 99,
                name = "Hijacked",
                members = bob.db.groupDao().members(g.value).map {
                    dev.whispr.data.messaging.MemberState(
                        it.userId,
                        Base64.getEncoder().encodeToString(it.identityKey),
                        if (it.userId == bob.id) "admin" else "member",
                        it.addedAt,
                    )
                },
            )
            bob.db.cryptoDao().enqueue(
                dev.whispr.data.db.OutboxEntity(
                    messageId = forgery,
                    conversationId = "x",
                    recipientId = carol.id,
                    payload = dev.whispr.data.messaging.PayloadCodec.encode(
                        dev.whispr.data.messaging.Payload.GroupUpdate(state),
                    ),
                    clientTs = 0,
                ),
            )
        }
        // Acknowledged means decrypted and processed (store-before-ack).
        eventually("carol processed bob's forgery") { "${carol.id}/$forgery" in relay.acked }
        assertEquals("Summit crew", carol.groups.observeGroup(g).first()!!.name)
    }

    @Test
    fun inviteeJoinsOnlyAfterAccepting() = runBlocking {
        val dave = RelayDevice("Dave", relay, server.url("/").toString())
        try {
            alice.knows(dave)
            dave.knows(alice)
            dave.start()
            eventually("dave keys") { relay.keyServer.oneTimeLeft(dave.id) > 0 }
            val g = createGroup()
            assertTrue(alice.groups.invite(g, listOf(UserId(dave.id))) is GroupResult.Ok)
            eventually("dave is invited") { dave.groups.observeGroup(g).first()?.status == GroupStatus.Invited }
            alice.repo.sendGroupText(g, "before dave accepted")
            eventually("bob reads it") { bob.texts(g.conversation).contains("before dave accepted") }
            assertTrue(dave.texts(g.conversation).isEmpty())

            assertTrue(dave.groups.acceptInvite(g) is GroupResult.Ok)
            eventually("dave is active everywhere") {
                dave.groups.observeGroup(g).first()?.status == GroupStatus.Active &&
                    listOf(alice, bob, carol).all { d ->
                        d.groups.observeGroup(g).first()!!.members.any { it.userId.value == dave.id && !it.invited }
                    }
            }
            carol.repo.sendGroupText(g, "welcome dave")
            eventually("dave reads carol") { dave.texts(g.conversation).contains("welcome dave") }
        } finally {
            dave.close()
        }
    }

    @Test
    fun declinedInviteDropsTheInvitee() = runBlocking {
        val dave = RelayDevice("Dave", relay, server.url("/").toString())
        try {
            alice.knows(dave)
            dave.knows(alice)
            dave.start()
            eventually("dave keys") { relay.keyServer.oneTimeLeft(dave.id) > 0 }
            val g = createGroup()
            alice.groups.invite(g, listOf(UserId(dave.id)))
            eventually("dave is invited") { dave.groups.observeGroup(g).first()?.status == GroupStatus.Invited }
            assertTrue(dave.groups.declineInvite(g) is GroupResult.Ok)
            eventually("alice drops the invitee") {
                alice.groups.observeGroup(g).first()!!.members.none { it.userId.value == dave.id }
            }
        } finally {
            dave.close()
        }
    }

    @Test
    fun soleAdminLeavingHandsTheRoleOnAndPromotedAdminsCanManage() = runBlocking {
        val g = createGroup()
        assertTrue(alice.groups.setRole(g, UserId(bob.id), GroupRole.Admin) is GroupResult.Ok)
        eventually("bob is admin") { bob.groups.observeGroup(g).first()!!.isAdmin }
        assertTrue(bob.groups.rename(g, "Bob renamed it") is GroupResult.Ok)
        eventually("carol sees bob's rename") { carol.groups.observeGroup(g).first()!!.name == "Bob renamed it" }

        // Bob leaves; Alice (still admin) stays. Then Alice leaves: Carol inherits.
        assertTrue(bob.groups.leave(g) is GroupResult.Ok)
        eventually("bob gone") { carol.groups.observeGroup(g).first()!!.members.none { it.userId.value == bob.id } }
        assertTrue(alice.groups.leave(g) is GroupResult.Ok)
        eventually("carol is now admin") { carol.groups.observeGroup(g).first()!!.isAdmin }
    }

    @Test
    fun groupMessageArrivingBeforeTheSendersKeyIsHeldThenShown() = runBlocking {
        val g = createGroup()
        // Hold back Bob's pairwise envelopes to Carol (his sender key), let the group message through.
        relay.defer =
            {
                it.sender == bob.id &&
                    it.recipient == carol.id &&
                    !it.frame.contains("\"conversation_id\":\"${g.value}\"")
            }
        bob.repo.sendGroupText(g, "out of order")
        eventually("alice reads it") { alice.texts(g.conversation).contains("out of order") }
        Thread.sleep(500)
        assertFalse(carol.texts(g.conversation).contains("out of order"))
        relay.release()
        eventually("carol reads it once the key arrives") { carol.texts(g.conversation).contains("out of order") }
    }

    @Test
    fun reactionsWorkInGroupsAndOneToOne() = runBlocking {
        val g = createGroup()
        alice.repo.sendGroupText(g, "react to me")
        eventually("bob has it") { bob.texts(g.conversation).contains("react to me") }
        val id = bob.messages(g.conversation).first { it.text == "react to me" }.id
        bob.repo.react(g.conversation, id, "👍")
        carol.repo.react(g.conversation, carol.messages(g.conversation).first { it.text == "react to me" }.id, "👍")
        eventually("alice sees two thumbs") {
            alice.messages(g.conversation).first { it.text == "react to me" }.reactions.singleOrNull()?.count == 2
        }
        bob.repo.react(g.conversation, id, null)
        eventually("bob's reaction removed everywhere") {
            alice.messages(g.conversation).first { it.text == "react to me" }.reactions.single().count == 1
        }

        alice.repo.sendText(UserId(bob.id), "direct")
        val direct = bob.direct(alice)
        eventually("bob has the direct message") { bob.texts(direct).contains("direct") }
        bob.repo.react(direct, bob.messages(direct).first { it.text == "direct" }.id, "❤️")
        eventually("alice sees the heart") {
            alice.messages(alice.direct(bob)).first { it.text == "direct" }.reactions.singleOrNull()?.emoji == "❤️"
        }
    }

    private fun eventually(what: String, cond: suspend () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 20_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(25)
        }
    }
}
