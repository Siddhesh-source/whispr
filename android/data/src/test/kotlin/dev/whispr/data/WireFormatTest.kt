package dev.whispr.data

import dev.whispr.data.crypto.Padding
import dev.whispr.data.crypto.WireFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireFormatTest {

    @Test
    fun paddingRoundTripsAndHidesExactLength() {
        for (size in listOf(0, 1, 158, 159, 160, 161, 319, 320, 1000)) {
            val data = ByteArray(size) { (it % 251 + 1).toByte() }
            val padded = Padding.pad(data)
            assertEquals("size $size", 0, padded.size % Padding.BLOCK)
            // Room for the 0x80 marker: 159 bytes fit one block, 160 need two.
            assertEquals("size $size", ((size / Padding.BLOCK) + 1) * Padding.BLOCK, padded.size)
            assertArrayEquals(data, Padding.unpad(padded))
        }
        // Trailing zeros in the message itself survive.
        val zeros = byteArrayOf(1, 0, 0)
        assertArrayEquals(zeros, Padding.unpad(Padding.pad(zeros)))
    }

    @Test
    fun invalidPaddingIsRejected() {
        assertNull(Padding.unpad(ByteArray(160)))
        assertNull(Padding.unpad(ByteArray(160) { 1 }))
        assertNull(Padding.unpad(ByteArray(0)))
    }

    @Test
    fun onlyVersionOneLibsignalTypesAreAccepted() {
        assertEquals(WireFormat.TYPE_PREKEY, WireFormat.decode(byteArrayOf(1, 1, 9))?.first)
        assertEquals(WireFormat.TYPE_WHISPER, WireFormat.decode(byteArrayOf(1, 2, 9))?.first)
        // Plaintext JSON (what a malicious server might inject), other
        // versions and types, and empty bodies are all refused.
        assertNull(WireFormat.decode("""{"t":"text","body":"hi"}""".toByteArray()))
        assertNull(WireFormat.decode(byteArrayOf(2, 1, 9)))
        assertNull(WireFormat.decode(byteArrayOf(1, 3, 9)))
        assertNull(WireFormat.decode(byteArrayOf(1, 1)))
        assertNull(WireFormat.decode(ByteArray(0)))
    }
}
