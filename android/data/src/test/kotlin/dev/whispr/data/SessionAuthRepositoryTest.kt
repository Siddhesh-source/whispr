package dev.whispr.data

import dev.whispr.data.auth.AuthMessages
import dev.whispr.data.auth.SessionAuthRepository
import dev.whispr.data.crypto.SecretFileStore
import dev.whispr.data.identity.LibsignalIdentityRepository
import dev.whispr.data.network.AuthApi
import dev.whispr.data.network.ServerConfig
import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AuthError
import dev.whispr.domain.model.AuthResult
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.SessionState
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.libsignal.protocol.IdentityKey

class SessionAuthRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private var now = Instant.parse("2026-01-01T12:00:00Z")
    private val fake = FakeAuthServer { now }
    private val accounts = MemoryAccounts()
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private lateinit var repo: SessionAuthRepository

    @Before
    fun setUp() {
        server.dispatcher = fake
        server.start()
        val identity =
            LibsignalIdentityRepository(SecretFileStore(tmp.root, SoftwareKeyWrapper()), Dispatchers.Unconfined)
        val api = AuthApi(OkHttpClient(), ServerConfig(server.url("/").toString()))
        repo = SessionAuthRepository(api, identity, accounts, clock)
    }

    @After
    fun tearDown() = server.close()

    private suspend fun registerAndStore(name: String = "Ada"): UserId {
        val r = repo.register(name)
        val id = (r as AuthResult.Ok).value
        accounts.state.value = Account(id, name, null)
        return id
    }

    @Test
    fun registerSendsVerifiableSignature() = runTest {
        val id = registerAndStore()
        assertEquals(fake.userId.toString(), id.value)
        assertEquals("Ada", fake.registeredName)
    }

    @Test
    fun authenticateProducesActiveSessionAndToken() = runTest {
        registerAndStore()
        assertEquals(AuthResult.Ok(Unit), repo.authenticate())
        assertTrue(repo.session.value is SessionState.Active)
        assertEquals(fake.issuedToken, repo.bearerToken())
    }

    @Test
    fun validTokenIsReusedWithoutAnotherChallenge() = runTest {
        registerAndStore()
        repo.authenticate()
        repo.authenticate()
        repo.bearerToken()
        assertEquals(1, fake.challenges)
    }

    @Test
    fun expiredTokenTriggersSilentReauthentication() = runTest {
        registerAndStore()
        repo.authenticate()
        now = now.plusSeconds(15 * 60)
        assertNotNull(repo.bearerToken())
        assertEquals(2, fake.challenges)
    }

    @Test
    fun rejectedSignatureLeavesNoToken() = runTest {
        registerAndStore()
        fake.rejectVerify = true
        assertEquals(AuthResult.Err(AuthError.Rejected), repo.authenticate())
        assertEquals(SessionState.Unavailable(AuthError.Rejected), repo.session.value)
        assertNull(repo.bearerToken())
    }

    @Test
    fun serverUnreachableMapsToNetwork() = runTest {
        registerAndStore()
        server.close()
        assertEquals(AuthResult.Err(AuthError.Network), repo.authenticate())
        assertEquals(AuthResult.Err(AuthError.Network), repo.register("Ada"))
    }

    @Test
    fun unregisteredAccountCannotAuthenticate() = runTest {
        assertEquals(AuthResult.Err(AuthError.Rejected), repo.authenticate())
        assertEquals(0, fake.challenges)
    }

    @Test
    fun serverErrorsMapToServer() = runTest {
        fake.failAll = 500
        assertEquals(AuthResult.Err(AuthError.Server), repo.register("Ada"))
        fake.failAll = 400
        assertEquals(AuthResult.Err(AuthError.InvalidInput), repo.register("Ada"))
    }
}

private class MemoryAccounts : AccountRepository {
    val state = MutableStateFlow<Account?>(null)
    override fun observeAccount(): Flow<Account?> = state
    override suspend fun getAccount() = state.value
    override suspend fun saveProfile(displayName: String, avatar: AvatarSource?) = Unit
    override suspend fun markRegistered(userId: UserId) = Unit
}

/**
 * Minimal stand-in for the Go server that enforces the same protocol:
 * signatures are verified with libsignal over the shared message formats.
 */
private class FakeAuthServer(private val now: () -> Instant) : Dispatcher() {
    val userId: UUID = UUID.randomUUID()
    var registeredName: String? = null
    var identityKey: IdentityKey? = null
    var challenges = 0
    var rejectVerify = false
    var failAll: Int? = null
    val issuedToken = "tok-" + UUID.randomUUID()
    private val nonces = mutableMapOf<String, ByteArray>()
    private val b64 = Base64.getDecoder()

    override fun dispatch(request: RecordedRequest): MockResponse {
        failAll?.let { return json(it, """{"code":"x","message":"x"}""") }
        val body = request.body?.utf8()?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        return when (request.url.encodedPath) {
            "/v1/register" -> register(body)
            "/v1/auth/challenge" -> {
                challenges++
                val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val id = UUID.randomUUID().toString()
                nonces[id] = nonce
                json(
                    200,
                    """{"challenge_id":"$id","nonce":"${Base64.getEncoder().encodeToString(
                        nonce,
                    )}","expires_at":"2026-01-01T12:01:00Z"}""",
                )
            }
            "/v1/auth/verify" -> verify(body)
            else -> json(404, "{}")
        }
    }

    private fun register(body: JsonObject): MockResponse {
        val key = b64.decode(body.str("identity_key"))
        val name = body.str("display_name")
        val ok = IdentityKey(
            key,
        ).publicKey.verifySignature(AuthMessages.register(key, name), b64.decode(body.str("signature")))
        if (!ok) return json(401, "{}")
        identityKey = IdentityKey(key)
        registeredName = name
        return json(201, """{"user_id":"$userId"}""")
    }

    private fun verify(body: JsonObject): MockResponse {
        val nonce = nonces.remove(body.str("challenge_id")) ?: return json(401, "{}")
        val ok = identityKey!!.publicKey.verifySignature(
            AuthMessages.auth(userId, nonce),
            b64.decode(body.str("signature")),
        )
        if (!ok || rejectVerify) return json(401, "{}")
        // Go emits nanosecond precision; make sure the client parses it.
        val expiry = now().plusSeconds(15 * 60).plusNanos(123_456_789)
        return json(200, """{"token":"$issuedToken","expires_at":"$expiry"}""")
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()
}
