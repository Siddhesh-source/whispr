package dev.whispr.data

import dev.whispr.data.backup.BackupCipher
import dev.whispr.data.backup.ChatBackup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backup file format: round trip, and every way a file can be wrong. */
class BackupCipherTest {
    private val key = Random(1).nextBytes(ChatBackup.KEY_BYTES)

    private fun seal(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCipher.encrypt(out, key).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(file: ByteArray, k: ByteArray = key) =
        BackupCipher.decrypt(ByteArrayInputStream(file), k).use { it.readBytes() }

    private fun failure(block: () -> Unit): String = try {
        block()
        "no error"
    } catch (e: BackupCipher.BadBackup) {
        e.message.orEmpty()
    }

    @Test
    fun roundTripsAcrossChunkBoundaries() {
        for (size in listOf(0, 1, BackupCipher.CHUNK - 1, BackupCipher.CHUNK, BackupCipher.CHUNK * 3 + 7)) {
            val plain = Random(size).nextBytes(size)
            assertArrayEquals("size $size", plain, open(seal(plain)))
        }
    }

    @Test
    fun theSameDataNeverEncryptsTheSameWay() {
        val plain = ByteArray(100)
        assertTrue(!seal(plain).contentEquals(seal(plain)))
    }

    @Test
    fun aWrongKeySaysSo() {
        val other = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertTrue(failure { open(seal(ByteArray(10)), other) }.startsWith("wrong"))
    }

    @Test
    fun aCutOffFileIsRejectedEvenAtAChunkBoundary() {
        val file = seal(Random(2).nextBytes(BackupCipher.CHUNK * 2))
        // Header (24) + one full chunk (4 + CHUNK + 16): what is left looks like a valid shorter file.
        val oneChunk = 24 + 4 + BackupCipher.CHUNK + 16
        assertEquals("the backup is incomplete", failure { open(file.copyOf(oneChunk)) })
        assertEquals("the backup is incomplete", failure { open(file.copyOf(file.size - 3)) })
    }

    @Test
    fun aChangedByteOrSwappedChunksAreRejected() {
        val file = seal(Random(3).nextBytes(BackupCipher.CHUNK * 2 + 5))
        val flipped = file.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        assertTrue(failure { open(flipped) }.isNotEmpty())
        val chunk = 4 + BackupCipher.CHUNK + 16
        val swapped = file.copyOf()
        System.arraycopy(file, 24 + chunk, swapped, 24, chunk)
        System.arraycopy(file, 24, swapped, 24 + chunk, chunk)
        assertTrue(failure { open(swapped) }.startsWith("wrong"))
        assertEquals("not a Whispr backup", failure { open("PK\u0003\u0004 not ours at all......".toByteArray()) })
    }

    @Test
    fun recoveryKeysAreWrittenInGroupsAndReadLeniently() {
        val text = ChatBackup.format(key)
        assertEquals(64 + 15, text.length)
        assertArrayEquals(key, ChatBackup.parse(text))
        assertArrayEquals(key, ChatBackup.parse(text.uppercase().replace(" ", "-")))
        assertArrayEquals(key, ChatBackup.parse("  " + text.replace(" ", "") + "\n"))
        assertNull(ChatBackup.parse(text.dropLast(1)))
        assertNull(ChatBackup.parse(text.replaceFirst(text.first { it.isLetterOrDigit() }, 'z')))
    }
}
