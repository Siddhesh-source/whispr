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

/**
 * How much we trust a contact's pinned identity key.
 * - Unverified: pinned on first sight (QR, username, or their request), not yet compared in person.
 * - Verified: safety numbers compared in person.
 * - KeyChanged: the server now reports a different key. Never accepted silently;
 *   sending is blocked until the user acknowledges it.
 */
enum class TrustState { Unverified, Verified, KeyChanged }

data class Contact(
    val userId: UserId,
    val displayName: String,
    val identityKey: ByteArray,
    val trust: TrustState = TrustState.Unverified,
    /** They added us (or messaged us) and we have not accepted yet. */
    val isRequest: Boolean = false,
) {
    override fun equals(other: Any?) = other is Contact &&
        userId == other.userId &&
        displayName == other.displayName &&
        identityKey.contentEquals(other.identityKey) &&
        trust == other.trust &&
        isRequest == other.isRequest

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

    /** Not a Whispr contact code, an unsupported version, or corrupted. */
    data object InvalidCode : AddContactResult

    /** The code names another server; never followed. */
    data object DifferentServer : AddContactResult

    /** The server reports a different key than the scanned code. Possible interception; nothing was added. */
    data object KeyMismatch : AddContactResult
    data class Failed(val error: AuthError) : AddContactResult
}

/** Safety number for a contact: 60 digits to compare, and the text of the code to show as a QR. */
data class SafetyNumber(val digits: String, val qrCode: String)

enum class VerifyResult { Match, Mismatch, InvalidCode }

data class MyProfile(val userId: UserId, val displayName: String, val username: String?, val avatarPath: String?)

sealed interface ProfileResult {
    data object Ok : ProfileResult
    data object InvalidInput : ProfileResult
    data object Unavailable : ProfileResult
    data class Failed(val error: AuthError) : ProfileResult
}

/** Privacy settings for activity metadata. Both are off by default. */
data class PrivacySettings(val readReceipts: Boolean = false, val typingIndicators: Boolean = false)
