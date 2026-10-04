package dev.whispr.data.auth

import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.AuthApi
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.AuthRepository
import dev.whispr.domain.repository.IdentityRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Supplies a valid bearer token to authenticated API clients (later phases). */
interface TokenSource {
    /** A token valid for at least a short margin, re-authenticating if needed; null if unavailable. */
    suspend fun bearerToken(): String?
}

/**
 * Registration and challenge-response sign-in. The bearer token lives only in
 * memory: it is never written to disk, so a stolen backup or disk image holds
 * no session. After a restart the app signs in again with the identity key.
 */
class SessionAuthRepository(
    private val api: AuthApi,
    private val identity: IdentityRepository,
    private val accounts: AccountRepository,
    private val clock: Clock = Clock.systemUTC(),
) : AuthRepository,
    TokenSource {

    private val state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val session: StateFlow<SessionState> = state.asStateFlow()

    private val mutex = Mutex()

    @Volatile private var token: Token? = null

    private class Token(val value: String, val expiresAt: Instant)

    override suspend fun register(displayName: String): AuthResult<UserId> {
        val (publicKey, signature) = try {
            val key = identity.getOrCreatePublicKey()
            key to identity.sign(AuthMessages.register(key, displayName))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return AuthResult.Err(AuthError.Storage)
        }
        return when (val r = api.register(publicKey, displayName, signature)) {
            is ApiResult.Success -> AuthResult.Ok(UserId(r.body.userId))
            is ApiResult.HttpError -> AuthResult.Err(mapHttp(r.code))
            ApiResult.NetworkError -> AuthResult.Err(AuthError.Network)
        }
    }

    override suspend fun authenticate(): AuthResult<Unit> = mutex.withLock {
        if (currentToken() != null) return@withLock AuthResult.Ok(Unit)
        signIn()
    }

    override suspend fun bearerToken(): String? {
        currentToken()?.let { return it }
        return mutex.withLock {
            currentToken() ?: run {
                signIn()
                currentToken()
            }
        }
    }

    private fun currentToken(): String? =
        token?.takeIf { it.expiresAt.isAfter(clock.instant().plus(EXPIRY_MARGIN)) }?.value

    /** Caller holds [mutex]. */
    private suspend fun signIn(): AuthResult<Unit> {
        state.value = SessionState.Authenticating
        val result = doSignIn()
        state.value = when (result) {
            is AuthResult.Ok -> SessionState.Active(token!!.expiresAt)
            is AuthResult.Err -> {
                token = null
                SessionState.Unavailable(result.error)
            }
        }
        return result
    }

    private suspend fun doSignIn(): AuthResult<Unit> {
        val userId = accounts.getAccount()?.userId ?: return AuthResult.Err(AuthError.Rejected)
        val uuid = runCatching { UUID.fromString(userId.value) }.getOrElse { return AuthResult.Err(AuthError.Storage) }

        val challenge = when (val r = api.challenge(userId.value)) {
            is ApiResult.Success -> r.body
            is ApiResult.HttpError -> return AuthResult.Err(mapHttp(r.code))
            ApiResult.NetworkError -> return AuthResult.Err(AuthError.Network)
        }
        val nonce = runCatching { AuthApi.unb64(challenge.nonce) }.getOrElse { return AuthResult.Err(AuthError.Server) }

        val signature = try {
            identity.sign(AuthMessages.auth(uuid, nonce))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return AuthResult.Err(AuthError.Storage)
        }

        val verified = when (val r = api.verify(challenge.challengeId, signature)) {
            is ApiResult.Success -> r.body
            is ApiResult.HttpError -> return AuthResult.Err(mapHttp(r.code))
            ApiResult.NetworkError -> return AuthResult.Err(AuthError.Network)
        }
        val expiresAt = try {
            Instant.parse(verified.expiresAt)
        } catch (_: DateTimeParseException) {
            return AuthResult.Err(AuthError.Server)
        }
        token = Token(verified.token, expiresAt)
        return AuthResult.Ok(Unit)
    }

    private fun mapHttp(code: Int): AuthError = when (code) {
        HTTP_BAD_REQUEST -> AuthError.InvalidInput
        HTTP_UNAUTHORIZED, HTTP_NOT_FOUND -> AuthError.Rejected
        HTTP_TOO_MANY_REQUESTS -> AuthError.Network // transient; retry later
        else -> AuthError.Server
    }

    private companion object {
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(30)
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
