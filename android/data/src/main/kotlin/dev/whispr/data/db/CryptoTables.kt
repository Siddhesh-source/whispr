package dev.whispr.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

// libsignal state and the bookkeeping for end-to-end encryption. Everything
// lives in the same SQLCipher database as messages, so a ratchet step and the
// message it produced or consumed commit in one transaction.

/** A libsignal SessionRecord per peer (device ID is always 1). */
@Entity(tableName = "signal_sessions")
data class SignalSessionEntity(@PrimaryKey val peerId: String, val record: ByteArray)

/** One-time EC prekeys (private halves). Deleted when used. */
@Entity(tableName = "signal_prekeys")
data class SignalPreKeyEntity(@PrimaryKey val id: Int, val record: ByteArray, val createdAt: Long)

/** Signed EC prekeys: the current one plus recent ones kept for delayed messages. */
@Entity(tableName = "signal_signed_prekeys")
data class SignalSignedPreKeyEntity(@PrimaryKey val id: Int, val record: ByteArray, val createdAt: Long)

/** Kyber prekeys: one-time keys (deleted when used) and last-resort keys (kept). */
@Entity(tableName = "signal_kyber_prekeys")
data class SignalKyberPreKeyEntity(
    @PrimaryKey val id: Int,
    val record: ByteArray,
    val lastResort: Boolean,
    val createdAt: Long,
)

/**
 * Base keys already used with a last-resort Kyber key. libsignal requires
 * these to reject a replayed PreKey message (ReusedBaseKeyException).
 */
@Entity(tableName = "signal_kyber_used_base_keys", primaryKeys = ["kyberId", "signedId", "baseKey"])
data class KyberUsedBaseKeyEntity(val kyberId: Int, val signedId: Int, val baseKey: ByteArray)

/** Envelopes already processed, by sender and the sender-chosen transport ID. */
@Entity(tableName = "seen_envelopes", primaryKeys = ["senderId", "transportId"])
data class SeenEnvelopeEntity(val senderId: String, val transportId: String, val receivedAt: Long)

/**
 * What we sent, kept for 30 days so a peer that could not decrypt an
 * envelope can ask for it again. [plaintext] is the padded payload (null for
 * control messages, which are never resent).
 */
@Entity(tableName = "sent_envelopes", indices = [Index(value = ["recipientId", "transportId"])])
data class SentEnvelopeEntity(
    @PrimaryKey val transportId: String,
    val recipientId: String,
    /** The logical message ID for text (equals the first transport ID), else null. */
    val mid: String?,
    /** text, read, contact_request or control. */
    val kind: String,
    val plaintext: ByteArray?,
    val sentAt: Long,
    val autoResent: Boolean = false,
)

/**
 * One row per envelope from [peerId] we could not decrypt and asked to be
 * resent. [state] is queued (reset not yet sent) or awaiting (reset sent as
 * [resetTransportId]; the 24 h window starts at [deliveredAt]).
 */
@Entity(tableName = "pending_resets", primaryKeys = ["peerId", "transportId"])
data class PendingResetEntity(
    val peerId: String,
    val transportId: String,
    val state: String,
    val attempts: Int,
    val failedAt: Long,
    val resetTransportId: String? = null,
    val deliveredAt: Long? = null,
)

/** A recipient whose outgoing lane is blocked, and why. */
@Entity(tableName = "parked_recipients")
data class ParkedRecipientEntity(@PrimaryKey val recipientId: String, val reason: String, val retryAt: Long)

/**
 * Envelopes from a contact whose identity key changed, held encrypted until
 * the user acknowledges the change (never shown before that).
 */
@Entity(tableName = "held_envelopes", primaryKeys = ["senderId", "transportId"])
data class HeldEnvelopeEntity(
    val senderId: String,
    val transportId: String,
    val ciphertext: ByteArray,
    val receivedAt: Long,
    /** Server sequence, for decrypting in arrival order. */
    val seq: Long,
)

/** Persisted count of unexpected processing failures per envelope (poison guard). */
@Entity(tableName = "decrypt_attempts", primaryKeys = ["senderId", "transportId"])
data class DecryptAttemptEntity(val senderId: String, val transportId: String, val count: Int)

/**
 * Blocking DAO for the crypto path. libsignal's store callbacks are
 * synchronous, so everything inside a crypto transaction uses these
 * functions on the crypto thread (never the main thread).
 */
@Dao
interface CryptoDao {
    // Sessions
    @Query("SELECT record FROM signal_sessions WHERE peerId = :peerId")
    fun session(peerId: String): ByteArray?

    @Upsert
    fun putSession(session: SignalSessionEntity)

    @Query("DELETE FROM signal_sessions WHERE peerId = :peerId")
    fun deleteSession(peerId: String)

    // Prekeys
    @Query("SELECT record FROM signal_prekeys WHERE id = :id")
    fun preKey(id: Int): ByteArray?

    @Upsert
    fun putPreKey(key: SignalPreKeyEntity)

    @Query("DELETE FROM signal_prekeys WHERE id = :id")
    fun deletePreKey(id: Int)

    @Query("SELECT record FROM signal_signed_prekeys WHERE id = :id")
    fun signedPreKey(id: Int): ByteArray?

    @Query("SELECT * FROM signal_signed_prekeys ORDER BY id")
    fun signedPreKeys(): List<SignalSignedPreKeyEntity>

    @Upsert
    fun putSignedPreKey(key: SignalSignedPreKeyEntity)

    @Query("DELETE FROM signal_signed_prekeys WHERE id = :id")
    fun deleteSignedPreKey(id: Int)

    @Query("SELECT * FROM signal_kyber_prekeys WHERE id = :id")
    fun kyberPreKey(id: Int): SignalKyberPreKeyEntity?

    @Query("SELECT * FROM signal_kyber_prekeys ORDER BY id")
    fun kyberPreKeys(): List<SignalKyberPreKeyEntity>

    @Upsert
    fun putKyberPreKey(key: SignalKyberPreKeyEntity)

    @Query("DELETE FROM signal_kyber_prekeys WHERE id = :id")
    fun deleteKyberPreKey(id: Int)

    /** Returns -1 if this base key was already used with this last-resort key. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertUsedBaseKey(row: KyberUsedBaseKeyEntity): Long

    @Query("DELETE FROM signal_kyber_used_base_keys WHERE kyberId = :kyberId")
    fun deleteUsedBaseKeys(kyberId: Int)

    // Settings (registration ID, key ID counters)
    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun setting(key: String): String?

    @Upsert
    fun putSetting(setting: SettingEntity)

    // Contacts (identity pins)
    @Query("SELECT * FROM contacts WHERE userId = :userId")
    fun contact(userId: String): ContactEntity?

    @Upsert
    fun putContact(contact: ContactEntity)

    @Query("UPDATE contacts SET trust = 'KeyChanged', pendingKey = :newKey WHERE userId = :userId")
    fun flagKeyChange(userId: String, newKey: ByteArray)

    // Envelope bookkeeping
    @Query("SELECT EXISTS(SELECT 1 FROM seen_envelopes WHERE senderId = :senderId AND transportId = :transportId)")
    fun seen(senderId: String, transportId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun markSeen(row: SeenEnvelopeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSent(row: SentEnvelopeEntity)

    @Query("SELECT * FROM sent_envelopes WHERE transportId = :transportId")
    fun sent(transportId: String): SentEnvelopeEntity?

    @Query("UPDATE sent_envelopes SET autoResent = 1 WHERE transportId = :transportId")
    fun markAutoResent(transportId: String)

    @Query("UPDATE outbox SET ciphertext = :ciphertext WHERE messageId = :messageId")
    fun setOutboxCiphertext(messageId: String, ciphertext: ByteArray): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun enqueue(entry: OutboxEntity): Long

    // Messages written from inside crypto transactions
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND messageId = :messageId")
    fun message(peerId: String, messageId: String): MessageEntity?

    @Query(
        "UPDATE messages SET body = :body, timestamp = :timestamp, placeholder = NULL WHERE localOrder = :localOrder",
    )
    fun recoverPlaceholder(localOrder: Long, body: String, timestamp: Long)

    @Query(
        "UPDATE messages SET placeholder = :state WHERE peerId = :peerId AND messageId = :messageId AND placeholder IN (:from)",
    )
    fun setPlaceholderState(peerId: String, messageId: String, state: String, from: List<String>): Int

    @Query("DELETE FROM messages WHERE peerId = :peerId AND messageId = :messageId AND placeholder IS NOT NULL")
    fun deletePlaceholder(peerId: String, messageId: String): Int

    @Query(
        """UPDATE messages SET status = 'Read'
           WHERE messageId IN (:messageIds) AND peerId = :peerId AND outgoing = 1 AND status IN ('Sending', 'Sent', 'Delivered')""",
    )
    fun markReadByPeer(messageIds: List<String>, peerId: String)

    // Held envelopes (identity key changed, awaiting acknowledgement)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun hold(row: HeldEnvelopeEntity): Long

    // Poison guard
    @Query("SELECT count FROM decrypt_attempts WHERE senderId = :senderId AND transportId = :transportId")
    fun attempts(senderId: String, transportId: String): Int?

    @Upsert
    fun putAttempts(row: DecryptAttemptEntity)

    @Query("DELETE FROM decrypt_attempts WHERE senderId = :senderId AND transportId = :transportId")
    fun clearAttempts(senderId: String, transportId: String)

    // Pending resets
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun queueReset(row: PendingResetEntity): Long
}
