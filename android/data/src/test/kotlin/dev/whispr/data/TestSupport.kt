package dev.whispr.data

import dev.whispr.data.crypto.KeyWrapper
import dev.whispr.data.crypto.WrappedBlob
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Software AES-GCM stand-in for the AndroidKeyStore wrapper in JVM tests. */
class SoftwareKeyWrapper(
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
) : KeyWrapper {
    override fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(WrappedBlob.GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return WrappedBlob.encode(iv, cipher.doFinal(plaintext))
    }

    override fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray {
        val (iv, ct) = WrappedBlob.decode(blob)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(WrappedBlob.GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }
}

fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
