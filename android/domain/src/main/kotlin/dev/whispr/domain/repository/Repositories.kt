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
import dev.whispr.domain.model.MyProfile
import dev.whispr.domain.model.PrivacySettings
import dev.whispr.domain.model.ProfileResult
import dev.whispr.domain.model.SafetyNumber
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.model.VerifyResult
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

    fun observeContact(userId: UserId): Flow<Contact?>

    suspend fun contact(userId: UserId): Contact?

    /** Our own contact code, to show as a QR. */
    suspend fun myContactCode(): String

    /** Parses a scanned code (hostile input), pins the key, and sends them a contact request. */
    suspend fun addFromCode(code: String): AddContactResult

    /** Looks up an exact username (e.g. "sam.42"); pins the server-reported key. */
    suspend fun addByUsername(username: String): AddContactResult

    /** Development and test helper: add by raw account ID. Not exposed in the UI. */
    suspend fun addById(rawUserId: String): AddContactResult

    suspend fun acceptRequest(userId: UserId)

    /** Removes the requester and anything they sent. */
    suspend fun declineRequest(userId: UserId)

    /** Re-reads the contact's key from the server; a difference marks the contact KeyChanged. */
    suspend fun refreshKey(userId: UserId)

    /** User saw the warning: pin the new key, back to Unverified. */
    suspend fun acknowledgeKeyChange(userId: UserId)

    suspend fun safetyNumber(userId: UserId): SafetyNumber?

    /** Compares a scanned safety-number code; marks Verified on a match. */
    suspend fun verifyScanned(userId: UserId, scanned: String): VerifyResult

    suspend fun setVerified(userId: UserId, verified: Boolean)
}

interface ProfileRepository {
    fun observeProfile(): Flow<MyProfile?>

    suspend fun setDisplayName(name: String): ProfileResult

    suspend fun setAvatar(avatar: AvatarSource?)

    /** Claims nickname plus a server-assigned number, e.g. "sam" -> "sam.42". */
    suspend fun claimUsername(nickname: String): ProfileResult

    suspend fun clearUsername(): ProfileResult
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

    /**
     * Stores the message as Sending and queues it; returns immediately.
     * Returns false (and stores nothing) if the contact's key changed and
     * the user has not acknowledged it.
     */
    suspend fun sendText(peer: UserId, text: String): Boolean

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
