package dev.whispr.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Groups, sender keys, media and reactions. Group state is replicated by the
// members' devices (the server has no groups); see
// docs/designs/groups-and-media.md.

/**
 * One group as this device knows it. [revision] and [revisionAuthor] order
 * concurrent updates (higher revision wins, then higher author ID).
 * [myDistributionId] is our current sender-key distribution; it changes
 * whenever someone is removed or leaves.
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val avatar: ByteArray?,
    val revision: Int,
    val revisionAuthor: String,
    /** GroupStatus name. */
    val status: String,
    val myDistributionId: String,
    /** The admin who invited us (we send our answer to them). */
    val invitedBy: String? = null,
    /** JSON map userId -> revision at which they were removed (removal tombstones). */
    @ColumnInfo(defaultValue = "{}") val removed: String = "{}",
    val createdAt: Long,
)

@Entity(tableName = "group_members", primaryKeys = ["groupId", "userId"])
data class GroupMemberEntity(
    val groupId: String,
    val userId: String,
    /** GroupRole name. */
    val role: String,
    /** Revision at which they were (last) added. */
    val addedAt: Int,
    val identityKey: ByteArray,
    val invited: Boolean = false,
)

/** libsignal sender-key state, ours and other members'. */
@Entity(tableName = "sender_keys", primaryKeys = ["senderId", "distributionId"])
data class SenderKeyEntity(val senderId: String, val distributionId: String, val record: ByteArray)

/** Which members have our key for [distributionId] (queued to them). */
@Entity(tableName = "group_key_shares", primaryKeys = ["groupId", "distributionId", "memberId"])
data class GroupKeyShareEntity(val groupId: String, val distributionId: String, val memberId: String)

/** A sender's distribution ID belongs to exactly this group. */
@Entity(tableName = "group_distributions", primaryKeys = ["senderId", "distributionId"])
data class GroupDistributionEntity(val senderId: String, val distributionId: String, val groupId: String)

/** Group ciphertext that arrived before its sender's key; retried when the key arrives. */
@Entity(tableName = "held_group_envelopes", primaryKeys = ["senderId", "transportId"])
data class HeldGroupEnvelopeEntity(
    val senderId: String,
    val transportId: String,
    val ciphertext: ByteArray,
    val receivedAt: Long,
    val seq: Long,
)

/** Per-recipient delivery of our group messages. */
@Entity(tableName = "group_sends", primaryKeys = ["messageId", "memberId"])
data class GroupSendEntity(val messageId: String, val memberId: String, val delivered: Boolean = false)

/**
 * An attachment of the message row [messageRow] (its localOrder). The blob
 * on disk ([blobPath]) is always the encrypted one; [key] lives only here,
 * in the SQLCipher database.
 */
@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey val messageRow: Long,
    /** The server's blob ID; null until uploaded. */
    val remoteId: String?,
    val key: ByteArray,
    val digest: ByteArray,
    val size: Long,
    val contentType: String,
    /** AttachmentKind name. */
    val kind: String,
    val fileName: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val thumbnail: ByteArray?,
    val blobPath: String?,
    /** AttachmentState name. */
    val state: String,
)

@Entity(tableName = "reactions", primaryKeys = ["conversationId", "targetAuthor", "targetMid", "reactorId"])
data class ReactionEntity(
    val conversationId: String,
    val targetAuthor: String,
    val targetMid: String,
    val reactorId: String,
    val emoji: String,
    val timestamp: Long,
)

/** Blocking DAO: used inside crypto transactions on the crypto thread. */
@Dao
interface GroupDao {
    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    fun group(groupId: String): GroupEntity?

    @Upsert
    fun putGroup(group: GroupEntity)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY addedAt, userId")
    fun members(groupId: String): List<GroupMemberEntity>

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    fun clearMembers(groupId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putMembers(members: List<GroupMemberEntity>)

    @Upsert
    fun putSenderKey(row: SenderKeyEntity)

    @Query("SELECT record FROM sender_keys WHERE senderId = :senderId AND distributionId = :distributionId")
    fun senderKey(senderId: String, distributionId: String): ByteArray?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun putShare(row: GroupKeyShareEntity): Long

    @Query("SELECT memberId FROM group_key_shares WHERE groupId = :groupId AND distributionId = :distributionId")
    fun shares(groupId: String, distributionId: String): List<String>

    @Query("DELETE FROM group_key_shares WHERE groupId = :groupId")
    fun clearShares(groupId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun putDistribution(row: GroupDistributionEntity): Long

    @Query("SELECT groupId FROM group_distributions WHERE senderId = :senderId AND distributionId = :distributionId")
    fun distributionGroup(senderId: String, distributionId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun hold(row: HeldGroupEnvelopeEntity): Long

    @Query("SELECT * FROM held_group_envelopes WHERE senderId = :senderId ORDER BY seq")
    fun held(senderId: String): List<HeldGroupEnvelopeEntity>

    @Query("DELETE FROM held_group_envelopes WHERE senderId = :senderId AND transportId = :transportId")
    fun releaseHeld(senderId: String, transportId: String)

    @Query("DELETE FROM held_group_envelopes WHERE receivedAt < :cutoff")
    fun purgeHeld(cutoff: Long)

    @Query(
        """SELECT EXISTS(SELECT 1 FROM messages WHERE peerId = :peerId
           AND conversationId NOT IN (SELECT groupId FROM groups))""",
    )
    fun hasDirectMessages(peerId: String): Boolean

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND messageId = :messageId LIMIT 1")
    fun messageIn(conversationId: String, messageId: String): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND peerId = :peerId AND messageId = :messageId",
    )
    fun messageFrom(conversationId: String, peerId: String, messageId: String): MessageEntity?

    @Query("DELETE FROM outbox WHERE messageId = :messageId")
    fun removeOutbox(messageId: String)

    @Query("UPDATE outbox SET recipients = :recipients WHERE messageId = :messageId")
    fun setOutboxRecipients(messageId: String, recipients: String)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId AND outgoing = 1")
    fun setStatus(messageId: String, status: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun putGroupSends(rows: List<GroupSendEntity>)

    @Query("UPDATE group_sends SET delivered = 1 WHERE messageId = :messageId AND memberId = :memberId")
    fun groupDelivered(messageId: String, memberId: String): Int

    @Query("SELECT COUNT(*) FROM group_sends WHERE messageId = :messageId AND delivered = 0")
    fun undelivered(messageId: String): Int

    @Upsert
    fun putReaction(row: ReactionEntity)

    @Query(
        """DELETE FROM reactions WHERE conversationId = :conversationId AND targetAuthor = :targetAuthor
           AND targetMid = :targetMid AND reactorId = :reactorId""",
    )
    fun deleteReaction(conversationId: String, targetAuthor: String, targetMid: String, reactorId: String)

    @Query(
        "SELECT timestamp FROM reactions WHERE conversationId = :c AND targetAuthor = :a AND targetMid = :m AND reactorId = :r",
    )
    fun reactionTime(c: String, a: String, m: String, r: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putAttachment(row: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE messageRow = :messageRow")
    fun attachment(messageRow: Long): AttachmentEntity?

    @Query("UPDATE attachments SET state = :state WHERE messageRow = :messageRow")
    fun setAttachmentState(messageRow: Long, state: String)
}

/** Flows for the UI. */
@Dao
interface GroupQueries {
    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    fun observeGroup(groupId: String): Flow<GroupEntity?>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY addedAt, userId")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM groups ORDER BY createdAt")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM reactions WHERE conversationId = :conversationId")
    fun observeReactions(conversationId: String): Flow<List<ReactionEntity>>

    @Query(
        """SELECT a.* FROM attachments a JOIN messages m ON m.localOrder = a.messageRow
           WHERE m.conversationId = :conversationId""",
    )
    fun observeAttachments(conversationId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageRow = :messageRow")
    suspend fun attachment(messageRow: Long): AttachmentEntity?

    @Query("UPDATE attachments SET state = :state WHERE messageRow = :messageRow")
    suspend fun setAttachmentState(messageRow: Long, state: String)

    @Query("UPDATE attachments SET state = :state, blobPath = :blobPath WHERE messageRow = :messageRow")
    suspend fun setAttachmentBlob(messageRow: Long, state: String, blobPath: String?)

    @Query("UPDATE attachments SET remoteId = :remoteId, state = 'Ready' WHERE messageRow = :messageRow")
    suspend fun setUploaded(messageRow: Long, remoteId: String)

    @Query(
        """SELECT a.* FROM attachments a JOIN messages m ON m.localOrder = a.messageRow
           WHERE a.state = 'Uploading' AND m.status = 'Sending'""",
    )
    suspend fun pendingUploads(): List<AttachmentEntity>

    /** Last message of each group, for the chat list (same shape as 1:1 rows). */
    @Query(
        """
        SELECT g.groupId AS groupId, g.name AS name, g.avatar AS avatar, g.status AS status, g.createdAt AS createdAt,
               m.messageId AS messageId, m.peerId AS peerId, m.outgoing AS outgoing, m.body AS body,
               m.timestamp AS timestamp, m.status AS msgStatus, m.placeholder AS placeholder, m.system AS system,
               (SELECT kind FROM attachments a WHERE a.messageRow = m.localOrder) AS attachmentKind,
               (SELECT COUNT(*) FROM messages u
                 WHERE u.conversationId = g.groupId AND u.outgoing = 0 AND u.readByMe = 0 AND u.system = 0) AS unread
        FROM groups g
        LEFT JOIN messages m ON m.localOrder =
            (SELECT MAX(localOrder) FROM messages x WHERE x.conversationId = g.groupId)
        WHERE g.status != 'Left'
        """,
    )
    fun observeGroupRows(): Flow<List<GroupRow>>
}

data class GroupRow(
    val groupId: String,
    val name: String,
    val avatar: ByteArray?,
    val status: String,
    val createdAt: Long,
    val messageId: String?,
    val peerId: String?,
    val outgoing: Boolean?,
    val body: String?,
    val timestamp: Long?,
    val msgStatus: String?,
    val placeholder: String?,
    val system: Boolean?,
    val attachmentKind: String?,
    val unread: Int,
)
