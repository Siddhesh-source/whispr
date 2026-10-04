package dev.whispr.data.messaging

import dev.whispr.data.db.ContactDao
import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.SettingDao
import dev.whispr.data.db.SettingEntity
import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.WhisprApi
import dev.whispr.domain.model.AddContactResult
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.Contact
import dev.whispr.domain.model.PrivacySettings
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.ContactsRepository
import dev.whispr.domain.repository.SettingsRepository
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Temporary Phase 2 contact adding by pasted user ID. Phase 3 replaces this
 * with QR codes that carry the identity key for out-of-band verification;
 * until then the key is whatever the server reports.
 */
class RoomContactsRepository(
    private val dao: ContactDao,
    private val api: WhisprApi,
    private val accounts: AccountRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ContactsRepository {

    override fun observeContacts(): Flow<List<Contact>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun contact(userId: UserId): Contact? = dao.get(userId.value)?.toDomain()

    override suspend fun addById(rawUserId: String): AddContactResult {
        val id = runCatching { UUID.fromString(rawUserId.trim()) }.getOrNull()?.toString()
            ?: return AddContactResult.InvalidId
        if (accounts.getAccount()?.userId?.value.equals(id, ignoreCase = true)) return AddContactResult.IsSelf
        return when (val r = api.lookupUser(id)) {
            is ApiResult.Success -> {
                val key = runCatching { Base64.getDecoder().decode(r.body.identityKey) }.getOrNull()
                    ?: return AddContactResult.Failed(AuthError.Server)
                val entity = ContactEntity(r.body.userId, r.body.displayName, key, clock())
                dao.upsert(entity)
                AddContactResult.Added(entity.toDomain())
            }
            is ApiResult.HttpError -> when (r.code) {
                HTTP_NOT_FOUND -> AddContactResult.NotFound
                HTTP_BAD_REQUEST -> AddContactResult.InvalidId
                else -> AddContactResult.Failed(AuthError.Server)
            }
            ApiResult.NetworkError -> AddContactResult.Failed(AuthError.Network)
        }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_BAD_REQUEST = 400
    }
}

/** Privacy toggles, stored in the encrypted database. Both default to off. */
class RoomSettingsRepository(private val dao: SettingDao) : SettingsRepository {
    override fun observePrivacy(): Flow<PrivacySettings> = dao.observeAll().map { rows ->
        val map = rows.associate { it.key to it.value }
        PrivacySettings(
            readReceipts = map[READ_RECEIPTS] == "true",
            typingIndicators = map[TYPING] == "true",
        )
    }

    override suspend fun setReadReceipts(enabled: Boolean) = dao.put(SettingEntity(READ_RECEIPTS, enabled.toString()))

    override suspend fun setTypingIndicators(enabled: Boolean) = dao.put(SettingEntity(TYPING, enabled.toString()))

    private companion object {
        const val READ_RECEIPTS = "privacy.read_receipts"
        const val TYPING = "privacy.typing_indicators"
    }
}
