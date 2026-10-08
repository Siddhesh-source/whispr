package dev.whispr.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A status update, ours ([authorId] = our user ID) or a contact's. Photo
 * statuses carry the attachment columns: the blob stays encrypted on disk
 * like any attachment. Rows are deleted at [expireAt] with their blob file.
 */
@Entity(tableName = "statuses", primaryKeys = ["authorId", "statusId"], indices = [Index("expireAt")])
data class StatusEntity(
    val authorId: String,
    val statusId: String,
    /** "text" or "image". */
    val kind: String,
    /** The text, or a photo's caption. */
    val body: String,
    val background: Int,
    val createdAt: Long,
    val expireAt: Long,
    val viewed: Boolean = false,
    /** StatusSendState name for our own statuses; "Sent" for received ones. */
    val sendState: String = "Sent",
    val remoteId: String? = null,
    val key: ByteArray? = null,
    val digest: ByteArray? = null,
    val size: Long = 0,
    val contentType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnail: ByteArray? = null,
    /** Encrypted blob on disk, once uploaded (ours) or downloaded (theirs). */
    val blobPath: String? = null,
    /** AttachmentState name, for photo statuses. */
    val mediaState: String? = null,
)

/** One call in the local log. Never sent anywhere. */
@Entity(tableName = "calls", indices = [Index("startedAt")])
data class CallEntity(
    @PrimaryKey val callId: String,
    val peerId: String,
    val outgoing: Boolean,
    val video: Boolean,
    val startedAt: Long,
    val connectedAt: Long?,
    val endedAt: Long,
    /** CallOutcome name. */
    val outcome: String,
)

/** A status row with its author's display name, for the feed. */
data class StatusRow(
    val authorId: String,
    val statusId: String,
    val kind: String,
    val body: String,
    val background: Int,
    val createdAt: Long,
    val expireAt: Long,
    val viewed: Boolean,
    val sendState: String,
    val size: Long,
    val contentType: String?,
    val width: Int?,
    val height: Int?,
    val thumbnail: ByteArray?,
    val mediaState: String?,
    val authorName: String?,
)

@Dao
interface StatusDao {
    @Query(
        """SELECT s.authorId, s.statusId, s.kind, s.body, s.background, s.createdAt, s.expireAt, s.viewed,
                  s.sendState, s.size, s.contentType, s.width, s.height, s.thumbnail, s.mediaState,
                  c.displayName AS authorName
             FROM statuses s LEFT JOIN contacts c ON c.userId = s.authorId
            WHERE s.expireAt > :now
            ORDER BY s.createdAt""",
    )
    fun observeLive(now: Long): Flow<List<StatusRow>>

    @Query("SELECT * FROM statuses WHERE authorId = :author AND statusId = :id")
    fun get(author: String, id: String): StatusEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(status: StatusEntity): Long

    @Upsert
    fun put(status: StatusEntity)

    @Query("UPDATE statuses SET viewed = 1 WHERE authorId = :author AND statusId = :id")
    fun markViewed(author: String, id: String)

    @Query("UPDATE statuses SET sendState = :state WHERE authorId = :author AND statusId = :id")
    fun setSendState(author: String, id: String, state: String)

    @Query("UPDATE statuses SET mediaState = :state WHERE authorId = :author AND statusId = :id")
    fun setMediaState(author: String, id: String, state: String)

    @Query("UPDATE statuses SET mediaState = :state, blobPath = :path WHERE authorId = :author AND statusId = :id")
    fun setBlob(author: String, id: String, state: String, path: String?)

    @Query("UPDATE statuses SET remoteId = :remoteId WHERE authorId = :author AND statusId = :id")
    fun setRemoteId(author: String, id: String, remoteId: String)

    @Query("DELETE FROM statuses WHERE authorId = :author AND statusId = :id")
    fun delete(author: String, id: String): Int

    @Query("SELECT * FROM statuses WHERE expireAt <= :now")
    fun expired(now: Long): List<StatusEntity>

    @Query("DELETE FROM statuses WHERE expireAt <= :now")
    fun deleteExpired(now: Long)

    /** Our photo statuses whose upload has not finished (resumed on start). */
    @Query("SELECT * FROM statuses WHERE authorId = :me AND sendState = 'Sending' AND kind = 'image'")
    fun pendingUploads(me: String): List<StatusEntity>

    /** Everyone a status goes to: accepted, visible contacts whose key we still trust. */
    @Query(
        """SELECT userId FROM contacts
            WHERE isRequest = 0 AND hidden = 0 AND trust != 'KeyChanged' AND length(identityKey) > 0
              AND userId != :me""",
    )
    fun audience(me: String): List<String>
}

@Dao
interface CallDao {
    @Query(
        """SELECT k.callId, k.peerId, k.outgoing, k.video, k.startedAt, k.connectedAt, k.endedAt, k.outcome,
                  c.displayName AS peerName
             FROM calls k LEFT JOIN contacts c ON c.userId = k.peerId
            ORDER BY k.startedAt DESC LIMIT 500""",
    )
    fun observe(): Flow<List<CallRow>>

    @Upsert
    suspend fun put(call: CallEntity)

    @Query("DELETE FROM calls")
    suspend fun clear()
}

data class CallRow(
    val callId: String,
    val peerId: String,
    val outgoing: Boolean,
    val video: Boolean,
    val startedAt: Long,
    val connectedAt: Long?,
    val endedAt: Long,
    val outcome: String,
    val peerName: String?,
)
