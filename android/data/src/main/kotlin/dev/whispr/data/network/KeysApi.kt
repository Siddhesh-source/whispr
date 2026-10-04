package dev.whispr.data.network

import dev.whispr.data.auth.TokenSource
import dev.whispr.data.crypto.KeyServer
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Prekey endpoints. Only public keys and signatures ever cross this API. */
class KeysApi(
    private val client: OkHttpClient,
    config: ServerConfig,
    private val tokens: TokenSource,
    private val json: Json = AuthApi.DefaultJson,
) : KeyServer {
    private val base: HttpUrl = config.baseUrl.toHttpUrl()

    override suspend fun upload(request: KeyUploadRequest): ApiResult<Unit> {
        val token = tokens.bearerToken() ?: return ApiResult.NetworkError
        return client.executeUnit(
            Request.Builder()
                .url(base.resolve("v1/keys")!!)
                .header("Authorization", "Bearer $token")
                .put(json.encodeToString(KeyUploadRequest.serializer(), request).toRequestBody(JSON))
                .build(),
        )
    }

    override suspend fun counts(): ApiResult<KeyCountsResponse> {
        val token = tokens.bearerToken() ?: return ApiResult.NetworkError
        return client.executeJson(
            Request.Builder().url(
                base.resolve("v1/keys/count")!!,
            ).header("Authorization", "Bearer $token").get().build(),
            json,
        )
    }

    /** Fetches (and consumes) one prekey bundle for [userId]. */
    suspend fun bundle(userId: String): BundleResult {
        val token = tokens.bearerToken() ?: return BundleResult.NetworkError
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("v1/keys").addPathSegment(userId).build())
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (_: IOException) {
            return BundleResult.NetworkError
        }
        return response.use {
            val body = try {
                it.body.string()
            } catch (_: IOException) {
                return BundleResult.NetworkError
            }
            when {
                it.isSuccessful -> try {
                    BundleResult.Success(json.decodeFromString(BundleResponse.serializer(), body))
                } catch (_: SerializationException) {
                    BundleResult.Failed(it.code)
                } catch (_: IllegalArgumentException) {
                    BundleResult.Failed(it.code)
                }
                it.code == HTTP_TOO_MANY -> BundleResult.RateLimited
                it.code == HTTP_NOT_FOUND -> when (errorCode(body)) {
                    "no_keys" -> BundleResult.NoKeys
                    else -> BundleResult.UnknownUser
                }
                else -> BundleResult.Failed(it.code)
            }
        }
    }

    private fun errorCode(body: String): String? = try {
        json.decodeFromString(ErrorBody.serializer(), body).code
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY = 429
    }
}

sealed interface BundleResult {
    data class Success(val bundle: BundleResponse) : BundleResult

    /** The user exists but has not uploaded keys yet. */
    data object NoKeys : BundleResult
    data object UnknownUser : BundleResult
    data object RateLimited : BundleResult
    data object NetworkError : BundleResult
    data class Failed(val code: Int) : BundleResult
}

@Serializable
private data class ErrorBody(val code: String? = null)

// Byte fields are standard base64, matching the server's JSON encoding of []byte.

@Serializable
data class SignedKeyJson(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
    val signature: String,
)

@Serializable
data class OneTimeKeyJson(@SerialName("key_id") val keyId: Int, @SerialName("public_key") val publicKey: String)

@Serializable
data class KeyUploadRequest(
    @SerialName("registration_id") val registrationId: Int? = null,
    @SerialName("signed_prekey") val signedPreKey: SignedKeyJson? = null,
    @SerialName("kyber_last_resort") val lastResort: SignedKeyJson? = null,
    @SerialName("one_time_prekeys") val oneTime: List<OneTimeKeyJson>? = null,
    @SerialName("kyber_prekeys") val kyber: List<SignedKeyJson>? = null,
)

@Serializable
data class KeyCountsResponse(
    @SerialName("registration_id") val registrationId: Int? = null,
    @SerialName("signed_prekey_id") val signedPreKeyId: Int? = null,
    @SerialName("kyber_last_resort_id") val lastResortId: Int? = null,
    @SerialName("one_time_prekeys") val oneTime: Int,
    @SerialName("kyber_prekeys") val kyber: Int,
)

@Serializable
data class KyberKeyJson(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
    val signature: String,
    @SerialName("last_resort") val lastResort: Boolean,
)

@Serializable
data class BundleResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: Int,
    @SerialName("registration_id") val registrationId: Int,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("signed_prekey") val signedPreKey: SignedKeyJson,
    @SerialName("one_time_prekey") val oneTime: OneTimeKeyJson? = null,
    @SerialName("kyber_prekey") val kyber: KyberKeyJson,
)
