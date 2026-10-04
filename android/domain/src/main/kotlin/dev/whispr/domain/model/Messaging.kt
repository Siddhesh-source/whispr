package dev.whispr.domain.model

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@JvmInline
value class ConversationId(val value: String) {
    companion object {
        /**
         * The 1:1 conversation ID both participants derive independently:
         * SHA-256("whispr-conv-v1" 0x00 || lower id || higher id), truncated to a
         * UUID. The server treats it as an opaque identifier.
         */
        fun direct(a: UserId, b: UserId): ConversationId {
            val (lo, hi) = listOf(a.value.lowercase(), b.value.lowercase()).sorted()
            val digest = MessageDigest.getInstance("SHA-256").run {
                update("whispr-conv-v1\u0000".toByteArray())
                update(lo.toByteArray())
                update(hi.toByteArray())
                digest()
            }
            // RFC 9562 version 8 (custom) with the RFC 4122 variant.
            digest[6] = ((digest[6].toInt() and 0x0F) or 0x80).toByte()
            digest[8] = ((digest[8].toInt() and 0x3F) or 0x80).toByte()
            val buf = ByteBuffer.wrap(digest, 0, 16)
            return ConversationId(UUID(buf.long, buf.long).toString())
        }
    }
}

data class Contact(val userId: UserId, val displayName: String, val identityKey: ByteArray) {
    override fun equals(other: Any?) = other is Contact &&
        userId == other.userId &&
        displayName == other.displayName &&
        identityKey.contentEquals(other.identityKey)

    override fun hashCode() = userId.hashCode()
}

enum class MessageStatus { Sending, Sent, Delivered, Read, Failed }

data class Message(
    val id: String,
    val conversationId: ConversationId,
    val outgoing: Boolean,
    val text: String,
    val timestamp: Instant,
    /** Outgoing messages only. */
    val status: MessageStatus?,
)

data class ConversationSummary(
    val id: ConversationId,
    val peer: Contact,
    val lastMessage: Message?,
    val unreadCount: Int,
)

enum class ConnectionState { Offline, Connecting, Connected }

sealed interface AddContactResult {
    data class Added(val contact: Contact) : AddContactResult
    data object NotFound : AddContactResult
    data object InvalidId : AddContactResult
    data object IsSelf : AddContactResult
    data class Failed(val error: AuthError) : AddContactResult
}

/** Privacy settings for activity metadata. Both are off by default. */
data class PrivacySettings(val readReceipts: Boolean = false, val typingIndicators: Boolean = false)
