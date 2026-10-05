package dev.whispr.data.contacts

import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.messaging.Payload
import dev.whispr.data.messaging.PayloadCodec
import dev.whispr.data.messaging.toDomain
import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.UserResponse
import dev.whispr.data.network.WhisprApi
import dev.whispr.domain.model.AddContactResult
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.SafetyNumber
import dev.whispr.domain.model.TrustState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.model.VerifyResult
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ContactsRepository
import dev.whispr.domain.repository.IdentityRepository
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Contacts with pinned identity keys.
 *
 * - Scanning a code pins the key from the code, after checking the server
 *   reports the same key (a mismatch means a lying server: nothing is added).
 * - A key that differs from the pinned one is never accepted silently: the
 *   contact is flagged KeyChanged, the new key is held aside, and the user
 *   must acknowledge before sending again.
 * - Verified only comes from comparing safety numbers.
 */
class RoomContactsRepository(
    private val db: WhisprDatabase,
    private val api: WhisprApi,
    private val accounts: AccountRepository,
    private val identity: IdentityRepository,
    /** Debug builds talk to a loopback server over HTTP. */
    private val allowInsecureLoopback: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Resumes encrypted messaging with the contact (unpark, release held messages). */
    private val onKeyAcknowledged: suspend (UserId) -> Unit = {},
) : ContactsRepository {

    private val dao get() = db.contactDao()

    override fun observeContacts(): Flow<List<Contact>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeContact(userId: UserId): Flow<Contact?> = dao.observe(userId.value).map { it?.toDomain() }

    override suspend fun contact(userId: UserId): Contact? = dao.get(userId.value)?.toDomain()

    override suspend fun myContactCode(): String =
        ContactQr.encode(ContactCard(UUID.fromString(me().value), identity.getOrCreatePublicKey(), api.origin))

    override suspend fun addFromCode(code: String): AddContactResult {
        val card = when (val parsed = ContactQr.parse(code, api.origin, allowInsecureLoopback)) {
            is QrParse.Ok -> parsed.card
            is QrParse.Err -> return if (parsed.error ==
                QrError.DifferentServer
            ) {
                AddContactResult.DifferentServer
            } else {
                AddContactResult.InvalidCode
            }
        }
        val id = card.userId.toString()
        if (id.equals(me().value, ignoreCase = true)) return AddContactResult.IsSelf
        val profile = when (val r = api.lookupUser(id)) {
            is ApiResult.Success -> r.body
            else -> return r.toFailure()
        }
        // The code is the out-of-band truth. If the server disagrees, it is
        // lying or compromised: refuse rather than pin either key.
        if (!decodeKey(profile.identityKey).contentEquals(card.identityKey)) return AddContactResult.KeyMismatch
        return pinAndRequest(id, profile.displayName, card.identityKey)
    }

    override suspend fun addByUsername(username: String): AddContactResult {
        val handle = username.trim().lowercase()
        if (!USERNAME.matches(handle)) return AddContactResult.InvalidId
        val profile = when (val r = api.lookupUsername(handle)) {
            is ApiResult.Success -> r.body
            else -> return r.toFailure()
        }
        return addFromProfile(profile)
    }

    override suspend fun addById(rawUserId: String): AddContactResult {
        val id =
            runCatching { UUID.fromString(rawUserId.trim()) }.getOrNull()?.toString()
                ?: return AddContactResult.InvalidId
        return when (val r = api.lookupUser(id)) {
            is ApiResult.Success -> addFromProfile(r.body)
            else -> r.toFailure()
        }
    }

    private suspend fun addFromProfile(profile: UserResponse): AddContactResult {
        if (profile.userId.equals(me().value, ignoreCase = true)) return AddContactResult.IsSelf
        val key = decodeKey(profile.identityKey)
        if (key.isEmpty()) return AddContactResult.Failed(AuthError.Server)
        // Trust on first use: verify safety numbers to upgrade.
        return pinAndRequest(profile.userId, profile.displayName, key)
    }

    /** Pins [key] for a new contact (or confirms an existing pin) and sends them a contact request. */
    private suspend fun pinAndRequest(userId: String, name: String, key: ByteArray): AddContactResult {
        val existing = dao.get(userId)
        if (existing != null && existing.identityKey.isNotEmpty() && !existing.identityKey.contentEquals(key)) {
            dao.flagKeyChange(userId, key)
            return AddContactResult.KeyMismatch
        }
        val entity = (existing ?: ContactEntity(userId, name, key, clock())).copy(identityKey = key, isRequest = false)
        dao.upsert(entity)
        if (existing == null || existing.isRequest) sendContactRequest(UserId(userId))
        return AddContactResult.Added(entity.toDomain())
    }

    private suspend fun sendContactRequest(peer: UserId) {
        val account = accounts.getAccount() ?: return
        val me = account.userId ?: return
        val payload = Payload.ContactRequest(
            account.displayName,
            Base64.getEncoder().encodeToString(identity.getOrCreatePublicKey()),
        )
        // Through the outbox, so a request made offline is still delivered.
        db.outboxDao().enqueue(
            OutboxEntity(
                messageId = UUID.randomUUID().toString(),
                conversationId = ConversationId.direct(me, peer).value,
                recipientId = peer.value,
                payload = PayloadCodec.encode(payload),
                clientTs = clock(),
            ),
        )
    }

    override suspend fun acceptRequest(userId: UserId) = dao.accept(userId.value)

    override suspend fun declineRequest(userId: UserId) {
        db.messageDao().deleteFrom(userId.value)
        dao.delete(userId.value)
    }

    override suspend fun refreshKey(userId: UserId) {
        val contact = dao.get(userId.value) ?: return
        val profile = (api.lookupUser(userId.value) as? ApiResult.Success)?.body ?: return
        val serverKey = decodeKey(profile.identityKey)
        if (serverKey.isEmpty()) return
        val alreadyPending = contact.pendingKey?.contentEquals(serverKey) == true
        val differsFromPinned = !contact.identityKey.contentEquals(serverKey)
        when {
            contact.identityKey.isEmpty() -> dao.upsert(contact.copy(identityKey = serverKey))
            // A new difference from the pinned key: flag it.
            differsFromPinned && !alreadyPending -> dao.flagKeyChange(userId.value, serverKey)
            // Already flagged and the server now reports yet another key (even the
            // original one): track the latest, so accepting never pins a stale key.
            // The flag stays: a flip-flopping key is itself suspicious.
            contact.trust == TrustState.KeyChanged.name && !alreadyPending -> dao.flagKeyChange(userId.value, serverKey)
        }
        if (profile.displayName != contact.displayName) dao.setName(userId.value, profile.displayName)
    }

    override suspend fun acknowledgeKeyChange(userId: UserId) {
        dao.acceptPendingKey(userId.value)
        onKeyAcknowledged(userId)
    }

    override suspend fun safetyNumber(userId: UserId): SafetyNumber? {
        val contact =
            dao.get(userId.value)?.takeIf { it.identityKey.isNotEmpty() && it.trust != TrustState.KeyChanged.name }
                ?: return null
        return SafetyNumbers.compute(
            UUID.fromString(me().value),
            identity.getOrCreatePublicKey(),
            UUID.fromString(contact.userId),
            contact.identityKey,
        )
    }

    override suspend fun verifyScanned(userId: UserId, scanned: String): VerifyResult {
        val contact =
            dao.get(userId.value)?.takeIf { it.identityKey.isNotEmpty() && it.trust != TrustState.KeyChanged.name }
                ?: return VerifyResult.InvalidCode
        val result = SafetyNumbers.compare(
            UUID.fromString(me().value),
            identity.getOrCreatePublicKey(),
            UUID.fromString(contact.userId),
            contact.identityKey,
            scanned,
        )
        if (result == VerifyResult.Match) dao.setTrust(userId.value, TrustState.Verified.name)
        return result
    }

    override suspend fun setVerified(userId: UserId, verified: Boolean) {
        val contact = dao.get(userId.value) ?: return
        if (contact.trust == TrustState.KeyChanged.name) return // acknowledge first
        dao.setTrust(userId.value, if (verified) TrustState.Verified.name else TrustState.Unverified.name)
    }

    private suspend fun me(): UserId = accounts.getAccount()?.userId ?: error("not registered")

    private fun decodeKey(b64: String): ByteArray = runCatching {
        Base64.getDecoder().decode(b64)
    }.getOrDefault(ByteArray(0))

    private fun ApiResult<*>.toFailure(): AddContactResult = when (this) {
        is ApiResult.HttpError -> when (code) {
            HTTP_NOT_FOUND -> AddContactResult.NotFound
            HTTP_BAD_REQUEST -> AddContactResult.InvalidId
            HTTP_TOO_MANY -> AddContactResult.Failed(AuthError.Network)
            else -> AddContactResult.Failed(AuthError.Server)
        }
        ApiResult.NetworkError -> AddContactResult.Failed(AuthError.Network)
        is ApiResult.Success -> AddContactResult.Failed(AuthError.Server)
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_TOO_MANY = 429
        val USERNAME = Regex("^[a-z][a-z0-9_]{2,31}\\.[0-9]{2,3}$")
    }
}
