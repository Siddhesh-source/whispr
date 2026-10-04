package dev.whispr.domain.repository

import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.model.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The long-term libsignal identity key pair. The private key never leaves
 * the data layer: callers get the public key and signatures only.
 */
interface IdentityRepository {
    suspend fun hasIdentity(): Boolean

    /** Creates and persists the identity on first call. Returns the serialized public key. */
    suspend fun getOrCreatePublicKey(): ByteArray

    /** Signs [message] with the identity private key. */
    suspend fun sign(message: ByteArray): ByteArray
}

interface AccountRepository {
    fun observeAccount(): Flow<Account?>

    suspend fun getAccount(): Account?

    /** Saves the profile locally. A non-null [avatar] is copied into private storage. */
    suspend fun saveProfile(displayName: String, avatar: AvatarSource?)

    suspend fun markRegistered(userId: UserId)
}

interface AuthRepository {
    val session: StateFlow<SessionState>

    /** Registers the identity key and display name. Safe to retry. */
    suspend fun register(displayName: String): AuthResult<UserId>

    /** Runs challenge-response for the registered account and holds the token in memory. */
    suspend fun authenticate(): AuthResult<Unit>
}

interface ConnectivityRepository {
    val isOnline: Flow<Boolean>
}
