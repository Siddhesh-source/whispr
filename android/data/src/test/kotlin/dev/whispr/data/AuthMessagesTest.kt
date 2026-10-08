package dev.whispr.data

import dev.whispr.data.auth.AuthMessages
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** Same vectors as server/internal/auth/messages_test.go. */
class AuthMessagesTest {
    @Test
    fun registerVector() {
        val key = byteArrayOf(5) + ByteArray(32) { (it + 1).toByte() }
        assertEquals(
            "7768697370722d72656769737465722d763100050102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20416461",
            hex(AuthMessages.register(key, "Ada")),
        )
    }

    @Test
    fun authVector() {
        val nonce = ByteArray(32) { it.toByte() }
        assertEquals(
            "7768697370722d617574682d76310000112233445566778899aabbccddeeff" +
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            hex(AuthMessages.auth(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), nonce)),
        )
    }

    @Test
    fun deleteVector() {
        val nonce = ByteArray(32) { it.toByte() }
        assertEquals(
            "7768697370722d64656c6574652d76310000112233445566778899aabbccddeeff" +
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            hex(AuthMessages.delete(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), nonce)),
        )
    }
}
