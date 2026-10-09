package dev.whispr.data.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup file format: `WHSPBK01`, a 16-byte salt, then AES-256-GCM
 * chunks of at most [CHUNK] plaintext bytes, each `[u32 length][ciphertext]`.
 * The file key is HMAC-SHA256(recovery key, label || salt), so every file has
 * its own key and a counter nonce never repeats. Each chunk's AAD says
 * whether it is the last one, so a cut-off file is rejected, and the counter
 * rejects reordered chunks.
 */
internal object BackupCipher {
    private val MAGIC = "WHSPBK01".toByteArray(Charsets.US_ASCII)
    private val LABEL = "whispr-backup-v1".toByteArray(Charsets.US_ASCII)
    private const val SALT = 16
    const val CHUNK = 64 * 1024
    private const val TAG_BITS = 128
    private const val NONCE = 12
    private const val MAX_CIPHERTEXT = CHUNK + TAG_BITS / 8

    /** Thrown when the key is wrong or the file was changed or cut off. */
    class BadBackup(message: String) : IOException(message)

    private fun fileKey(recoveryKey: ByteArray, salt: ByteArray): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(recoveryKey, "HmacSHA256"))
        mac.update(LABEL)
        return SecretKeySpec(mac.doFinal(salt), "AES")
    }

    private fun nonce(counter: Long) = ByteBuffer.allocate(NONCE).putInt(0).putLong(counter).array()

    fun encrypt(out: OutputStream, recoveryKey: ByteArray): OutputStream {
        val salt = ByteArray(SALT).also { SecureRandom().nextBytes(it) }
        out.write(MAGIC)
        out.write(salt)
        return Encrypting(DataOutputStream(out), fileKey(recoveryKey, salt))
    }

    fun decrypt(input: InputStream, recoveryKey: ByteArray): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        val salt = ByteArray(SALT)
        try {
            data.readFully(magic)
            data.readFully(salt)
        } catch (_: EOFException) {
            throw BadBackup("not a Whispr backup")
        }
        if (!magic.contentEquals(MAGIC)) throw BadBackup("not a Whispr backup")
        return Decrypting(data, fileKey(recoveryKey, salt))
    }

    private class Encrypting(private val out: DataOutputStream, private val key: SecretKeySpec) : OutputStream() {
        private val buffer = ByteArray(CHUNK)
        private var filled = 0
        private var counter = 0L
        private var closed = false

        override fun write(b: Int) {
            if (filled == CHUNK) seal(last = false)
            buffer[filled++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var n = len
            while (n > 0) {
                if (filled == CHUNK) seal(last = false)
                val take = minOf(n, CHUNK - filled)
                System.arraycopy(b, o, buffer, filled, take)
                filled += take
                o += take
                n -= take
            }
        }

        private fun seal(last: Boolean) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(counter++)))
            cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            val sealed = cipher.doFinal(buffer, 0, filled)
            out.writeInt(sealed.size)
            out.write(sealed)
            filled = 0
        }

        override fun close() {
            if (closed) return
            closed = true
            seal(last = true)
            out.close()
        }
    }

    private class Decrypting(private val input: DataInputStream, private val key: SecretKeySpec) : InputStream() {
        private var chunk = ByteArray(0)
        private var pos = 0
        private var counter = 0L
        private var ended = false

        override fun read(): Int {
            if (!fill()) return -1
            return chunk[pos++].toInt() and BYTE
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            return n
        }

        private fun fill(): Boolean {
            while (pos == chunk.size) {
                if (ended) return false
                val size = try {
                    input.readInt()
                } catch (_: EOFException) {
                    throw BadBackup("the backup is incomplete")
                }
                if (size !in 0..MAX_CIPHERTEXT) throw BadBackup("the backup is damaged")
                val sealed = ByteArray(size)
                try {
                    input.readFully(sealed)
                } catch (_: EOFException) {
                    throw BadBackup("the backup is incomplete")
                }
                chunk = open(sealed, last = false) ?: open(sealed, last = true)?.also { ended = true }
                    ?: throw BadBackup("wrong recovery key, or the backup is damaged")
                counter++
                pos = 0
            }
            return true
        }

        private fun open(sealed: ByteArray, last: Boolean): ByteArray? = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(counter)))
            cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            cipher.doFinal(sealed)
        } catch (_: AEADBadTagException) {
            null
        }

        override fun close() = input.close()
    }

    private const val BYTE = 0xFF
}
