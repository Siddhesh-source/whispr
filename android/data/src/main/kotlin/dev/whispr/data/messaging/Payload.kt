package dev.whispr.data.messaging

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What clients put inside an envelope's opaque payload. The server never
 * sees this structure. In Phase 2 it is plaintext JSON; Phase 4 encrypts
 * these exact bytes with libsignal, so receipts and typing become invisible
 * to the server too.
 */
@Serializable
sealed interface Payload {
    @Serializable
    @SerialName("text")
    data class Text(val body: String) : Payload

    /** The recipient read these message IDs. Only sent if read receipts are enabled. */
    @Serializable
    @SerialName("read")
    data class Read(val ids: List<String>) : Payload

    /**
     * "I added you": sent after scanning someone's code or finding their
     * username. Carries the sender's identity key so the recipient can pin it
     * and cross-check it against the server.
     */
    @Serializable
    @SerialName("contact_request")
    data class ContactRequest(val name: String, val key: String) : Payload

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
