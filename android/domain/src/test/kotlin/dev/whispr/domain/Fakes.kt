package dev.whispr.domain

import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.AuthRepository
import dev.whispr.domain.repository.IdentityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeIdentityRepository : IdentityRepository {
    var key: ByteArray? = null
    var failCreate = false
    var createCalls = 0

    override suspend fun hasIdentity() = key != null

    override suspend fun getOrCreatePublicKey(): ByteArray {
        createCalls++
        if (failCreate) error("keystore unavailable")
        return key ?: ByteArray(33) { 5 }.also { key = it }
    }

    override suspend fun sign(message: ByteArray) = message.reversedArray()
}

class FakeAccountRepository : AccountRepository {
    val state = MutableStateFlow<Account?>(null)

    override fun observeAccount(): Flow<Account?> = state

    override suspend fun getAccount() = state.value

    override suspend fun saveProfile(displayName: String, avatar: AvatarSource?) {
        state.value = Account(state.value?.userId, displayName, avatar?.uri)
    }

    override suspend fun markRegistered(userId: UserId) {
        state.value = state.value!!.copy(userId = userId)
    }
}

class FakeAuthRepository : AuthRepository {
    override val session: StateFlow<SessionState> = MutableStateFlow(SessionState.Idle)
    var registerResult: AuthResult<UserId> = AuthResult.Ok(UserId("u-1"))
    var authenticateResult: AuthResult<Unit> = AuthResult.Ok(Unit)
    val registeredNames = mutableListOf<String>()
    var authenticateCalls = 0

    override suspend fun register(displayName: String): AuthResult<UserId> {
        registeredNames += displayName
        return registerResult
    }

    override suspend fun authenticate(): AuthResult<Unit> {
        authenticateCalls++
        return authenticateResult
    }
}

val networkError = AuthResult.Err(AuthError.Network)
