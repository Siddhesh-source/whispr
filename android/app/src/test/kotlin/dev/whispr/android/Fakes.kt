package dev.whispr.android

import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.AuthRepository
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.repository.IdentityRepository
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

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

    var deleteResult: AuthResult<Unit> = AuthResult.Ok(Unit)
    var deleteCalls = 0
    override suspend fun deleteAccount(): AuthResult<Unit> {
        deleteCalls++
        return deleteResult
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
    var sendAllowed = true
    val replies = mutableListOf<String?>()
    override suspend fun sendText(peer: UserId, text: String, replyTo: String?): Boolean {
        replies += replyTo
        if (sendAllowed) sent += peer to text
        return sendAllowed
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

    val groupSent = mutableListOf<Pair<dev.whispr.domain.model.GroupId, String>>()
    val media = mutableListOf<dev.whispr.domain.model.MediaSource>()
    val reactions = mutableListOf<Pair<String, String?>>()
    var mediaResult: dev.whispr.domain.model.SendResult = dev.whispr.domain.model.SendResult.Ok
    val downloads = mutableListOf<String>()
    var bytes: ByteArray? = null

    override suspend fun sendGroupText(
        group: dev.whispr.domain.model.GroupId,
        text: String,
        replyTo: String?,
    ): Boolean {
        replies += replyTo
        if (sendAllowed) groupSent += group to text
        return sendAllowed
    }
    override suspend fun sendMedia(
        conversation: dev.whispr.domain.model.ConversationId,
        source: dev.whispr.domain.model.MediaSource,
    ) = mediaResult.also { media += source }
    override suspend fun react(
        conversation: dev.whispr.domain.model.ConversationId,
        messageId: String,
        emoji: String?,
    ) {
        reactions += messageId to emoji
    }
    override suspend fun download(conversation: dev.whispr.domain.model.ConversationId, messageId: String) {
        downloads += messageId
    }
    override suspend fun attachmentBytes(conversation: dev.whispr.domain.model.ConversationId, messageId: String) =
        bytes
    override suspend fun exportAttachment(
        conversation: dev.whispr.domain.model.ConversationId,
        messageId: String,
    ): String? = null

    val actions = mutableListOf<String>()
    val timer = MutableStateFlow(0L)
    var searchHits = emptyList<dev.whispr.domain.model.SearchHit>()
    override suspend fun forward(
        from: dev.whispr.domain.model.ConversationId,
        messageId: String,
        to: dev.whispr.domain.model.ConversationId,
    ) = dev.whispr.domain.model.SendResult.Ok.also { actions += "forward:$messageId:${to.value}" }
    override suspend fun deleteForMe(conversation: dev.whispr.domain.model.ConversationId, messageId: String) {
        actions += "deleteForMe:$messageId"
    }
    override suspend fun deleteForEveryone(conversation: dev.whispr.domain.model.ConversationId, messageId: String) =
        true.also { actions += "deleteForEveryone:$messageId" }
    override fun observeTimer(conversation: dev.whispr.domain.model.ConversationId) = timer
    override suspend fun setTimer(conversation: dev.whispr.domain.model.ConversationId, seconds: Long) =
        true.also { timer.value = seconds }
    override suspend fun search(query: String) = searchHits.also { actions += "search:$query" }
}

class FakeGroups : dev.whispr.domain.repository.GroupsRepository {
    val groups = MutableStateFlow<Map<dev.whispr.domain.model.GroupId, dev.whispr.domain.model.Group>>(emptyMap())
    val calls = mutableListOf<String>()
    var result: dev.whispr.domain.model.GroupResult = dev.whispr.domain.model.GroupResult.Ok(
        dev.whispr.domain.model.GroupId("11111111-1111-4111-8111-111111111111"),
    )

    override fun observeGroup(id: dev.whispr.domain.model.GroupId) = groups.map { it[id] }
    override suspend fun create(name: String, members: List<UserId>, avatar: AvatarSource?) =
        result.also { calls += "create:$name:${members.size}" }
    override suspend fun rename(id: dev.whispr.domain.model.GroupId, name: String) = result.also {
        calls +=
            "rename:$name"
    }
    override suspend fun setAvatar(id: dev.whispr.domain.model.GroupId, avatar: AvatarSource?) = result.also {
        calls +=
            "avatar"
    }
    override suspend fun addMembers(id: dev.whispr.domain.model.GroupId, members: List<UserId>) =
        result.also { calls += "add:${members.size}" }
    override suspend fun removeMember(id: dev.whispr.domain.model.GroupId, member: UserId) =
        result.also { calls += "remove:${member.value}" }
    override suspend fun setRole(
        id: dev.whispr.domain.model.GroupId,
        member: UserId,
        role: dev.whispr.domain.model.GroupRole,
    ) = result.also { calls += "role:${member.value}:$role" }
    override suspend fun invite(id: dev.whispr.domain.model.GroupId, members: List<UserId>) =
        result.also { calls += "invite:${members.size}" }
    override suspend fun acceptInvite(id: dev.whispr.domain.model.GroupId) = result.also { calls += "accept" }
    override suspend fun declineInvite(id: dev.whispr.domain.model.GroupId) = result.also { calls += "decline" }
    override suspend fun leave(id: dev.whispr.domain.model.GroupId) = result.also { calls += "leave" }
}

class FakeContacts : dev.whispr.domain.repository.ContactsRepository {
    val contacts = MutableStateFlow<List<dev.whispr.domain.model.Contact>>(emptyList())
    var result: dev.whispr.domain.model.AddContactResult = dev.whispr.domain.model.AddContactResult.NotFound
    var verifyResult = dev.whispr.domain.model.VerifyResult.Match
    val calls = mutableListOf<String>()
    var safety: dev.whispr.domain.model.SafetyNumber? = dev.whispr.domain.model.SafetyNumber(
        "1".repeat(60),
        "whispr-sn:AAAA",
    )

    override fun observeContacts() = contacts
    override fun observeContact(userId: UserId) = contacts.map { list -> list.firstOrNull { it.userId == userId } }
    override suspend fun contact(userId: UserId) = contacts.value.firstOrNull { it.userId == userId }
    override suspend fun myContactCode() = "whispr:MYCODE"
    override suspend fun addFromCode(code: String) = result.also { calls += "code:$code" }
    override suspend fun addByUsername(username: String) = result.also { calls += "username:$username" }
    override suspend fun addById(rawUserId: String) = result
    override suspend fun acceptRequest(userId: UserId) = update(userId) { it.copy(isRequest = false) }.also {
        calls +=
            "accept"
    }
    override suspend fun declineRequest(userId: UserId) {
        calls += "decline"
        contacts.value = contacts.value.filterNot { it.userId == userId }
    }
    override suspend fun refreshKey(userId: UserId) {
        calls += "refresh"
    }
    override suspend fun acknowledgeKeyChange(userId: UserId) =
        update(userId) { it.copy(trust = TrustState.Unverified) }.also { calls += "ack" }
    override suspend fun safetyNumber(userId: UserId) = safety
    override suspend fun verifyScanned(userId: UserId, scanned: String) =
        verifyResult.also { calls += "verify:$scanned" }
    override suspend fun setVerified(userId: UserId, verified: Boolean) = update(userId) {
        it.copy(
            trust = if (verified) TrustState.Verified else TrustState.Unverified,
        )
    }

    private fun update(userId: UserId, f: (dev.whispr.domain.model.Contact) -> dev.whispr.domain.model.Contact) {
        contacts.value = contacts.value.map { if (it.userId == userId) f(it) else it }
    }
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
    override suspend fun setScreenSecurity(enabled: Boolean) {
        privacy.value = privacy.value.copy(screenSecurity = enabled)
    }
    override suspend fun setRelayCalls(enabled: Boolean) {
        privacy.value = privacy.value.copy(relayCalls = enabled)
    }
}

class FakeEncryption : dev.whispr.domain.repository.EncryptionRepository {
    val registered = MutableStateFlow(true)
    override fun observeKeysRegistered() = registered
}
