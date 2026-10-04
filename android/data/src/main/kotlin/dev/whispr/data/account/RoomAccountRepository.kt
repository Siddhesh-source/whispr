package dev.whispr.data.account

import android.net.Uri
import dev.whispr.data.db.AccountDao
import dev.whispr.data.db.AccountEntity
import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomAccountRepository(
    private val dao: AccountDao,
    private val avatars: AvatarImporter,
    private val io: CoroutineDispatcher,
) : AccountRepository {

    override fun observeAccount(): Flow<Account?> = dao.observe().map { it?.toDomain() }

    override suspend fun getAccount(): Account? = dao.get()?.toDomain()

    override suspend fun saveProfile(displayName: String, avatar: AvatarSource?) {
        val existing = dao.get()
        val avatarPath = if (avatar != null) withContext(io) { avatars.import(avatar) } else existing?.avatarPath
        dao.upsert(AccountEntity(userId = existing?.userId, displayName = displayName, avatarPath = avatarPath))
    }

    override suspend fun markRegistered(userId: UserId) {
        check(dao.setUserId(userId.value) == 1) { "no profile to register" }
    }

    private fun AccountEntity.toDomain() = Account(userId?.let(::UserId), displayName, avatarPath)
}

/** Seam over [AvatarStore] so the repository can be tested without images. */
fun interface AvatarImporter {
    fun import(source: AvatarSource): String
}

fun AvatarStore.asImporter() = AvatarImporter { import(Uri.parse(it.uri)) }
