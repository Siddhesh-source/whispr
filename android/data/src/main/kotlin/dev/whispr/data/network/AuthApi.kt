package dev.whispr.data.network

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** [pins]: SPKI pins ("sha256/<base64>") for the server's certificate chain; empty disables pinning. */
data class ServerConfig(val baseUrl: String, val pins: List<String> = emptyList())

sealed interface ApiResult<out T> {
    data class Success<T>(val body: T) : ApiResult<T>
    data class HttpError(val code: Int) : ApiResult<Nothing>
    data object NetworkError : ApiResult<Nothing>
}

/**
 * Thin client for the auth endpoints. Request and response bodies are never
 * logged; there is deliberately no HTTP logging interceptor.
 */
class AuthApi(private val client: OkHttpClient, config: ServerConfig, private val json: Json = DefaultJson) {
    private val base: HttpUrl = config.baseUrl.toHttpUrl()

    suspend fun register(
        identityKey: ByteArray,
        displayName: String,
        signature: ByteArray,
    ): ApiResult<RegisterResponse> = post("v1/register", RegisterRequest(b64(identityKey), displayName, b64(signature)))

    suspend fun challenge(userId: String): ApiResult<ChallengeResponse> =
        post("v1/auth/challenge", ChallengeRequest(userId))

    suspend fun verify(challengeId: String, signature: ByteArray): ApiResult<VerifyResponse> =
        post("v1/auth/verify", VerifyRequest(challengeId, b64(signature)))

    suspend fun me(token: String): ApiResult<MeResponse> = execute(
        Request.Builder().url(base.resolve("v1/me")!!).header("Authorization", "Bearer $token").get().build(),
    )

    /** Deletes the account server-side; [signature] answers a fresh challenge over [AuthMessages.delete]. */
    suspend fun deleteAccount(token: String, challengeId: String, signature: ByteArray): ApiResult<Unit> =
        client.executeUnit(
            Request.Builder()
                .url(base.resolve("v1/me")!!)
                .header("Authorization", "Bearer $token")
                .delete(
                    json.encodeToString(DeleteAccountRequest(challengeId, b64(signature))).toRequestBody(JSON_MEDIA),
                )
                .build(),
        )

    private suspend inline fun <reified Req, reified Res> post(path: String, body: Req): ApiResult<Res> = execute(
        Request.Builder()
            .url(base.resolve(path)!!)
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA))
            .build(),
    )

    private suspend inline fun <reified Res> execute(request: Request): ApiResult<Res> =
        client.executeJson(request, json)

    companion object {
        val DefaultJson = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA = "application/json".toMediaType()
        private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    }
}

@Serializable
data class RegisterRequest(
    @SerialName("identity_key") val identityKey: String,
    @SerialName("display_name") val displayName: String,
    val signature: String,
)

@Serializable
data class RegisterResponse(@SerialName("user_id") val userId: String)

@Serializable
data class ChallengeRequest(@SerialName("user_id") val userId: String)

@Serializable
data class ChallengeResponse(
    @SerialName("challenge_id") val challengeId: String,
    val nonce: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class VerifyRequest(@SerialName("challenge_id") val challengeId: String, val signature: String)

@Serializable
data class VerifyResponse(val token: String, @SerialName("expires_at") val expiresAt: String)

@Serializable
data class MeResponse(@SerialName("user_id") val userId: String, @SerialName("display_name") val displayName: String)

@Serializable
data class DeleteAccountRequest(@SerialName("challenge_id") val challengeId: String, val signature: String)
