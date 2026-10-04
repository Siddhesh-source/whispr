package dev.whispr.domain.model

import java.time.Instant

/** Server-assigned account identifier (a UUID string). Not secret. */
@JvmInline
value class UserId(val value: String)

/**
 * The local user's profile. [userId] is null until the server has accepted
 * the registration. The avatar is only ever stored on the device.
 */
data class Account(val userId: UserId?, val displayName: String, val avatarPath: String?) {
    val isRegistered: Boolean get() = userId != null
}

/** An avatar the user picked, as an opaque platform URI string. */
@JvmInline
value class AvatarSource(val uri: String)

sealed interface SessionState {
    /** No authentication attempted yet this process. */
    data object Idle : SessionState
    data object Authenticating : SessionState
    data class Active(val expiresAt: Instant) : SessionState

    /** The last attempt failed; the app keeps working offline and retries. */
    data class Unavailable(val error: AuthError) : SessionState
}

sealed interface AuthError {
    /** Could not reach the server. */
    data object Network : AuthError

    /** The server rejected our signature or does not know our account. */
    data object Rejected : AuthError

    /** The server rejected the request as malformed (e.g. display name). */
    data object InvalidInput : AuthError

    /** Unexpected server response or 5xx. */
    data object Server : AuthError

    /** The identity key could not be created, read, or used. */
    data object Storage : AuthError
}

sealed interface AuthResult<out T> {
    data class Ok<T>(val value: T) : AuthResult<T>
    data class Err(val error: AuthError) : AuthResult<Nothing>
}
