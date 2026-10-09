package dev.whispr.data.messaging

import dev.whispr.data.crypto.SessionCrypto
import dev.whispr.data.crypto.SignalStore
import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.GroupDao
import dev.whispr.data.db.GroupEntity
import dev.whispr.data.db.GroupKeyShareEntity
import dev.whispr.data.db.GroupMemberEntity
import dev.whispr.data.db.MessageEntity
import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.GroupResult
import dev.whispr.domain.model.GroupRole
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKey

/**
 * Group state and sender keys. The server knows nothing about groups: admins
 * send the full state ([Payload.GroupUpdate]) to every member over pairwise
 * libsignal sessions, and every device applies the same rules:
 *
 * - An update is accepted only from someone who is an admin in our current
 *   state (for a group we don't know yet: an admin in the new state that
 *   lists us), and only if it is newer: higher revision, then higher author ID.
 * - Removals stick: a member whose `added` revision is not above their
 *   removal tombstone is dropped from any state, so a concurrent update from
 *   another admin can't bring them back. Tombstones from every valid update
 *   are merged, even one that loses the revision tie.
 * - When anyone leaves or is removed, we start a new sender-key distribution,
 *   so nothing we send afterwards can be read with the old key.
 *
 * Functions without `suspend` run inside a crypto transaction (see
 * [SessionCrypto.transaction]); they may call the crypto functions that
 * require one.
 */
class GroupManager(
    private val db: WhisprDatabase,
    private val crypto: SessionCrypto,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao: GroupDao get() = db.groupDao()
    private val cdao get() = db.cryptoDao()
    private val json = Json

    // ---- Local operations ----

    /** Who we are, for operations started on this device. */
    class Self(val id: String, val name: String, val identityKey: ByteArray)

    suspend fun create(self: Self, name: String, memberIds: List<String>, avatar: ByteArray?): GroupResult {
        val cleanName = name.trim()
        val ids = memberIds.distinct().filter { it != self.id }
        if (!validName(cleanName) || ids.size + 1 > GroupState.MAX_MEMBERS || !validAvatar(avatar)) {
            return GroupResult.Invalid
        }
        return crypto.transaction {
            val contacts = ids.map { cdao.contact(it) ?: return@transaction GroupResult.Invalid }
            if (contacts.any { it.trust == TrustState.KeyChanged.name }) return@transaction GroupResult.KeyChanged
            if (contacts.any { it.identityKey.isEmpty() }) return@transaction GroupResult.Invalid
            val id = UUID.randomUUID().toString()
            val group = GroupEntity(
                groupId = id,
                name = cleanName,
                avatar = avatar,
                revision = 1,
                revisionAuthor = self.id,
                status = GroupStatus.Active.name,
                myDistributionId = UUID.randomUUID().toString(),
                createdAt = clock(),
            )
            val members = listOf(GroupMemberEntity(id, self.id, GroupRole.Admin.name, 1, self.identityKey)) +
                contacts.map { GroupMemberEntity(id, it.userId, GroupRole.Member.name, 1, it.identityKey) }
            dao.putGroup(group)
            dao.putMembers(members)
            system(id, "You created the group")
            broadcast(self.id, group, members, ids)
            GroupResult.Ok(dev.whispr.domain.model.GroupId(id))
        }
    }

    suspend fun rename(self: Self, id: String, name: String): GroupResult {
        val clean = name.trim()
        if (!validName(clean)) return GroupResult.Invalid
        return adminOp(self, id) { g, members, removed ->
            Change(g.copy(name = clean), members, removed, note = "You renamed the group to \"$clean\"")
        }
    }

    suspend fun setAvatar(self: Self, id: String, avatar: ByteArray?): GroupResult {
        if (!validAvatar(avatar)) return GroupResult.Invalid
        return adminOp(self, id) { g, members, removed ->
            Change(g.copy(avatar = avatar), members, removed, note = "You changed the group picture")
        }
    }

    suspend fun addMembers(self: Self, id: String, userIds: List<String>, invite: Boolean): GroupResult =
        adminOp(self, id) { g, members, removed ->
            val rev = g.revision + 1
            val existing = members.map { it.userId }.toSet()
            val added = userIds.distinct().filter { it != self.id && it !in existing }
            if (added.isEmpty()) return@adminOp null
            if (members.size + added.size > GroupState.MAX_MEMBERS) throw Refused(GroupResult.Invalid)
            val contacts = added.map { cdao.contact(it) ?: throw Refused(GroupResult.Invalid) }
            if (contacts.any { it.trust == TrustState.KeyChanged.name }) throw Refused(GroupResult.KeyChanged)
            if (contacts.any { it.identityKey.isEmpty() }) throw Refused(GroupResult.Invalid)
            val newMembers = members + contacts.map {
                GroupMemberEntity(id, it.userId, GroupRole.Member.name, rev, it.identityKey, invited = invite)
            }
            val names = contacts.joinToString { it.displayName }
            Change(g, newMembers, removed, note = if (invite) "You invited $names" else "You added $names")
        }

    suspend fun removeMember(self: Self, id: String, userId: String): GroupResult =
        adminOp(self, id) { g, members, removed ->
            if (userId == self.id || members.none { it.userId == userId }) return@adminOp null
            val rev = g.revision + 1
            Change(
                g,
                members.filter { it.userId != userId },
                removed + (userId to rev),
                notifyAlso = listOf(userId),
                note = "You removed ${nameOf(userId)}",
            )
        }

    suspend fun setRole(self: Self, id: String, userId: String, role: GroupRole): GroupResult =
        adminOp(self, id) { g, members, removed ->
            val target = members.firstOrNull { it.userId == userId && !it.invited } ?: return@adminOp null
            val admins = members.count { it.role == GroupRole.Admin.name && !it.invited }
            // There is always at least one admin.
            if (role == GroupRole.Member && target.role == GroupRole.Admin.name && admins == 1) {
                throw Refused(GroupResult.NotAllowed)
            }
            Change(g, members.map { if (it.userId == userId) it.copy(role = role.name) else it }, removed)
        }

    suspend fun acceptInvite(self: Self, id: String): GroupResult = crypto.transaction {
        val g = dao.group(id) ?: return@transaction GroupResult.Invalid
        if (g.status != GroupStatus.Invited.name) return@transaction GroupResult.NotAllowed
        val to = answerTo(g) ?: return@transaction GroupResult.NotAllowed
        enqueuePairwise(self.id, to, Payload.GroupJoin(id))
        system(id, "You accepted the invitation")
        GroupResult.Ok(dev.whispr.domain.model.GroupId(id))
    }

    suspend fun declineInvite(self: Self, id: String): GroupResult = crypto.transaction {
        val g = dao.group(id) ?: return@transaction GroupResult.Invalid
        if (g.status != GroupStatus.Invited.name) return@transaction GroupResult.NotAllowed
        answerTo(g)?.let { enqueuePairwise(self.id, it, Payload.GroupDecline(id)) }
        dao.putGroup(g.copy(status = GroupStatus.Left.name))
        GroupResult.Ok(dev.whispr.domain.model.GroupId(id))
    }

    /** Leaves; a sole admin first hands the role to the longest-standing member. */
    suspend fun leave(self: Self, id: String): GroupResult {
        val g = crypto.transaction { dao.group(id) } ?: return GroupResult.Invalid
        if (g.status != GroupStatus.Active.name) return GroupResult.NotAllowed
        val members = crypto.transaction { dao.members(id) }
        val me = members.firstOrNull { it.userId == self.id } ?: return GroupResult.NotAllowed
        val others = members.filter { it.userId != self.id && !it.invited }
        if (me.role == GroupRole.Admin.name) {
            val result = adminOp(self, id) { group, current, removed ->
                val rev = group.revision + 1
                var rest = current.filter { it.userId != self.id }
                if (rest.none { it.role == GroupRole.Admin.name && !it.invited }) {
                    val heir = rest.filter { !it.invited }.minWithOrNull(HEIR_ORDER)
                    rest = rest.map { if (it.userId == heir?.userId) it.copy(role = GroupRole.Admin.name) else it }
                }
                Change(group, rest, removed + (self.id to rev), note = "You left")
            }
            if (result !is GroupResult.Ok) return result
        } else {
            crypto.transaction {
                others.forEach { enqueuePairwise(self.id, it.userId, Payload.GroupLeave(id)) }
                system(id, "You left")
            }
        }
        crypto.transaction { dao.group(id)?.let { dao.putGroup(it.copy(status = GroupStatus.Left.name)) } }
        return GroupResult.Ok(dev.whispr.domain.model.GroupId(id))
    }

    private class Change(
        val group: GroupEntity,
        val members: List<GroupMemberEntity>,
        val removed: Map<String, Int>,
        val notifyAlso: List<String> = emptyList(),
        val note: String? = null,
    )

    private class Refused(val result: GroupResult) : Exception()

    /** Runs an admin-only state change as a new revision and sends it to everyone affected. */
    private suspend fun adminOp(
        self: Self,
        id: String,
        change: (GroupEntity, List<GroupMemberEntity>, Map<String, Int>) -> Change?,
    ): GroupResult = try {
        crypto.transaction {
            val g = dao.group(id) ?: return@transaction GroupResult.Invalid
            val members = dao.members(id)
            val me = members.firstOrNull { it.userId == self.id }
            if (g.status != GroupStatus.Active.name || me?.role != GroupRole.Admin.name || me.invited) {
                return@transaction GroupResult.NotAllowed
            }
            val c = change(g, members, removedOf(g)) ?: return@transaction GroupResult.Ok(
                dev.whispr.domain.model.GroupId(id),
            )
            val rev = g.revision + 1
            val next = c.group.copy(revision = rev, revisionAuthor = self.id, removed = encodeRemoved(c.removed))
            val members2 = filterRemoved(c.members, c.removed)
            commit(self.id, g, members, next, members2)
            c.note?.let { system(id, it) }
            broadcast(self.id, dao.group(id)!!, members2, members2.map { it.userId } + c.notifyAlso)
            GroupResult.Ok(dev.whispr.domain.model.GroupId(id))
        }
    } catch (r: Refused) {
        r.result
    }

    // ---- Sending group content ----

    /**
     * Queues [payload] (padded and encrypted once, at the head of the group's
     * lane) for the group's current members, preceded by our sender key for
     * anyone who doesn't have it yet. Returns the recipients, empty if we are
     * alone, or null if we can't send to this group.
     */
    fun enqueueGroup(
        me: String,
        groupId: String,
        messageId: String,
        payload: ByteArray,
        clientTs: Long,
    ): List<String>? {
        val g = dao.group(groupId) ?: return null
        if (g.status != GroupStatus.Active.name) return null
        val recipients = activeMembers(groupId).filter { it != me }
        if (recipients.isEmpty()) return recipients
        ensureShares(me, g, recipients)
        cdao.enqueue(
            OutboxEntity(
                messageId = messageId,
                conversationId = groupId,
                recipientId = groupId,
                payload = payload,
                clientTs = clientTs,
                groupId = groupId,
                recipients = recipients.joinToString(","),
            ),
        )
        return recipients
    }

    /** Queues our current sender key to every recipient who doesn't have it yet. */
    fun ensureShares(me: String, g: GroupEntity, recipients: List<String>) {
        val distribution = UUID.fromString(g.myDistributionId)
        val missing = recipients - dao.shares(g.groupId, g.myDistributionId).toSet()
        if (missing.isEmpty()) return
        val skdm = Base64.getEncoder().encodeToString(crypto.distributionMessage(distribution))
        for (member in missing) {
            enqueuePairwise(me, member, Payload.SenderKey(g.groupId, g.myDistributionId, skdm))
            dao.putShare(GroupKeyShareEntity(g.groupId, g.myDistributionId, member))
        }
    }

    fun activeMembers(groupId: String): List<String> = dao.members(groupId).filter { !it.invited }.map { it.userId }

    // ---- Incoming (pairwise, inside the decrypt transaction) ----

    /** Returns members whose held group messages may now be decryptable. */
    fun applyUpdate(me: String, sender: String, s: GroupState): List<String> {
        if (!valid(s)) return emptyList()
        val local = dao.group(s.id)
        val incomingRemoved = s.removed
        if (local == null) {
            val effective = filterState(s.members, incomingRemoved)
            val mine = effective.firstOrNull { it.id == me } ?: return emptyList()
            if (effective.none { it.id == sender && it.role == ADMIN && !it.invited }) return emptyList()
            val status = if (mine.invited) GroupStatus.Invited else GroupStatus.Active
            val group = GroupEntity(
                groupId = s.id,
                name = s.name.trim(),
                avatar = s.avatar?.let(::decode),
                revision = s.rev,
                revisionAuthor = sender,
                status = status.name,
                myDistributionId = UUID.randomUUID().toString(),
                invitedBy = sender.takeIf { mine.invited },
                removed = encodeRemoved(incomingRemoved),
                createdAt = clock(),
            )
            dao.putGroup(group)
            val members = effective.map { it.toEntity(s.id) }
            dao.putMembers(members)
            pinMembers(me, effective)
            system(
                s.id,
                if (mine.invited) "${nameOf(sender)} invited you" else "${nameOf(sender)} added you",
            )
            return members.map { it.userId }.filter { it != me }
        }
        if (local.status == GroupStatus.Left.name) return emptyList()
        val members = dao.members(s.id)
        if (members.none { it.userId == sender && it.role == GroupRole.Admin.name && !it.invited }) return emptyList()
        val merged = mergeRemoved(removedOf(local), incomingRemoved)
        val newer = s.rev > local.revision || (s.rev == local.revision && sender > local.revisionAuthor)
        if (!newer) {
            // A losing concurrent update still carries valid removals.
            if (merged != removedOf(local)) {
                val kept = filterRemoved(members, merged)
                commit(me, local, members, local.copy(removed = encodeRemoved(merged)), kept)
                ensureAdmin(me, s.id)
            }
            return emptyList()
        }
        val effective = filterState(s.members, merged)
        pinMembers(me, effective)
        val next = local.copy(
            name = s.name.trim(),
            avatar = s.avatar?.let(::decode),
            revision = s.rev,
            revisionAuthor = sender,
            removed = encodeRemoved(merged),
        )
        if (next.name != local.name) system(s.id, "${nameOf(sender)} renamed the group to \"${next.name}\"")
        if (!next.avatar.contentEqualsNullable(
                local.avatar,
            )
        ) {
            system(s.id, "${nameOf(sender)} changed the group picture")
        }
        commit(me, local, members, next, effective.map { it.toEntity(s.id) }, sender)
        ensureAdmin(me, s.id)
        return effective.map { it.id }.filter { it != me }
    }

    fun applyJoin(me: String, sender: String, groupId: String) {
        val g = dao.group(groupId) ?: return
        val members = dao.members(groupId)
        if (!isActiveAdmin(me, g, members)) return
        if (members.none { it.userId == sender && it.invited }) return
        val next = g.copy(revision = g.revision + 1, revisionAuthor = me)
        val updated = members.map { if (it.userId == sender) it.copy(invited = false) else it }
        commit(me, g, members, next, updated)
        broadcast(me, dao.group(groupId)!!, updated, updated.map { it.userId })
    }

    fun applyDecline(me: String, sender: String, groupId: String) {
        val g = dao.group(groupId) ?: return
        val members = dao.members(groupId)
        if (!isActiveAdmin(me, g, members)) return
        if (members.none { it.userId == sender && it.invited }) return
        val rev = g.revision + 1
        val removed = removedOf(g) + (sender to rev)
        val next = g.copy(revision = rev, revisionAuthor = me, removed = encodeRemoved(removed))
        val updated = filterRemoved(members, removed)
        commit(me, g, members, next, updated)
        broadcast(me, dao.group(groupId)!!, updated, updated.map { it.userId })
    }

    /** A member left. Everyone drops them and rotates; one admin publishes the next revision. */
    fun applyLeave(me: String, sender: String, groupId: String) {
        val g = dao.group(groupId) ?: return
        if (g.status == GroupStatus.Left.name) return
        val members = dao.members(groupId)
        if (members.none { it.userId == sender && !it.invited }) return
        val removed = removedOf(g) + (sender to g.revision)
        val kept = filterRemoved(members, removed)
        commit(me, g, members, g.copy(removed = encodeRemoved(removed)), kept, leaver = sender)
        val admins = kept.filter { it.role == GroupRole.Admin.name && !it.invited }.map { it.userId }.sorted()
        if (g.status == GroupStatus.Active.name && admins.firstOrNull() == me) {
            val current = dao.group(groupId)!!
            val rev = current.revision + 1
            val next = current.copy(
                revision = rev,
                revisionAuthor = me,
                removed = encodeRemoved(removedOf(current) + (sender to rev)),
            )
            dao.putGroup(next)
            broadcast(me, next, kept, kept.map { it.userId })
        }
        ensureAdmin(me, groupId)
    }

    /**
     * Two admins left at about the same time, each still counting on the
     * other: nobody is admin any more. Every member promotes the same heir
     * (earliest added, then lowest user ID) locally, and the heir publishes
     * that as a new revision, which the others accept because they already
     * see the heir as admin.
     */
    private fun ensureAdmin(me: String, groupId: String) {
        val g = dao.group(groupId) ?: return
        if (g.status != GroupStatus.Active.name) return
        val members = dao.members(groupId)
        val active = members.filter { !it.invited }
        if (active.isEmpty() || active.any { it.role == GroupRole.Admin.name }) return
        val heir = active.minWith(HEIR_ORDER)
        val promoted = members.map { if (it.userId == heir.userId) it.copy(role = GroupRole.Admin.name) else it }
        dao.clearMembers(groupId)
        dao.putMembers(promoted)
        if (heir.userId != me) return
        val next = g.copy(revision = g.revision + 1, revisionAuthor = me)
        dao.putGroup(next)
        broadcast(me, next, promoted, promoted.map { it.userId })
        system(groupId, "You are now an admin")
    }

    /**
     * Stores a member's sender key. Accepted from anyone (it only lets us
     * decrypt their messages); whether those messages are shown is decided
     * by membership when they arrive.
     */
    fun applySenderKey(sender: String, p: Payload.SenderKey): Boolean {
        val bytes = runCatching { Base64.getDecoder().decode(p.skdm) }.getOrNull() ?: return false
        val distribution = crypto.processDistribution(sender, bytes) ?: return false
        if (distribution.toString() != p.d) return false
        dao.putDistribution(dev.whispr.data.db.GroupDistributionEntity(sender, p.d, p.g))
        return true
    }

    /** Group content may be shown only from a current member of a group we're active in. */
    fun accepts(me: String, groupId: String, sender: String): Boolean {
        val g = dao.group(groupId) ?: return false
        if (g.status != GroupStatus.Active.name) return false
        val members = dao.members(groupId)
        return members.any { it.userId == sender && !it.invited } && members.any { it.userId == me && !it.invited }
    }

    // ---- Shared ----

    /**
     * Writes [next] and [newMembers]. If anyone who held our key is gone, we
     * start a new distribution so they can't read what we send next.
     */
    private fun commit(
        me: String,
        old: GroupEntity,
        oldMembers: List<GroupMemberEntity>,
        next: GroupEntity,
        newMembers: List<GroupMemberEntity>,
        author: String? = null,
        leaver: String? = null,
    ) {
        val before = oldMembers.filter { !it.invited }.map { it.userId }.toSet()
        val after = newMembers.filter { !it.invited }.map { it.userId }.toSet()
        val departed = before - after - me
        val stillMe = newMembers.firstOrNull { it.userId == me }
        val status = when {
            old.status == GroupStatus.Left.name -> GroupStatus.Left
            stillMe == null -> GroupStatus.Removed
            stillMe.invited -> GroupStatus.Invited
            else -> GroupStatus.Active
        }
        var group = next.copy(status = status.name)
        if (departed.isNotEmpty() || (me in before && me !in after)) {
            group = group.copy(myDistributionId = UUID.randomUUID().toString())
            dao.clearShares(old.groupId)
        }
        dao.putGroup(group)
        dao.clearMembers(old.groupId)
        dao.putMembers(newMembers)
        if (author != null) {
            val joined = after - before - me
            if (joined.isNotEmpty() && old.status != GroupStatus.Invited.name) {
                system(old.groupId, "${nameOf(author)} added ${joined.joinToString { nameOf(it) }}")
            }
            departed.forEach { system(old.groupId, "${nameOf(it)} is no longer in the group") }
        } else if (leaver != null && leaver in departed) {
            system(old.groupId, "${nameOf(leaver)} left")
        }
        // Our own operations write their own notes; these are for what others did.
        if (author != null) {
            when {
                status == GroupStatus.Removed && old.status != GroupStatus.Removed.name ->
                    system(old.groupId, "You were removed from the group")
                status == GroupStatus.Active && old.status == GroupStatus.Invited.name ->
                    system(old.groupId, "You joined the group")
            }
        }
    }

    private fun broadcast(me: String, g: GroupEntity, members: List<GroupMemberEntity>, to: List<String>) {
        val state = GroupState(
            id = g.groupId,
            rev = g.revision,
            name = g.name,
            avatar = g.avatar?.let { Base64.getEncoder().encodeToString(it) },
            members = members.map {
                MemberState(
                    id = it.userId,
                    key = Base64.getEncoder().encodeToString(it.identityKey),
                    role = if (it.role == GroupRole.Admin.name) ADMIN else MEMBER,
                    added = it.addedAt,
                    invited = it.invited,
                    name = (if (it.userId == me) "" else nameOf(it.userId)).take(GroupState.MAX_NAME),
                )
            },
            removed = removedOf(g),
        )
        to.distinct().filter { it != me }.forEach { enqueuePairwise(me, it, Payload.GroupUpdate(state)) }
    }

    private fun enqueuePairwise(me: String, peer: String, payload: Payload) {
        cdao.enqueue(
            OutboxEntity(
                messageId = UUID.randomUUID().toString(),
                conversationId = ConversationId.direct(UserId(me), UserId(peer)).value,
                recipientId = peer,
                payload = PayloadCodec.encode(payload),
                clientTs = clock(),
            ),
        )
    }

    /**
     * Members we don't know get a hidden contact pinned to the key the admin
     * gave. An existing contact's pin always wins; a different key in the
     * group state is never adopted (the usual key-change checks then apply).
     */
    private fun pinMembers(me: String, members: List<MemberState>) {
        for (m in members) {
            if (m.id == me) continue
            val key = decode(m.key)
            val existing = cdao.contact(m.id)
            when {
                existing == null -> cdao.putContact(
                    ContactEntity(
                        m.id,
                        m.name.trim().take(GroupState.MAX_NAME).ifBlank { SignalStore.UNKNOWN_CONTACT },
                        key,
                        clock(),
                        hidden = true,
                    ),
                )
                // Someone who wrote to us first (a message request) but turns
                // out to be a fellow group member, and never sent us 1:1 text.
                existing.isRequest &&
                    existing.displayName == SignalStore.UNKNOWN_CONTACT &&
                    !dao.hasDirectMessages(m.id) -> cdao.putContact(
                    existing.copy(
                        displayName = m.name.trim().take(GroupState.MAX_NAME)
                            .ifBlank { SignalStore.UNKNOWN_CONTACT },
                        isRequest = false,
                        hidden = true,
                    ),
                )
                existing.identityKey.isEmpty() -> cdao.putContact(existing.copy(identityKey = key))
            }
        }
    }

    private fun isActiveAdmin(me: String, g: GroupEntity, members: List<GroupMemberEntity>) =
        g.status == GroupStatus.Active.name &&
            members.any { it.userId == me && it.role == GroupRole.Admin.name && !it.invited }

    private fun answerTo(g: GroupEntity): String? {
        val admins = dao.members(g.groupId).filter { it.role == GroupRole.Admin.name && !it.invited }.map { it.userId }
        return g.invitedBy?.takeIf { it in admins } ?: admins.firstOrNull()
    }

    fun system(groupId: String, text: String) {
        cdao.insertMessage(
            MessageEntity(
                messageId = "sys-" + UUID.randomUUID(),
                conversationId = groupId,
                peerId = groupId,
                outgoing = false,
                body = text,
                timestamp = clock(),
                status = null,
                readByMe = true,
                system = true,
            ),
        )
    }

    private fun nameOf(userId: String): String =
        cdao.contact(userId)?.displayName?.takeIf { it != SignalStore.UNKNOWN_CONTACT } ?: "Someone"

    private fun removedOf(g: GroupEntity): Map<String, Int> =
        runCatching { json.decodeFromString<Map<String, Int>>(g.removed) }.getOrDefault(emptyMap())

    private fun encodeRemoved(m: Map<String, Int>) = json.encodeToString(m)

    private fun mergeRemoved(a: Map<String, Int>, b: Map<String, Int>) =
        (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0, b[it] ?: 0) }

    private fun filterRemoved(members: List<GroupMemberEntity>, removed: Map<String, Int>) =
        members.filter { (removed[it.userId] ?: 0) < it.addedAt }

    private fun filterState(members: List<MemberState>, removed: Map<String, Int>) =
        members.filter { (removed[it.id] ?: 0) < it.added }

    private fun MemberState.toEntity(groupId: String) = GroupMemberEntity(
        groupId,
        id,
        if (role == ADMIN) GroupRole.Admin.name else GroupRole.Member.name,
        added,
        decode(key),
        invited,
    )

    private fun valid(s: GroupState): Boolean = runCatching {
        UUID.fromString(s.id)
        s.rev > 0 &&
            validName(s.name.trim()) &&
            s.members.size in 1..GroupState.MAX_MEMBERS &&
            s.members.map { it.id }.toSet().size == s.members.size &&
            s.members.all { m ->
                UUID.fromString(m.id)
                IdentityKey(decode(m.key))
                m.role in setOf(ADMIN, MEMBER) && m.added in 1..s.rev && m.name.length <= GroupState.MAX_NAME
            } &&
            s.members.any { it.role == ADMIN && !it.invited } &&
            validAvatar(s.avatar?.let(::decode)) &&
            s.removed.size <= MAX_TOMBSTONES
    }.getOrDefault(false)

    private fun validName(name: String) = name.isNotBlank() && name.length <= GroupState.MAX_NAME

    private fun validAvatar(avatar: ByteArray?) = avatar == null || avatar.size <= GroupState.MAX_AVATAR_BYTES

    private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?) =
        if (this == null) other == null else other != null && contentEquals(other)

    companion object {
        const val ADMIN = "admin"
        const val MEMBER = "member"
        private const val MAX_TOMBSTONES = 1_000
    }
}

/** Who inherits the admin role: the longest-standing member, ties broken by user ID. */
private val HEIR_ORDER = compareBy<GroupMemberEntity>({ it.addedAt }, { it.userId })
