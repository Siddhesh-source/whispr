package dev.whispr.domain.model

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
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
    /** Set when this is a stand-in for a message that could not be shown. */
    val notice: MessageNotice? = null,
    /** Incoming group messages: who wrote it. */
    val author: UserId? = null,
    val authorName: String? = null,
    val attachment: Attachment? = null,
    val reactions: List<Reaction> = emptyList(),
    /** A group event ("Sam added Alex"), shown centred, never sent. */
    val system: Boolean = false,
    /** The message this one replies to. */
    val quote: Quote? = null,
    val forwarded: Boolean = false,
    /** Deleted for everyone by its author: shown as a tombstone, with no content. */
    val deleted: Boolean = false,
    /** Disappearing messages: the timer this message was sent with. */
    val expiresIn: Duration? = null,
    /** When it will be deleted; null until its timer starts (incoming: when read). */
    val expiresAt: Instant? = null,
)

/**
 * The quoted message above a reply, resolved from the local database. Only
 * the reference travels; [found] is false once we no longer have it
 * (deleted, expired, or never received).
 */
data class Quote(
    val messageId: String,
    val outgoing: Boolean,
    val authorName: String?,
    val text: String,
    val attachmentKind: AttachmentKind?,
    val found: Boolean,
)

/** A local search result: the message and where it is. */
data class SearchHit(
    val conversation: ConversationId,
    val title: String,
    val message: Message,
    /** Set for a 1:1 chat. */
    val peer: UserId?,
    /** Set for a group. */
    val group: GroupId?,
)

/** Rules shared by the UI and the data layer. */
object MessageRules {
    /** Disappearing-message timers offered, in seconds. 0 is off. */
    val TIMER_OPTIONS: List<Long> = listOf(0L, 5 * 60L, 60 * 60L, 24 * 60 * 60L, 7 * 24 * 60 * 60L)

    /** The longest timer accepted from a peer (four weeks). */
    const val MAX_TIMER_SECONDS: Long = 28L * 24 * 60 * 60

    /** "Delete for everyone" is offered for our own messages this long after sending. */
    val DELETE_FOR_EVERYONE_WINDOW: Duration = Duration.ofHours(24)

    fun canDeleteForEveryone(m: Message, now: Instant): Boolean = m.outgoing &&
        !m.system &&
        !m.deleted &&
        m.notice == null &&
        m.status != MessageStatus.Failed &&
        m.status != MessageStatus.Sending &&
        Duration.between(m.timestamp, now) <= DELETE_FOR_EVERYONE_WINDOW

    /** Only shown content can be replied to, copied or forwarded. */
    fun isContent(m: Message): Boolean = !m.system && !m.deleted && m.notice == null
}

/** Why a message is shown as a stand-in instead of its text. */
enum class MessageNotice {
    /** Couldn't decrypt; the sender was asked to resend it. */
    Pending,

    /** Couldn't decrypt; will ask the sender once their chat is unblocked. */
    Waiting,

    /** Couldn't decrypt and couldn't be recovered. */
    Unrecoverable,

    /** Held because the sender's safety number changed; shown after review. */
    Held,
}

/** A row in the chat list: a 1:1 chat ([peer]) or a group ([group]). */
data class ConversationSummary(
    val id: ConversationId,
    val peer: Contact?,
    val lastMessage: Message?,
    val unreadCount: Int,
    val group: GroupSummary? = null,
) {
    val title: String get() = group?.name ?: peer?.displayName.orEmpty()
}

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

/**
 * Privacy settings. Read receipts and typing indicators are off by default;
 * screen security (no screenshots, recordings or recents thumbnails) is on.
 */
data class PrivacySettings(
    val readReceipts: Boolean = false,
    val typingIndicators: Boolean = false,
    val screenSecurity: Boolean = true,
    /** Calls go only through the server's relay, hiding your IP address from the people you call. */
    val relayCalls: Boolean = false,
)
