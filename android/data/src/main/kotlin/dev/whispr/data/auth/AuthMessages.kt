package dev.whispr.data.auth

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Signed-message formats. A wire contract with server/internal/auth/messages.go;
 * both sides test the same vectors (AuthMessagesTest / messages_test.go).
 * The labels provide domain separation between registration and login.
 */
object AuthMessages {
    private val REGISTER_LABEL = "whispr-register-v1\u0000".toByteArray(Charsets.US_ASCII)
    private val AUTH_LABEL = "whispr-auth-v1\u0000".toByteArray(Charsets.US_ASCII)
    private val DELETE_LABEL = "whispr-delete-v1\u0000".toByteArray(Charsets.US_ASCII)

    /** `"whispr-register-v1" 0x00 || identity_key (33 bytes) || display_name (UTF-8)` */
    fun register(identityKey: ByteArray, displayName: String): ByteArray {
        require(identityKey.size == IDENTITY_KEY_LEN) { "identity key must be $IDENTITY_KEY_LEN bytes" }
        return REGISTER_LABEL + identityKey + displayName.toByteArray(Charsets.UTF_8)
    }

    /** `"whispr-auth-v1" 0x00 || user_id (16 raw bytes, RFC 4122 order) || nonce` */
    fun auth(userId: UUID, nonce: ByteArray): ByteArray = AUTH_LABEL + raw(userId) + nonce

    /**
     * `"whispr-delete-v1" 0x00 || user_id || nonce`: deleting the account. Its
     * own label means a sign-in signature can never delete an account.
     */
    fun delete(userId: UUID, nonce: ByteArray): ByteArray = DELETE_LABEL + raw(userId) + nonce

    private fun raw(userId: UUID): ByteArray =
        ByteBuffer.allocate(UUID_LEN).putLong(userId.mostSignificantBits).putLong(userId.leastSignificantBits).array()

    private const val IDENTITY_KEY_LEN = 33
    private const val UUID_LEN = 16
}
