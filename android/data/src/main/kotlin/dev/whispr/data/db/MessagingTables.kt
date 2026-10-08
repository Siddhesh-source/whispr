package dev.whispr.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    /** The pinned identity key. Empty only if it could not be fetched yet. */
    val identityKey: ByteArray,
    val addedAt: Long,
    /** TrustState name: Unverified, Verified or KeyChanged. */
    @ColumnInfo(defaultValue = "Unverified") val trust: String = "Unverified",
    /** True while this is an incoming contact or message request we have not accepted. */
    @ColumnInfo(defaultValue = "0") val isRequest: Boolean = false,
    /** The different key the server reported, held until the user acknowledges it. */
    val pendingKey: ByteArray? = null,
    /** A group member we have no 1:1 chat with: pinned for sessions, not listed as a contact. */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)

/**
 * One row per message. [localOrder] (insertion order) is the display order:
 * incoming messages are inserted in server-sequence order, outgoing ones when
 * the user sends them. The unique index on ([peerId], [messageId]) makes
 * redelivered envelopes no-ops, which is what turns at-least-once delivery
 * into exactly-once on screen. Message IDs are chosen by the sender, so they
 * are only unique per peer.
 *
 * For incoming text, [messageId] is the sender's logical message ID (`mid`),
 * which a resend keeps. A [placeholder] row stands in for an envelope we
 * could not decrypt (keyed by its transport ID, which equals the `mid` of a
 * first send) until it is recovered or given up on.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["peerId", "messageId"], unique = true),
        Index(value = ["conversationId", "localOrder"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val localOrder: Long = 0,
    val messageId: String,
    val conversationId: String,
    val peerId: String,
    val outgoing: Boolean,
    val body: String,
    val timestamp: Long,
    /** Outgoing: Sending/Sent/Delivered/Read/Failed. Incoming: null. */
    val status: String?,
    @ColumnInfo(defaultValue = "0") val readByMe: Boolean = false,
    /** Null for a real message; otherwise a [Placeholder] state name. */
    val placeholder: String? = null,
    /** A local group event ("Sam added Alex"); never sent. */
    @ColumnInfo(defaultValue = "0") val system: Boolean = false,
    /** A reply: the quoted message's logical ID and author (user ID). Resolved locally when shown. */
    val quoteId: String? = null,
    val quoteAuthor: String? = null,
    @ColumnInfo(defaultValue = "0") val forwarded: Boolean = false,
    /** Deleted for everyone by its author: content cleared, a tombstone stays. */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    /** Disappearing messages: the timer it was sent with, in seconds. */
    val expiresIn: Long? = null,
    /** When it is deleted (epoch ms); set when the timer starts (sent, or read by us). */
    val expireAt: Long? = null,
)

/** Per-conversation settings that every participant shares: the disappearing-message timer. */
@Entity(tableName = "conversation_settings")
data class ConversationSettingEntity(
    @PrimaryKey val conversationId: String,
    /** Seconds; 0 is off. */
    val timer: Long,
    /** The sender's timestamp of the change that set it; the newest change wins. */
    val timerTs: Long,
)

/** One search result row. */
data class SearchRow(
    val localOrder: Long,
    val messageId: String,
    val conversationId: String,
    val peerId: String,
    val outgoing: Boolean,
    val body: String,
    val timestamp: Long,
    val status: String?,
    val groupName: String?,
    val contactName: String?,
)

/** States of a "couldn't decrypt" stand-in. They only move forward, except that a late resend may still recover an unrecoverable one. */
enum class Placeholder {
    /** We asked the sender to resend. */
    Pending,

    /** Waiting to ask: the sender's lane is blocked (key change or no keys). */
    Waiting,

    /** Gave up: the sender could not resend it or never answered. */
    Unrecoverable,

    /** Held encrypted because the sender's safety number changed; shown after review. */
    Held,
}

/**
 * Envelopes waiting to be accepted by the server, sent strictly in [seq]
 * order with one in flight. Survives restarts, so nothing typed offline is lost.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["messageId"], unique = true)])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val messageId: String,
    val conversationId: String,
    val recipientId: String,
    /** The plaintext Payload (padded and encrypted when sent). */
    val payload: ByteArray,
    val clientTs: Long,
    /**
     * The encrypted wire bytes, set once when this entry first reaches the
     * head of its lane, so resends are byte-identical.
     */
    val ciphertext: ByteArray? = null,
    /** Set for a group message: sent with send_multi to [recipients] (comma-separated, fixed at send time). */
    val groupId: String? = null,
    val recipients: String? = null,
    /**
     * Lower goes first: [PRIORITY_CALL] (call signaling), [PRIORITY_NORMAL]
     * (messages and controls), [PRIORITY_STATUS] (status fan-out). A status to
     * every contact must never delay a call or a chat message.
     */
    @ColumnInfo(defaultValue = "1") val priority: Int = PRIORITY_NORMAL,
) {
    companion object {
        const val PRIORITY_CALL = 0
        const val PRIORITY_NORMAL = 1
        const val PRIORITY_STATUS = 2
    }
}

@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)

data class ConversationRow(
    val peerId: String,
    val displayName: String,
    val identityKey: ByteArray,
    val trust: String,
    val isRequest: Boolean,
    val messageId: String?,
    val outgoing: Boolean?,
    val body: String?,
    val timestamp: Long?,
    val status: String?,
    val placeholder: String?,
    val unread: Int,
)

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE hidden = 0 ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>

    /** Every contact, including group members we only know from groups (for names). */
    @Query("SELECT * FROM contacts")
    fun observeEveryone(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts")
    suspend fun everyone(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE userId = :userId")
    suspend fun get(userId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE userId = :userId")
    fun observe(userId: String): Flow<ContactEntity?>

    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Query("UPDATE contacts SET isRequest = 0 WHERE userId = :userId")
    suspend fun accept(userId: String)

    @Query("UPDATE contacts SET trust = :trust WHERE userId = :userId")
    suspend fun setTrust(userId: String, trust: String)

    /** A different key was reported: keep the pinned one, hold the new one, flag it. */
    @Query("UPDATE contacts SET trust = 'KeyChanged', pendingKey = :newKey WHERE userId = :userId")
    suspend fun flagKeyChange(userId: String, newKey: ByteArray)

    /** The user acknowledged a key change: pin the new key, back to Unverified. */
    @Query(
        """UPDATE contacts SET identityKey = pendingKey, pendingKey = NULL, trust = 'Unverified'
           WHERE userId = :userId AND pendingKey IS NOT NULL""",
    )
    suspend fun acceptPendingKey(userId: String)

    @Query("UPDATE contacts SET displayName = :name WHERE userId = :userId")
    suspend fun setName(userId: String, name: String)

    @Query("DELETE FROM contacts WHERE userId = :userId")
    suspend fun delete(userId: String)
}

@Dao
interface MessageDao {
    /**
     * One row per contact (with or without messages), newest activity first.
     * Contacts without messages sort by when they were added.
     */
    @Query(
        """
        SELECT c.userId AS peerId, c.displayName, c.identityKey, c.trust, c.isRequest,
               m.messageId, m.outgoing, m.body, m.timestamp, m.status, m.placeholder,
               (SELECT COUNT(*) FROM messages u
                 WHERE u.peerId = c.userId AND u.outgoing = 0 AND u.readByMe = 0
                   AND u.conversationId NOT IN (SELECT groupId FROM groups)) AS unread
        FROM contacts c
        LEFT JOIN messages m ON m.localOrder = (SELECT MAX(localOrder) FROM messages x WHERE x.peerId = c.userId
            AND x.conversationId NOT IN (SELECT groupId FROM groups))
        WHERE c.hidden = 0
        ORDER BY COALESCE(m.timestamp, c.addedAt) DESC
        """,
    )
    fun observeConversations(): Flow<List<ConversationRow>>

    @Query("DELETE FROM messages WHERE peerId = :peerId AND conversationId NOT IN (SELECT groupId FROM groups)")
    suspend fun deleteFrom(peerId: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY localOrder")
    fun observe(conversationId: String): Flow<List<MessageEntity>>

    /** Returns -1 if a message with this ID already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun get(messageId: String): MessageEntity?

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId AND outgoing = 1")
    suspend fun setStatus(messageId: String, status: String)

    /** Status only moves forward: Sending → Sent → Delivered → Read. */
    @Query("UPDATE messages SET status = 'Sent' WHERE messageId = :messageId AND status = 'Sending'")
    suspend fun markSent(messageId: String)

    @Query("UPDATE messages SET status = 'Delivered' WHERE messageId = :messageId AND status IN ('Sending', 'Sent')")
    suspend fun markDelivered(messageId: String)

    @Query(
        """UPDATE messages SET status = 'Read'
           WHERE messageId IN (:messageIds) AND peerId = :peerId AND outgoing = 1 AND status IN ('Sending', 'Sent', 'Delivered')""",
    )
    suspend fun markReadByPeer(messageIds: List<String>, peerId: String)

    @Query("SELECT messageId FROM messages WHERE conversationId = :conversationId AND outgoing = 0 AND readByMe = 0")
    suspend fun unreadIncomingIds(conversationId: String): List<String>

    @Query("UPDATE messages SET readByMe = 1 WHERE conversationId = :conversationId AND outgoing = 0")
    suspend fun markAllReadByMe(conversationId: String)

    /** Reading starts the clock of disappearing incoming messages. */
    @Query(
        """UPDATE messages SET expireAt = :now + expiresIn * 1000
           WHERE conversationId = :conversationId AND outgoing = 0 AND expiresIn IS NOT NULL AND expireAt IS NULL""",
    )
    suspend fun startTimers(conversationId: String, now: Long)

    /**
     * Case-insensitive (ASCII) substring search over shown text. [pattern] is
     * already escaped for LIKE with a backslash as the escape character.
     */
    @Query(
        """SELECT m.localOrder, m.messageId, m.conversationId, m.peerId, m.outgoing, m.body, m.timestamp, m.status,
                  g.name AS groupName, c.displayName AS contactName
           FROM messages m
           LEFT JOIN groups g ON g.groupId = m.conversationId
           LEFT JOIN contacts c ON c.userId = m.peerId
           WHERE m.body LIKE :pattern ESCAPE '\' AND m.placeholder IS NULL AND m.deleted = 0 AND m.system = 0
             AND (g.groupId IS NOT NULL OR c.isRequest = 0)
           ORDER BY m.timestamp DESC LIMIT :limit""",
    )
    suspend fun search(pattern: String, limit: Int): List<SearchRow>

    @Query("SELECT * FROM conversation_settings WHERE conversationId = :conversationId")
    fun observeSetting(conversationId: String): Flow<ConversationSettingEntity?>
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY seq LIMIT 1")
    fun observeHead(): Flow<OutboxEntity?>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(entry: OutboxEntity): Long

    @Query("DELETE FROM outbox WHERE messageId = :messageId")
    suspend fun remove(messageId: String): Int
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Upsert
    suspend fun put(setting: SettingEntity)
}

/** Multi-table writes that must commit atomically. */
@Dao
abstract class MessagingTransactions {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOutbox(entry: OutboxEntity): Long

    @Query("DELETE FROM outbox WHERE messageId = :messageId")
    protected abstract suspend fun removeOutbox(messageId: String): Int

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId AND outgoing = 1")
    protected abstract suspend fun setStatus(messageId: String, status: String)

    @Query("UPDATE messages SET status = 'Sent' WHERE messageId = :messageId AND status = 'Sending'")
    protected abstract suspend fun markSent(messageId: String)

    /** A new outgoing message and its outbox entry, together or not at all. */
    @Transaction
    open suspend fun sendNew(message: MessageEntity, entry: OutboxEntity) {
        insertMessage(message)
        insertOutbox(entry)
    }

    @Transaction
    open suspend fun requeue(messageId: String, entry: OutboxEntity) {
        setStatus(messageId, "Sending")
        insertOutbox(entry)
    }

    /** The server stored the envelope. */
    @Transaction
    open suspend fun accepted(messageId: String) {
        removeOutbox(messageId)
        markSent(messageId)
    }

    /** The server refused the envelope permanently. */
    @Transaction
    open suspend fun rejected(messageId: String) {
        removeOutbox(messageId)
        setStatus(messageId, "Failed")
    }
}
