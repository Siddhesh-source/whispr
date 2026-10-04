package dev.whispr.domain.repository

import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AddContactResult
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.ConnectionState
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.ConversationSummary
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.PrivacySettings
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

interface ContactsRepository {
    fun observeContacts(): Flow<List<Contact>>

    suspend fun contact(userId: UserId): Contact?

    /** Looks the user up on the server and stores them locally. */
    suspend fun addById(rawUserId: String): AddContactResult
}

/**
 * The local database is the single source of truth: UI observes it, sending
 * writes to it (and the outbox), and the network layer reconciles in the
 * background.
 */
interface MessagingRepository {
    val connection: StateFlow<ConnectionState>

    fun observeConversations(): Flow<List<ConversationSummary>>

    fun observeMessages(conversation: ConversationId): Flow<List<Message>>

    /** Stores the message as Sending and queues it; returns immediately. */
    suspend fun sendText(peer: UserId, text: String)

    /** Re-queues a message that failed permanently (user tapped retry). */
    suspend fun retry(messageId: String)

    /** Marks incoming messages read; sends a read receipt if enabled. */
    suspend fun markRead(conversation: ConversationId)

    /** Called while the user types; sends throttled typing signals if enabled. */
    suspend fun onTyping(peer: UserId)

    fun observePeerTyping(conversation: ConversationId): Flow<Boolean>
}

interface SettingsRepository {
    fun observePrivacy(): Flow<PrivacySettings>

    suspend fun setReadReceipts(enabled: Boolean)

    suspend fun setTypingIndicators(enabled: Boolean)
}
