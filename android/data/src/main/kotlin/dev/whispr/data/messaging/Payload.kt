package dev.whispr.data.messaging

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What clients put inside an envelope. These bytes are padded and encrypted
 * with libsignal before they leave the device; the server never sees them.
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
}

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
