package dev.whispr.data.crypto

/**
 * Encrypts small secrets (key material) at rest with a key the app cannot
 * export. Production uses [AndroidKeystoreKeyWrapper]; tests use a software key.
 *
 * Blobs are self-describing: `version (1) || iv length (1) || iv || ciphertext+tag`.
 * [aad] binds a blob to its purpose so, for example, a wrapped database key
 * cannot be swapped in for the identity key.
 */
interface KeyWrapper {
    fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray

    /** @throws java.security.GeneralSecurityException if the blob is corrupt, tampered, or bound to other AAD. */
    fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray
}

internal object WrappedBlob {
    const val VERSION: Byte = 1
    const val GCM_TAG_BITS = 128

    fun encode(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size in 1..255)
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + ciphertext
    }

    /** Returns (iv, ciphertext). */
    fun decode(blob: ByteArray): Pair<ByteArray, ByteArray> {
        if (blob.size < 2 || blob[0] != VERSION) throw java.security.GeneralSecurityException("unknown blob format")
        val ivLen = blob[1].toInt() and 0xFF
        if (ivLen == 0 || blob.size < 2 + ivLen + GCM_TAG_BITS / 8) {
            throw java.security.GeneralSecurityException("truncated blob")
        }
        return blob.copyOfRange(2, 2 + ivLen) to blob.copyOfRange(2 + ivLen, blob.size)
    }
}
