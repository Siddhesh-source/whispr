package dev.whispr.data.contacts

import dev.whispr.domain.model.SafetyNumber
import dev.whispr.domain.model.VerifyResult
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.fingerprint.Fingerprint
import org.signal.libsignal.protocol.fingerprint.FingerprintParsingException
import org.signal.libsignal.protocol.fingerprint.FingerprintVersionMismatchException
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator

/**
 * Safety numbers, computed and compared entirely by libsignal's fingerprint
 * API. Both sides derive the same 60 digits; each shows a scannable code the
 * other scans. Stable identifiers are the 16-byte account IDs.
 */
object SafetyNumbers {
    const val PREFIX = "whispr-sn:"
    private const val ITERATIONS = 5200
    private const val VERSION = 2
    private const val MAX_TEXT_LEN = 512

    fun compute(myId: UUID, myKey: ByteArray, theirId: UUID, theirKey: ByteArray): SafetyNumber {
        val fp = fingerprint(myId, myKey, theirId, theirKey)
        return SafetyNumber(fp.displayableFingerprint.displayText, encode(fp.scannableFingerprint.serialized))
    }

    /** The QR text for a scannable fingerprint. */
    fun encode(scannable: ByteArray): String =
        PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(scannable)

    /**
     * Compares a scanned code (hostile input) with the fingerprint for these
     * two identities, using libsignal's comparison. Never throws.
     */
    fun compare(myId: UUID, myKey: ByteArray, theirId: UUID, theirKey: ByteArray, scanned: String?): VerifyResult {
        if (scanned == null ||
            scanned.length > MAX_TEXT_LEN ||
            !scanned.startsWith(PREFIX)
        ) {
            return VerifyResult.InvalidCode
        }
        val body = scanned.substring(PREFIX.length)
        if (body.isEmpty() ||
            body.any { !(it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_') }
        ) {
            return VerifyResult.InvalidCode
        }
        val bytes = try {
            Base64.getUrlDecoder().decode(body)
        } catch (_: IllegalArgumentException) {
            return VerifyResult.InvalidCode
        }
        return try {
            if (fingerprint(
                    myId,
                    myKey,
                    theirId,
                    theirKey,
                ).scannableFingerprint.compareTo(bytes)
            ) {
                VerifyResult.Match
            } else {
                VerifyResult.Mismatch
            }
        } catch (_: FingerprintVersionMismatchException) {
            VerifyResult.InvalidCode
        } catch (_: FingerprintParsingException) {
            VerifyResult.InvalidCode
        }
    }

    private fun fingerprint(myId: UUID, myKey: ByteArray, theirId: UUID, theirKey: ByteArray): Fingerprint =
        NumericFingerprintGenerator(
            ITERATIONS,
        ).createFor(VERSION, idBytes(myId), IdentityKey(myKey), idBytes(theirId), IdentityKey(theirKey))

    private fun idBytes(id: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
}
