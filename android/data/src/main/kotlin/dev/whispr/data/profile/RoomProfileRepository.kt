package dev.whispr.data.profile

import dev.whispr.data.account.AvatarImporter
import dev.whispr.data.db.AccountDao
import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.WhisprApi
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.MyProfile
import dev.whispr.domain.model.ProfileResult
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.ProfileRepository
import dev.whispr.domain.usecase.DisplayNameValidation
import dev.whispr.domain.usecase.DisplayNameValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Display name and username live on the server (contacts and lookups need
 * them) and are mirrored locally. The profile picture never leaves the
 * device until encrypted profiles exist.
 */
class RoomProfileRepository(
    private val dao: AccountDao,
    private val api: WhisprApi,
    private val avatars: AvatarImporter,
    private val io: CoroutineDispatcher,
) : ProfileRepository {

    override fun observeProfile(): Flow<MyProfile?> = dao.observe().map { a ->
        a?.userId?.let { MyProfile(UserId(it), a.displayName, a.username, a.avatarPath) }
    }

    override suspend fun setDisplayName(name: String): ProfileResult {
        val valid =
            DisplayNameValidator.validate(name) as? DisplayNameValidation.Valid ?: return ProfileResult.InvalidInput
        return when (val r = api.putDisplayName(valid.name)) {
            is ApiResult.Success -> {
                dao.setDisplayName(valid.name)
                ProfileResult.Ok
            }
            else -> r.toResult()
        }
    }

    override suspend fun setAvatar(avatar: AvatarSource?) {
        val path = avatar?.let { withContext(io) { avatars.import(it) } }
        dao.setAvatarPath(path)
    }

    override suspend fun claimUsername(nickname: String): ProfileResult {
        val nick = nickname.trim().lowercase()
        if (!NICKNAME.matches(nick)) return ProfileResult.InvalidInput
        return when (val r = api.putUsername(nick)) {
            is ApiResult.Success -> {
                dao.setUsername(r.body.username)
                ProfileResult.Ok
            }
            else -> r.toResult()
        }
    }

    override suspend fun clearUsername(): ProfileResult = when (val r = api.deleteUsername()) {
        is ApiResult.Success -> {
            dao.setUsername(null)
            ProfileResult.Ok
        }
        else -> r.toResult()
    }

    private fun ApiResult<*>.toResult(): ProfileResult = when (this) {
        is ApiResult.HttpError -> when (code) {
            HTTP_BAD_REQUEST -> ProfileResult.InvalidInput
            HTTP_CONFLICT -> ProfileResult.Unavailable
            else -> ProfileResult.Failed(AuthError.Server)
        }
        ApiResult.NetworkError -> ProfileResult.Failed(AuthError.Network)
        is ApiResult.Success -> ProfileResult.Ok
    }

    companion object {
        /** Mirrors the server: 3–32 of a–z, 0–9, _, starting with a letter. */
        val NICKNAME = Regex("^[a-z][a-z0-9_]{2,31}$")
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_CONFLICT = 409
    }
}
