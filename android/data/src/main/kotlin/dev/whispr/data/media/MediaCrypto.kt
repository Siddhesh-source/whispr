package dev.whispr.data.media

import java.security.MessageDigest
import java.security.SecureRandom
import org.signal.libsignal.crypto.Aes256GcmDecryption
import org.signal.libsignal.crypto.Aes256GcmEncryption
import org.signal.libsignal.crypto.CryptographicHash
import org.signal.libsignal.protocol.InvalidKeyException

/**
 * Attachment encryption with libsignal's AES-256-GCM. No custom cryptography:
 * this only frames libsignal's output.
 *
 *     blob   := nonce (12) ‖ ciphertext ‖ tag (16)      key: 32 random bytes per file
 *     digest := SHA-256(blob)                           (libsignal CryptographicHash)
 *
 * The key and digest travel inside the end-to-end encrypted message; only
 * the blob is uploaded. A recipient checks the digest before decrypting
 * (constant-time compare) and the GCM tag before using any plaintext.
 */
object MediaCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val OVERHEAD = NONCE_BYTES + TAG_BYTES
    private val AAD = "whispr-attachment-v1".toByteArray()

    class Sealed(val blob: ByteArray, val key: ByteArray, val digest: ByteArray)

    fun seal(plaintext: ByteArray, random: SecureRandom = SecureRandom()): Sealed {
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val body = plaintext.copyOf()
        val enc = Aes256GcmEncryption(key, nonce, AAD)
        enc.encrypt(body)
        val blob = nonce + body + enc.computeTag()
        return Sealed(blob, key, sha256(blob))
    }

    /** Returns the plaintext, or null if the digest, key or tag doesn't match. */
    fun open(blob: ByteArray, key: ByteArray, digest: ByteArray): ByteArray? {
        if (!MessageDigest.isEqual(sha256(blob), digest)) return null
        if (blob.size < OVERHEAD || key.size != KEY_BYTES) return null
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val body = blob.copyOfRange(NONCE_BYTES, blob.size - TAG_BYTES)
        val tag = blob.copyOfRange(blob.size - TAG_BYTES, blob.size)
        return try {
            val dec = Aes256GcmDecryption(key, nonce, AAD)
            dec.decrypt(body)
            if (dec.verifyTag(tag)) body else null
        } catch (_: InvalidKeyException) {
            null
        }
    }

    fun sha256(bytes: ByteArray): ByteArray = CryptographicHash("SHA-256").run {
        update(bytes)
        finish()
    }
}
