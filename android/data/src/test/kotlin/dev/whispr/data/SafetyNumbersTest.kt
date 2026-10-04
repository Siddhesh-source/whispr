package dev.whispr.data

import dev.whispr.data.contacts.SafetyNumbers
import dev.whispr.domain.model.VerifyResult
import java.util.Base64
import java.util.UUID
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair

/** Safety numbers come from libsignal; these tests pin how we use them. */
class SafetyNumbersTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val aKey = IdentityKeyPair.generate().publicKey.serialize()
    private val bKey = IdentityKeyPair.generate().publicKey.serialize()

    @Test
    fun bothSidesSeeTheSameDigits() {
        val fromA = SafetyNumbers.compute(a, aKey, b, bKey)
        val fromB = SafetyNumbers.compute(b, bKey, a, aKey)
        assertEquals(60, fromA.digits.length)
        assertTrue(fromA.digits.all { it.isDigit() })
        assertEquals(fromA.digits, fromB.digits)
    }

    @Test
    fun eachSideScansTheOther() {
        val fromB = SafetyNumbers.compute(b, bKey, a, aKey)
        assertEquals(VerifyResult.Match, SafetyNumbers.compare(a, aKey, b, bKey, fromB.qrCode))
    }

    @Test
    fun differentKeyChangesTheNumberAndFailsComparison() {
        val other = IdentityKeyPair.generate().publicKey.serialize()
        assertNotEquals(SafetyNumbers.compute(a, aKey, b, bKey).digits, SafetyNumbers.compute(a, aKey, b, other).digits)
        val impostor = SafetyNumbers.compute(b, other, a, aKey)
        assertEquals(VerifyResult.Mismatch, SafetyNumbers.compare(a, aKey, b, bKey, impostor.qrCode))
    }

    @Test
    fun hostileScansAreInvalidNeverThrow() {
        listOf(null, "", "whispr-sn:", "whispr-sn:!!", "whispr:abc", "whispr-sn:" + "A".repeat(600)).forEach {
            assertEquals(it?.take(20), VerifyResult.InvalidCode, SafetyNumbers.compare(a, aKey, b, bKey, it))
        }
        val rnd = Random(7)
        repeat(2_000) {
            val bytes = ByteArray(rnd.nextInt(0, 120)).also(rnd::nextBytes)
            SafetyNumbers.compare(
                a,
                aKey,
                b,
                bKey,
                "whispr-sn:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            )
        }
    }
}
