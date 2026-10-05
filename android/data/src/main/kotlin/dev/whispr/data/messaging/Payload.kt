package dev.whispr.data.messaging

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What clients put inside an envelope. These bytes are padded and encrypted
 * with libsignal before they leave the device; the server never sees them.
 *
 * 1:1 content and every group control message travel over pairwise
 * sessions. Group content ([Text], [Media], [Reaction] with [g] set) is
 * encrypted once with the sender's group key and fanned out by the server.
 *
 * `replaces` is set only on an automatic resend after the peer could not
 * decrypt the original: it names the failed envelope's transport ID.
 */
@Serializable
sealed interface Payload {
    @Serializable
    @SerialName("text")
    data class Text(
        val body: String,
        /** Logical message ID; equals the first transport ID and survives resends. */
        val mid: String? = null,
        /** Original send time (epoch ms), kept on resends. */
        val ts: Long? = null,
        val replaces: String? = null,
        /** Group ID for a group message. */
        val g: String? = null,
    ) : Payload

    /** An encrypted attachment: the key and digest travel here, the ciphertext in object storage. */
    @Serializable
    @SerialName("media")
    data class Media(
        val a: AttachmentPointer,
        val mid: String? = null,
        val ts: Long? = null,
        val replaces: String? = null,
        val g: String? = null,
    ) : Payload

    /** Sets (or with [emoji] null removes) the sender's reaction to message [target] by [author]. */
    @Serializable
    @SerialName("reaction")
    data class Reaction(
        val target: String,
        val author: String,
        val emoji: String?,
        val ts: Long,
        val replaces: String? = null,
        val g: String? = null,
    ) : Payload

    /** The recipient read these message IDs. Only sent if read receipts are enabled. */
    @Serializable
    @SerialName("read")
    data class Read(val ids: List<String>, val replaces: String? = null) : Payload

    /**
     * "I added you": sent after scanning someone's code or finding their
     * username. Carries the sender's identity key so the recipient can pin it
     * and cross-check it against the server.
     */
    @Serializable
    @SerialName("contact_request")
    data class ContactRequest(val name: String, val key: String, val replaces: String? = null) : Payload

    /** "I could not decrypt these envelopes from you; please resend them." */
    @Serializable
    @SerialName("reset")
    data class SessionReset(val failed: List<String>) : Payload

    /**
     * The answer to a [SessionReset]: which IDs were resent, which were
     * control messages (nothing to show), and which can't be recovered.
     * [aliases] maps a lost resend's ID to the original it replaced.
     */
    @Serializable
    @SerialName("reset_done")
    data class ResetDone(
        val resent: List<String> = emptyList(),
        val control: List<String> = emptyList(),
        val lost: List<String> = emptyList(),
        val aliases: Map<String, String> = emptyMap(),
    ) : Payload

    /** Sent as a transient frame (never stored). Only if typing indicators are enabled. */
    @Serializable
    @SerialName("typing")
    data object Typing : Payload

    /** An admin's full view of a group (pairwise). Also how invites arrive. */
    @Serializable
    @SerialName("group")
    data class GroupUpdate(val state: GroupState, val replaces: String? = null) : Payload

    /** Our sender key for group [g] under distribution [d] (pairwise, base64 SKDM). */
    @Serializable
    @SerialName("sender_key")
    data class SenderKey(val g: String, val d: String, val skdm: String, val replaces: String? = null) : Payload

    /** An invitee accepts (to an admin). */
    @Serializable
    @SerialName("group_join")
    data class GroupJoin(val g: String, val replaces: String? = null) : Payload

    /** An invitee declines (to an admin). */
    @Serializable
    @SerialName("group_decline")
    data class GroupDecline(val g: String, val replaces: String? = null) : Payload

    /** A member leaves (to every member). */
    @Serializable
    @SerialName("group_leave")
    data class GroupLeave(val g: String, val replaces: String? = null) : Payload
}

/** Where an attachment is and how to open it. Only ever inside an encrypted payload. */
@Serializable
data class AttachmentPointer(
    /** Server blob ID. */
    val id: String,
    /** Base64 AES-256 key. */
    val key: String,
    /** Base64 SHA-256 of the blob. */
    val digest: String,
    /** Plaintext size. */
    val size: Long,
    val type: String,
    /** image, file or voice. */
    val kind: String,
    val name: String? = null,
    val w: Int? = null,
    val h: Int? = null,
    val dur: Long? = null,
    /** Base64 JPEG preview. */
    val thumb: String? = null,
)

@Serializable
data class GroupState(
    val id: String,
    val rev: Int,
    val name: String,
    /** Base64 JPEG, at most [MAX_AVATAR_BYTES]. */
    val avatar: String? = null,
    val members: List<MemberState>,
    /** Removal tombstones: userId -> the revision that removed them. */
    val removed: Map<String, Int> = emptyMap(),
) {
    companion object {
        const val MAX_AVATAR_BYTES = 24 * 1024
        const val MAX_NAME = 64
        const val MAX_MEMBERS = 101
    }
}

@Serializable
data class MemberState(
    val id: String,
    /** Base64 identity key. */
    val key: String,
    /** admin or member. */
    val role: String,
    /** Revision at which they were added. */
    val added: Int,
    val invited: Boolean = false,
    /** The admin's label for them (shown for members who are not our contacts). */
    val name: String = "",
)

object PayloadCodec {
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
    }

    fun encode(payload: Payload): ByteArray = json.encodeToString(Payload.serializer(), payload).toByteArray()

    /** Returns null for payloads this version does not understand (forward compatibility). */
    fun decode(bytes: ByteArray): Payload? = try {
        json.decodeFromString(Payload.serializer(), bytes.decodeToString())
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
