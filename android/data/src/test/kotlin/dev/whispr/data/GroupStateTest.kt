package dev.whispr.data

import dev.whispr.data.db.ContactEntity
import dev.whispr.data.messaging.GroupManager
import dev.whispr.data.messaging.GroupState
import dev.whispr.data.messaging.MemberState
import dev.whispr.domain.model.GroupStatus
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.signal.libsignal.protocol.IdentityKeyPair

/** The group state rules on one device, fed hand-made updates. */
@RunWith(RobolectricTestRunner::class)
class GroupStateTest {
    private val device = CryptoDevice(FakeKeyServer())
    private val groups = GroupManager(device.db, device.crypto)
    private val me = device.userId
    private val adminX = "11111111-1111-4111-8111-111111111111"
    private val adminY = "22222222-2222-4222-8222-222222222222"
    private val carol = UUID.randomUUID().toString()
    private val keys = mutableMapOf<String, String>()
    private val group = UUID.randomUUID().toString()

    @After
    fun tearDown() = device.close()

    private fun key(id: String) =
        keys.getOrPut(id) { Base64.getEncoder().encodeToString(IdentityKeyPair.generate().publicKey.serialize()) }

    private fun member(id: String, role: String = "member", added: Int = 1) = MemberState(id, key(id), role, added)

    private fun state(
        rev: Int,
        members: List<MemberState>,
        removed: Map<String, Int> = emptyMap(),
        name: String = "G",
    ) = GroupState(group, rev, name, null, members, removed)

    private fun apply(sender: String, s: GroupState) = runBlocking {
        device.crypto.transaction { groups.applyUpdate(me, sender, s) }
    }

    private fun memberIds() = runBlocking {
        device.crypto.transaction { device.db.groupDao().members(group).map { it.userId }.toSet() }
    }

    private fun base() = listOf(member(adminX, "admin"), member(adminY, "admin"), member(me), member(carol))

    @Test
    fun aConcurrentUpdateCannotBringBackARemovedMember() {
        apply(adminX, state(1, base()))
        // X removes Carol at rev 2; Y renames at rev 2 still listing Carol. Y's ID is higher, so Y's wins the tie...
        apply(adminX, state(2, base().filter { it.id != carol }, removed = mapOf(carol to 2)))
        apply(adminY, state(2, base(), name = "Renamed by Y"))
        // ...but Carol stays removed.
        assertFalse(carol in memberIds())
        assertEquals(
            "Renamed by Y",
            runBlocking {
                device.crypto.transaction { device.db.groupDao().group(group)!!.name }
            },
        )
    }

    @Test
    fun theSameHoldsInTheOtherOrder() {
        apply(adminX, state(1, base()))
        apply(adminY, state(2, base(), name = "Renamed by Y"))
        val distribution =
            runBlocking { device.crypto.transaction { device.db.groupDao().group(group)!!.myDistributionId } }
        // X's removal loses the tie but its tombstone still applies, and we rotate.
        apply(adminX, state(2, base().filter { it.id != carol }, removed = mapOf(carol to 2)))
        assertFalse(carol in memberIds())
        assertNotEquals(
            distribution,
            runBlocking {
                device.crypto.transaction { device.db.groupDao().group(group)!!.myDistributionId }
            },
        )
        // A later explicit re-add (added above the tombstone) does bring her back.
        apply(
            adminX,
            state(
                3,
                base().filter {
                    it.id != carol
                } + member(carol, added = 3),
                removed = mapOf(carol to 2),
            ),
        )
        assertTrue(carol in memberIds())
    }

    @Test
    fun updatesFromNonAdminsOrOldRevisionsAreIgnored() {
        apply(adminX, state(2, base()))
        apply(carol, state(5, base().map { if (it.id == carol) it.copy(role = "admin") else it }, name = "Carol's"))
        apply(adminX, state(1, base(), name = "Old"))
        assertEquals("G", runBlocking { device.crypto.transaction { device.db.groupDao().group(group)!!.name } })
    }

    @Test
    fun aGroupThatDoesNotListUsOrHasNoAdminIsNotCreated() {
        apply(adminX, state(1, listOf(member(adminX, "admin"), member(carol))))
        apply(adminX, state(1, listOf(member(adminX), member(me))))
        assertNull(runBlocking { device.crypto.transaction { device.db.groupDao().group(group) } })
    }

    @Test
    fun malformedStatesAreRejected() {
        val bad = listOf(
            state(1, base(), name = ""),
            state(1, base(), name = "x".repeat(65)),
            state(1, base() + member(carol)),
            state(1, base().map { if (it.id == carol) it.copy(key = "AAAA") else it }),
            state(1, base().map { if (it.id == carol) it.copy(added = 7) else it }),
            GroupState(
                group,
                1,
                "G",
                Base64.getEncoder().encodeToString(ByteArray(GroupState.MAX_AVATAR_BYTES + 1)),
                base(),
            ),
            GroupState("not-a-uuid", 1, "G", null, base()),
        )
        bad.forEach { apply(adminX, it) }
        assertNull(runBlocking { device.crypto.transaction { device.db.groupDao().group(group) } })
    }

    @Test
    fun aMembersExistingPinWinsOverTheAdminsKey() {
        val pinned = IdentityKeyPair.generate().publicKey.serialize()
        runBlocking { device.db.contactDao().upsert(ContactEntity(carol, "Carol", pinned, 0)) }
        apply(adminX, state(1, base()))
        val contact = runBlocking { device.db.contactDao().get(carol)!! }
        assertTrue(contact.identityKey.contentEquals(pinned))
        assertFalse(contact.hidden)
        // A member we didn't know becomes a hidden contact (not in the chat list).
        assertTrue(runBlocking { device.db.contactDao().get(adminY)!!.hidden })
        assertEquals(
            GroupStatus.Active.name,
            runBlocking {
                device.crypto.transaction { device.db.groupDao().group(group)!!.status }
            },
        )
    }
}
