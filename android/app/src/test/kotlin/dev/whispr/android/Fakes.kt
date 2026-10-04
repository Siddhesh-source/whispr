package dev.whispr.android

import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.AuthRepository
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.repository.IdentityRepository
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeIdentity : IdentityRepository {
    override suspend fun hasIdentity() = true
    override suspend fun getOrCreatePublicKey() = ByteArray(33)
    override suspend fun sign(message: ByteArray) = ByteArray(64)
}

class FakeAccounts(initial: Account? = null) : AccountRepository {
    val state = MutableStateFlow(initial)
    override fun observeAccount(): Flow<Account?> = state
    override suspend fun getAccount() = state.value
    override suspend fun saveProfile(displayName: String, avatar: AvatarSource?) {
        state.value = Account(state.value?.userId, displayName, avatar?.uri)
    }
    override suspend fun markRegistered(userId: UserId) {
        state.value = state.value?.copy(userId = userId)
    }
}

/** Mirrors SessionAuthRepository's state transitions, including the suspension point. */
class FakeAuth : AuthRepository {
    override val session = MutableStateFlow<SessionState>(SessionState.Idle)
    var registerResult: AuthResult<UserId> = AuthResult.Ok(UserId("00000000-0000-0000-0000-000000000001"))
    var authenticateResult: AuthResult<Unit> = AuthResult.Ok(Unit)
    var authenticateCalls = 0
    var completedAuthentications = 0

    override suspend fun register(displayName: String) = registerResult

    override suspend fun authenticate(): AuthResult<Unit> {
        authenticateCalls++
        if (session.value is SessionState.Active) return AuthResult.Ok(Unit)
        session.value = SessionState.Authenticating
        delay(100) // network round trip
        val r = authenticateResult
        session.value = when (r) {
            is AuthResult.Ok -> SessionState.Active(Instant.MAX)
            is AuthResult.Err -> SessionState.Unavailable(r.error)
        }
        completedAuthentications++
        return r
    }
}

class FakeConnectivity(online: Boolean = true) : ConnectivityRepository {
    val online = MutableStateFlow(online)
    override val isOnline: Flow<Boolean> = this.online
}

val registered = Account(UserId("00000000-0000-0000-0000-000000000001"), "Ada", null)

class FakeMessaging : dev.whispr.domain.repository.MessagingRepository {
    override val connection = MutableStateFlow(dev.whispr.domain.model.ConnectionState.Connected)
    val conversations = MutableStateFlow<List<dev.whispr.domain.model.ConversationSummary>>(emptyList())
    val messages = MutableStateFlow<List<dev.whispr.domain.model.Message>>(emptyList())
    val typing = MutableStateFlow(false)
    val sent = mutableListOf<Pair<UserId, String>>()
    val retried = mutableListOf<String>()
    var markedRead = 0
    var typingCalls = 0

    override fun observeConversations() = conversations
    override fun observeMessages(conversation: dev.whispr.domain.model.ConversationId) = messages
    override suspend fun sendText(peer: UserId, text: String) {
        sent += peer to text
    }
    override suspend fun retry(messageId: String) {
        retried += messageId
    }
    override suspend fun markRead(conversation: dev.whispr.domain.model.ConversationId) {
        markedRead++
    }
    override suspend fun onTyping(peer: UserId) {
        typingCalls++
    }
    override fun observePeerTyping(conversation: dev.whispr.domain.model.ConversationId) = typing
}

class FakeContacts : dev.whispr.domain.repository.ContactsRepository {
    val contacts = MutableStateFlow<List<dev.whispr.domain.model.Contact>>(emptyList())
    var result: dev.whispr.domain.model.AddContactResult = dev.whispr.domain.model.AddContactResult.NotFound
    override fun observeContacts() = contacts
    override suspend fun contact(userId: UserId) = contacts.value.firstOrNull { it.userId == userId }
    override suspend fun addById(rawUserId: String) = result
}

class FakeSettings : dev.whispr.domain.repository.SettingsRepository {
    val privacy = MutableStateFlow(dev.whispr.domain.model.PrivacySettings())
    override fun observePrivacy() = privacy
    override suspend fun setReadReceipts(enabled: Boolean) {
        privacy.value = privacy.value.copy(readReceipts = enabled)
    }
    override suspend fun setTypingIndicators(enabled: Boolean) {
        privacy.value = privacy.value.copy(typingIndicators = enabled)
    }
}
