package dev.whispr.data.contacts

import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.signal.libsignal.protocol.IdentityKey

/** The contents of a contact QR code. */
data class ContactCard(val userId: UUID, val identityKey: ByteArray, val server: String) {
    override fun equals(other: Any?) = other is ContactCard &&
        userId == other.userId &&
        identityKey.contentEquals(other.identityKey) &&
        server == other.server

    override fun hashCode() = userId.hashCode()
}

sealed interface QrError {
    /** Not a Whispr code at all (another app's QR, a URL, random text). */
    data object NotWhispr : QrError
    data object UnsupportedVersion : QrError

    /** Wrong length, bad encoding, reserved bits set, trailing data, or an invalid key. */
    data object Malformed : QrError

    /** The code names a different server; switching servers from a QR is never allowed. */
    data object DifferentServer : QrError
}

sealed interface QrParse {
    data class Ok(val card: ContactCard) : QrParse
    data class Err(val error: QrError) : QrParse
}

/**
 * Contact QR payload: `whispr:` + base64url(no padding) of
 *
 *     version(1) = 1 ‖ flags(1) = 0 ‖ user_id(16) ‖ identity_key(33) ‖ server_len(1) ‖ server(server_len, ASCII)
 *
 * where server is an origin such as `https://chat.example.org`.
 *
 * Every scanned code is hostile input. [parse] checks the overall length
 * before decoding, every field length exactly, rejects reserved flags and
 * trailing bytes, validates the key with libsignal, and restricts the server
 * to an HTTPS origin (plain HTTP only for loopback, and only when allowed).
 * It never throws.
 */
object ContactQr {
    const val PREFIX = "whispr:"
    const val VERSION: Byte = 1
    private const val ID_LEN = 16
    private const val KEY_LEN = 33
    private const val HEADER_LEN = 2
    private const val MAX_SERVER_LEN = 253
    private const val MIN_LEN = HEADER_LEN + ID_LEN + KEY_LEN + 1

    /** Generous upper bound on the text form; anything longer is rejected before decoding. */
    const val MAX_TEXT_LEN = 512

    fun encode(card: ContactCard): String {
        val server = card.server.toByteArray(Charsets.US_ASCII)
        require(server.size in 1..MAX_SERVER_LEN) { "server origin too long" }
        require(card.identityKey.size == KEY_LEN) { "identity key must be $KEY_LEN bytes" }
        val buf = ByteBuffer.allocate(MIN_LEN + server.size)
            .put(VERSION)
            .put(0)
            .putLong(card.userId.mostSignificantBits)
            .putLong(card.userId.leastSignificantBits)
            .put(card.identityKey)
            .put(server.size.toByte())
            .put(server)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array())
    }

    /**
     * @param expectedServer our own server origin; codes naming another server are rejected.
     * @param allowInsecureLoopback permit `http://` for loopback hosts (debug builds only).
     */
    fun parse(text: String?, expectedServer: String, allowInsecureLoopback: Boolean = false): QrParse {
        if (text == null || text.length > MAX_TEXT_LEN || !text.startsWith(PREFIX)) return err(QrError.NotWhispr)
        val body = text.substring(PREFIX.length)
        if (body.isEmpty() || body.any { !it.isBase64Url() }) return err(QrError.Malformed)
        val bytes = try {
            Base64.getUrlDecoder().decode(body)
        } catch (_: IllegalArgumentException) {
            return err(QrError.Malformed)
        }
        if (bytes.isEmpty()) return err(QrError.Malformed)
        if (bytes[0] != VERSION) return err(QrError.UnsupportedVersion)
        if (bytes.size < MIN_LEN || bytes[1] != 0.toByte()) return err(QrError.Malformed)

        val buf = ByteBuffer.wrap(bytes, HEADER_LEN, bytes.size - HEADER_LEN)
        val userId = UUID(buf.long, buf.long)
        val key = ByteArray(KEY_LEN).also { buf.get(it) }
        val serverLen = buf.get().toInt() and 0xFF
        if (serverLen == 0 || serverLen > MAX_SERVER_LEN || buf.remaining() != serverLen) return err(QrError.Malformed)
        val serverBytes = ByteArray(serverLen).also { buf.get(it) }
        if (serverBytes.any { it < 0x21 || it > 0x7E }) return err(QrError.Malformed) // printable ASCII only

        if (!isValidIdentityKey(key)) return err(QrError.Malformed)
        val server =
            normalizeOrigin(String(serverBytes, Charsets.US_ASCII), allowInsecureLoopback)
                ?: return err(QrError.Malformed)
        if (server != normalizeOrigin(expectedServer, allowInsecureLoopback = true)) return err(QrError.DifferentServer)
        return QrParse.Ok(ContactCard(userId, key, server))
    }

    /**
     * Reduces a URL to `scheme://host[:port]`, or null if it is not an
     * acceptable server: HTTPS (HTTP only for loopback when allowed), no
     * user info, path, query, or fragment.
     */
    fun normalizeOrigin(url: String, allowInsecureLoopback: Boolean): String? {
        val u: HttpUrl = url.toHttpUrlOrNull() ?: return null
        if (u.username.isNotEmpty() || u.password.isNotEmpty() || u.query != null || u.fragment != null) return null
        if (u.encodedPath != "/" && u.encodedPath.isNotEmpty()) return null
        val loopback = u.host == "localhost" || u.host == "127.0.0.1" || u.host == "::1"
        if (u.scheme == "http" && !(allowInsecureLoopback && loopback)) return null
        val defaultPort = HttpUrl.defaultPort(u.scheme)
        return u.scheme + "://" + u.host + if (u.port == defaultPort) "" else ":${u.port}"
    }

    private fun isValidIdentityKey(key: ByteArray): Boolean = try {
        IdentityKey(key)
        true
    } catch (_: Exception) {
        false
    }

    private fun Char.isBase64Url() =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '_'

    private fun err(e: QrError) = QrParse.Err(e)
}
