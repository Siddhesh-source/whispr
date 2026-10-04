package dev.whispr.data.network

import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class ServerConfig(val baseUrl: String)

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

    private suspend inline fun <reified Req, reified Res> post(path: String, body: Req): ApiResult<Res> = execute(
        Request.Builder()
            .url(base.resolve(path)!!)
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA))
            .build(),
    )

    private suspend inline fun <reified Res> execute(request: Request): ApiResult<Res> {
        val response = try {
            client.newCall(request).await()
        } catch (_: IOException) {
            return ApiResult.NetworkError
        }
        return response.use {
            if (!it.isSuccessful) return ApiResult.HttpError(it.code)
            try {
                ApiResult.Success(json.decodeFromString<Res>(it.body.string()))
            } catch (_: SerializationException) {
                ApiResult.HttpError(it.code)
            } catch (_: IOException) {
                ApiResult.NetworkError
            }
        }
    }

    companion object {
        val DefaultJson = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA = "application/json".toMediaType()
        private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) =
                cont.resume(response) { _, _, _ -> response.close() }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
        },
    )
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
