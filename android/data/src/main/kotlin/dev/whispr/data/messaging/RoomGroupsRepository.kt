package dev.whispr.data.messaging

import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.media.MediaPreparer
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.Group
import dev.whispr.domain.model.GroupId
import dev.whispr.domain.model.GroupMember
import dev.whispr.domain.model.GroupResult
import dev.whispr.domain.model.GroupRole
import dev.whispr.domain.model.GroupStatus
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.GroupsRepository
import dev.whispr.domain.repository.IdentityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class RoomGroupsRepository(
    private val db: WhisprDatabase,
    private val engine: MessagingEngine,
    private val accounts: AccountRepository,
    private val identity: IdentityRepository,
    private val preparer: MediaPreparer?,
) : GroupsRepository {
    private val groups get() = engine.groups

    override fun observeGroup(id: GroupId): Flow<Group?> = combine(
        db.groupQueries().observeGroup(id.value),
        db.groupQueries().observeMembers(id.value),
        db.contactDao().observeEveryone(),
        accounts.observeAccount(),
    ) { g, members, everyone, account ->
        g ?: return@combine null
        val me = account?.userId?.value
        val names = everyone.associate { it.userId to it.displayName }
        Group(
            id = id,
            name = g.name,
            avatar = g.avatar,
            members = members.map {
                GroupMember(
                    userId = UserId(it.userId),
                    displayName = if (it.userId == me) account?.displayName.orEmpty() else names[it.userId].orEmpty(),
                    role = GroupRole.valueOf(it.role),
                    invited = it.invited,
                    isMe = it.userId == me,
                )
            },
            status = GroupStatus.valueOf(g.status),
        )
    }

    private suspend fun self(): GroupManager.Self? {
        val account = accounts.getAccount() ?: return null
        val id = account.userId ?: return null
        return GroupManager.Self(id.value, account.displayName, identity.getOrCreatePublicKey())
    }

    override suspend fun create(name: String, members: List<UserId>, avatar: AvatarSource?): GroupResult {
        val self = self() ?: return GroupResult.NotAllowed
        val picture = avatar?.let { picture(it) ?: return GroupResult.Invalid }
        return groups.create(self, name, members.map { it.value }, picture)
    }

    override suspend fun rename(id: GroupId, name: String): GroupResult =
        self()?.let { groups.rename(it, id.value, name) } ?: GroupResult.NotAllowed

    override suspend fun setAvatar(id: GroupId, avatar: AvatarSource?): GroupResult {
        val self = self() ?: return GroupResult.NotAllowed
        val picture = avatar?.let { picture(it) ?: return GroupResult.Invalid }
        return groups.setAvatar(self, id.value, picture)
    }

    override suspend fun addMembers(id: GroupId, members: List<UserId>): GroupResult =
        self()?.let { groups.addMembers(it, id.value, members.map { m -> m.value }, invite = false) }
            ?: GroupResult.NotAllowed

    override suspend fun removeMember(id: GroupId, member: UserId): GroupResult =
        self()?.let { groups.removeMember(it, id.value, member.value) } ?: GroupResult.NotAllowed

    override suspend fun setRole(id: GroupId, member: UserId, role: GroupRole): GroupResult =
        self()?.let { groups.setRole(it, id.value, member.value, role) } ?: GroupResult.NotAllowed

    override suspend fun invite(id: GroupId, members: List<UserId>): GroupResult =
        self()?.let { groups.addMembers(it, id.value, members.map { m -> m.value }, invite = true) }
            ?: GroupResult.NotAllowed

    override suspend fun acceptInvite(id: GroupId): GroupResult =
        self()?.let { groups.acceptInvite(it, id.value) } ?: GroupResult.NotAllowed

    override suspend fun declineInvite(id: GroupId): GroupResult =
        self()?.let { groups.declineInvite(it, id.value) } ?: GroupResult.NotAllowed

    override suspend fun leave(id: GroupId): GroupResult =
        self()?.let { groups.leave(it, id.value) } ?: GroupResult.NotAllowed

    private suspend fun picture(source: AvatarSource): ByteArray? =
        preparer?.groupAvatar(source.uri, GroupState.MAX_AVATAR_BYTES)
}
