package dev.whispr.data

import dev.whispr.data.auth.SessionAuthRepository
import dev.whispr.data.crypto.SecretFileStore
import dev.whispr.data.identity.LibsignalIdentityRepository
import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.AuthApi
import dev.whispr.data.network.ServerConfig
import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end against the real Go server with real libsignal on both sides.
 * Runs only when WHISPR_SERVER_URL is set, e.g.:
 *   docker compose up -d && WHISPR_SERVER_URL=http://127.0.0.1:8080/ ./gradlew :data:testDebugUnitTest
 */
class LiveServerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun registerAuthenticateAndCallAuthenticatedEndpoint() = runBlocking {
        val url = System.getenv("WHISPR_SERVER_URL")
        assumeTrue("WHISPR_SERVER_URL not set", !url.isNullOrBlank())

        val accounts = object : AccountRepository {
            val state = MutableStateFlow<Account?>(null)
            override fun observeAccount() = state
            override suspend fun getAccount() = state.value
            override suspend fun saveProfile(displayName: String, avatar: AvatarSource?) = Unit
            override suspend fun markRegistered(userId: UserId) = Unit
        }
        val wrapper = SoftwareKeyWrapper()
        val identity = LibsignalIdentityRepository(SecretFileStore(tmp.root, wrapper), Dispatchers.IO)
        val api = AuthApi(OkHttpClient(), ServerConfig(url!!))
        val repo = SessionAuthRepository(api, identity, accounts)

        val userId = (repo.register("Live Test") as AuthResult.Ok).value
        accounts.state.value = Account(userId, "Live Test", null)
        assertEquals(AuthResult.Ok(Unit), repo.authenticate())

        val me = api.me(repo.bearerToken()!!)
        assertEquals(userId.value, (me as ApiResult.Success).body.userId)

        // Simulated restart: fresh repository, same stored identity, no token.
        val restarted = SessionAuthRepository(
            api,
            LibsignalIdentityRepository(SecretFileStore(tmp.root, wrapper), Dispatchers.IO),
            accounts,
        )
        assertEquals(AuthResult.Ok(Unit), restarted.authenticate())
    }
}
