package dev.whispr.domain.repository

import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AddContactResult
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.ConnectionState
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.ConversationSummary
import dev.whispr.domain.model.Group
import dev.whispr.domain.model.GroupId
import dev.whispr.domain.model.GroupResult
import dev.whispr.domain.model.GroupRole
import dev.whispr.domain.model.MediaSource
import dev.whispr.domain.model.Message
import dev.whispr.domain.model.MyProfile
import dev.whispr.domain.model.PrivacySettings
import dev.whispr.domain.model.ProfileResult
import dev.whispr.domain.model.SafetyNumber
import dev.whispr.domain.model.SearchHit
import dev.whispr.domain.model.SendResult
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

    /**
     * Deletes the account on the server (proving possession of the identity
     * key with a fresh signed challenge), then wipes everything on this
     * device. Irreversible. On success the app must restart.
     */
    suspend fun deleteAccount(): AuthResult<Unit>
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
    suspend fun sendText(peer: UserId, text: String, replyTo: String? = null): Boolean

    /**
     * Sends to every current group member, encrypted once with our sender key. False if we can't send there.
     * [replyTo] quotes a message in the same conversation.
     */
    suspend fun sendGroupText(group: GroupId, text: String, replyTo: String? = null): Boolean

    /**
     * Sends a copy of a message to another conversation, marked as forwarded.
     * Media is re-encrypted with a fresh key and uploaded again, so the
     * server cannot link the copy to the original. Media must be downloaded.
     */
    suspend fun forward(from: ConversationId, messageId: String, to: ConversationId): SendResult

    /** Removes a message from this device only. */
    suspend fun deleteForMe(conversation: ConversationId, messageId: String)

    /** Deletes our own message for everyone (within [MessageRules.DELETE_FOR_EVERYONE_WINDOW]). */
    suspend fun deleteForEveryone(conversation: ConversationId, messageId: String): Boolean

    /** The conversation's disappearing-message timer in seconds (0 = off). */
    fun observeTimer(conversation: ConversationId): Flow<Long>

    /** Sets the timer for everyone in the conversation. False if we can't send there. */
    suspend fun setTimer(conversation: ConversationId, seconds: Long): Boolean

    /** Searches message text on this device (case-insensitive for ASCII), newest first. */
    suspend fun search(query: String): List<SearchHit>

    /**
     * Encrypts [source] on the device with a fresh key, uploads only the
     * ciphertext, and sends the key and digest inside an encrypted message.
     * [conversation] is a 1:1 or group conversation.
     */
    suspend fun sendMedia(conversation: ConversationId, source: MediaSource): SendResult

    /** Sets (or with null removes) our reaction to [messageId]. */
    suspend fun react(conversation: ConversationId, messageId: String, emoji: String?)

    /** Downloads, verifies and stores a received attachment (it stays encrypted at rest). */
    suspend fun download(conversation: ConversationId, messageId: String)

    /** The decrypted bytes of a stored attachment, for display; null if not available. */
    suspend fun attachmentBytes(conversation: ConversationId, messageId: String): ByteArray?

    /**
     * Writes a decrypted copy to a private cache file (deleted on next start)
     * so another app can open it; returns its path.
     */
    suspend fun exportAttachment(conversation: ConversationId, messageId: String): String?

    /** Re-queues a message that failed permanently (user tapped retry). */
    suspend fun retry(messageId: String)

    /** Marks incoming messages read; sends a read receipt if enabled. */
    suspend fun markRead(conversation: ConversationId)

    /** Called while the user types; sends throttled typing signals if enabled. */
    suspend fun onTyping(peer: UserId)

    fun observePeerTyping(conversation: ConversationId): Flow<Boolean>
}

/**
 * Groups are kept by the members' devices: state changes are sent by admins
 * over pairwise encrypted sessions, and the server only fans out ciphertext.
 */
interface GroupsRepository {
    fun observeGroup(id: GroupId): Flow<Group?>

    /** Creates a group with us as admin and [members] (contacts). */
    suspend fun create(name: String, members: List<UserId>, avatar: AvatarSource? = null): GroupResult

    suspend fun rename(id: GroupId, name: String): GroupResult

    /** Sets (or with null clears) the group picture: scaled down, metadata stripped, encrypted. */
    suspend fun setAvatar(id: GroupId, avatar: AvatarSource?): GroupResult

    suspend fun addMembers(id: GroupId, members: List<UserId>): GroupResult

    /** Removes [member]; every remaining member rotates their sender key. */
    suspend fun removeMember(id: GroupId, member: UserId): GroupResult

    suspend fun setRole(id: GroupId, member: UserId, role: GroupRole): GroupResult

    /** Invites [members]: they join only if they accept. */
    suspend fun invite(id: GroupId, members: List<UserId>): GroupResult

    suspend fun acceptInvite(id: GroupId): GroupResult

    suspend fun declineInvite(id: GroupId): GroupResult

    /** Leaves the group. A sole admin first hands the role to the longest-standing member. */
    suspend fun leave(id: GroupId): GroupResult
}

interface SettingsRepository {
    fun observePrivacy(): Flow<PrivacySettings>

    suspend fun setReadReceipts(enabled: Boolean)

    suspend fun setTypingIndicators(enabled: Boolean)

    suspend fun setScreenSecurity(enabled: Boolean)
}

interface EncryptionRepository {
    /** False while this device's encryption keys could not be uploaded to the server. */
    fun observeKeysRegistered(): Flow<Boolean>
}
