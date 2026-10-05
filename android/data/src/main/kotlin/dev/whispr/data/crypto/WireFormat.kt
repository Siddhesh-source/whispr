package dev.whispr.data.crypto

import org.signal.libsignal.protocol.message.CiphertextMessage

/**
 * Framing around libsignal ciphertext inside an envelope's opaque payload:
 *
 *     payload := 0x01 (format v1) | type | libsignal serialized message
 *     type    := 0x01 PreKeySignalMessage | 0x02 SignalMessage | 0x03 SenderKeyMessage (groups)
 *
 * Anything else is not ours and is dropped, so the server cannot inject
 * plaintext. This is framing only; all cryptography is libsignal's.
 */
object WireFormat {
    const val VERSION: Byte = 0x01
    const val TYPE_PREKEY: Byte = 0x01
    const val TYPE_WHISPER: Byte = 0x02
    const val TYPE_SENDER_KEY: Byte = 0x03

    fun encode(message: CiphertextMessage): ByteArray {
        val type = when (message.type) {
            CiphertextMessage.PREKEY_TYPE -> TYPE_PREKEY
            CiphertextMessage.WHISPER_TYPE -> TYPE_WHISPER
            CiphertextMessage.SENDERKEY_TYPE -> TYPE_SENDER_KEY
            else -> error("unexpected libsignal message type ${message.type}")
        }
        return byteArrayOf(VERSION, type) + message.serialize()
    }

    /** Returns (type, body), or null if [payload] is not a v1 Whispr ciphertext. */
    fun decode(payload: ByteArray): Pair<Byte, ByteArray>? {
        if (payload.size < 3 || payload[0] != VERSION) return null
        val type = payload[1]
        if (type != TYPE_PREKEY && type != TYPE_WHISPER && type != TYPE_SENDER_KEY) return null
        return type to payload.copyOfRange(2, payload.size)
    }
}

/**
 * Pads plaintext to a multiple of 160 bytes (0x80 then zeros, as Signal does)
 * so ciphertext length reveals only a size bucket, not the exact length.
 */
object Padding {
    const val BLOCK = 160
    private const val MARKER: Byte = 0x80.toByte()

    fun pad(plaintext: ByteArray): ByteArray {
        val padded = ((plaintext.size + 1 + BLOCK - 1) / BLOCK) * BLOCK
        return plaintext.copyOf(padded).also { it[plaintext.size] = MARKER }
    }

    /** Returns null if [padded] is not validly padded. */
    fun unpad(padded: ByteArray): ByteArray? {
        var i = padded.size - 1
        while (i >= 0 && padded[i] == 0.toByte()) i--
        if (i < 0 || padded[i] != MARKER) return null
        return padded.copyOf(i)
    }
}
