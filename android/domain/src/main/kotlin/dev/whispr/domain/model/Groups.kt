package dev.whispr.domain.model

/**
 * A group's random 128-bit ID (a UUID string). It doubles as the group's
 * conversation ID; the server sees it only as an opaque value.
 */
@JvmInline
value class GroupId(val value: String) {
    val conversation: ConversationId get() = ConversationId(value)
}

enum class GroupRole { Admin, Member }

/** Where the local user stands in a group. */
enum class GroupStatus {
    Active,

    /** An admin invited us; nothing is sent to us until we accept. */
    Invited,

    /** An admin removed us. The chat stays readable but is closed. */
    Removed,

    /** We left. */
    Left,
}

data class GroupMember(
    val userId: UserId,
    val displayName: String,
    val role: GroupRole,
    /** Invited but has not joined yet. */
    val invited: Boolean = false,
    val isMe: Boolean = false,
)

data class Group(
    val id: GroupId,
    val name: String,
    /** A small JPEG, end-to-end encrypted like the name. */
    val avatar: ByteArray?,
    val members: List<GroupMember>,
    val status: GroupStatus,
) {
    val me: GroupMember? get() = members.firstOrNull { it.isMe }
    val isAdmin: Boolean get() = status == GroupStatus.Active && me?.role == GroupRole.Admin

    /** Any member can rename the group or change its picture. */
    val canEditInfo: Boolean get() = status == GroupStatus.Active && me?.invited == false

    override fun equals(other: Any?) = other is Group &&
        id == other.id &&
        name == other.name &&
        avatar.contentEqualsNullable(other.avatar) &&
        members == other.members &&
        status == other.status

    override fun hashCode() = id.hashCode()
}

data class GroupSummary(val id: GroupId, val name: String, val avatar: ByteArray?, val status: GroupStatus) {
    override fun equals(other: Any?) = other is GroupSummary &&
        id == other.id &&
        name == other.name &&
        avatar.contentEqualsNullable(other.avatar) &&
        status == other.status

    override fun hashCode() = id.hashCode()
}

sealed interface GroupResult {
    data class Ok(val group: GroupId) : GroupResult

    /** Only admins can change the group. */
    data object NotAllowed : GroupResult

    /** Name empty or too long, too many members, or an unknown member. */
    data object Invalid : GroupResult

    /** A member's safety number changed; resolve it before adding them. */
    data object KeyChanged : GroupResult
}

internal fun ByteArray?.contentEqualsNullable(other: ByteArray?) =
    if (this == null) other == null else other != null && contentEquals(other)
