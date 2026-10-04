package dev.whispr.data

import dev.whispr.data.contacts.ContactCard
import dev.whispr.data.contacts.ContactQr
import dev.whispr.data.contacts.QrError
import dev.whispr.data.contacts.QrParse
import java.util.Base64
import java.util.UUID
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair

/** Every scanned QR is hostile input: these tests try to break the parser. */
class ContactQrTest {
    private val server = "https://chat.example.org"
    private val key = IdentityKeyPair.generate().publicKey.serialize()
    private val card = ContactCard(UUID.randomUUID(), key, server)

    private fun parse(text: String?, expected: String = server, loopback: Boolean = false) =
        ContactQr.parse(text, expected, loopback)

    private fun raw(card: ContactCard) =
        Base64.getUrlDecoder().decode(ContactQr.encode(card).removePrefix(ContactQr.PREFIX))

    private fun wrap(bytes: ByteArray) =
        ContactQr.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    @Test
    fun roundTrip() {
        assertEquals(QrParse.Ok(card), parse(ContactQr.encode(card)))
    }

    @Test
    fun encodingIsCompact() {
        // Fits comfortably in a low-version QR code.
        assertTrue(ContactQr.encode(card).length < 120)
    }

    @Test
    fun foreignAndEmptyInputsAreNotWhispr() {
        listOf(null, "", "https://evil.example/x", "WHISPR:abc", "whispr", "SIGNAL:abc", "x".repeat(10_000)).forEach {
            assertEquals(it?.take(20), QrParse.Err(QrError.NotWhispr), parse(it))
        }
    }

    @Test
    fun badEncodingIsMalformed() {
        listOf("whispr:", "whispr:!!!!", "whispr:abc=", "whispr:ab cd", "whispr:é", "whispr:A").forEach {
            assertEquals(it, QrParse.Err(QrError.Malformed), parse(it))
        }
    }

    @Test
    fun unknownVersionIsRejected() {
        val b = raw(card).also { it[0] = 2 }
        assertEquals(QrParse.Err(QrError.UnsupportedVersion), parse(wrap(b)))
    }

    @Test
    fun reservedFlagsRejected() {
        val b = raw(card).also { it[1] = 1 }
        assertEquals(QrParse.Err(QrError.Malformed), parse(wrap(b)))
    }

    @Test
    fun truncationAndTrailingBytesRejected() {
        val b = raw(card)
        for (len in 1 until b.size) {
            assertEquals("truncated to $len bytes", QrParse.Err(QrError.Malformed), parse(wrap(b.copyOf(len))))
        }
        assertEquals(QrParse.Err(QrError.Malformed), parse(wrap(b + byteArrayOf(0))))
    }

    @Test
    fun serverLengthFieldMustMatch() {
        val b = raw(card)
        val lenIndex = 2 + 16 + 33
        listOf(0, 1, b[lenIndex] + 1, 255).forEach { bad ->
            val m = b.copyOf().also { it[lenIndex] = bad.toByte() }
            assertEquals("len $bad", QrParse.Err(QrError.Malformed), parse(wrap(m)))
        }
    }

    @Test
    fun invalidIdentityKeyRejected() {
        val b = raw(card)
        b[2 + 16] = 0x09 // unknown key type
        assertEquals(QrParse.Err(QrError.Malformed), parse(wrap(b)))
    }

    @Test
    fun hostileServersRejected() {
        listOf(
            "http://chat.example.org", // cleartext
            "https://user:pw@chat.example.org",
            "https://chat.example.org/path",
            "https://chat.example.org/?q=1",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "https://chat.example.org\u0000",
        ).forEach { s ->
            val bytes = s.toByteArray(Charsets.UTF_8)
            val b = raw(card).copyOf(2 + 16 + 33)
            val m = b + byteArrayOf(bytes.size.toByte()) + bytes
            assertTrue("server $s accepted", parse(wrap(m)) is QrParse.Err)
        }
    }

    @Test
    fun differentServerIsNeverAccepted() {
        val other = card.copy(server = "https://evil.example")
        assertEquals(QrParse.Err(QrError.DifferentServer), parse(ContactQr.encode(other)))
    }

    @Test
    fun loopbackHttpOnlyWhenAllowed() {
        val local = card.copy(server = "http://127.0.0.1:8080")
        assertEquals(
            QrParse.Err(QrError.Malformed),
            parse(ContactQr.encode(local), "http://127.0.0.1:8080/", loopback = false),
        )
        assertTrue(parse(ContactQr.encode(local), "http://127.0.0.1:8080/", loopback = true) is QrParse.Ok)
    }

    @Test
    fun originNormalization() {
        assertEquals("https://a.example", ContactQr.normalizeOrigin("https://a.example:443/", false))
        assertEquals("https://a.example:8443", ContactQr.normalizeOrigin("https://A.example:8443", false))
        assertNull(ContactQr.normalizeOrigin("http://a.example", true))
    }

    @Test
    fun randomGarbageNeverThrows() {
        val rnd = Random(42)
        repeat(5_000) {
            val bytes = ByteArray(rnd.nextInt(0, 200)).also(rnd::nextBytes)
            // Random bodies behind a valid prefix, plus random raw strings.
            parse(wrap(bytes))
            parse(String(bytes, Charsets.ISO_8859_1))
            if (bytes.isNotEmpty()) bytes[0] = 1
            parse(wrap(bytes))
        }
    }
}
