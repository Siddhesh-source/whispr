package dev.whispr.data.network

import dev.whispr.data.auth.TokenSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Authenticated REST calls. Tokens come from [TokenSource] (re-auth on demand). */
class WhisprApi(
    private val client: OkHttpClient,
    config: ServerConfig,
    private val tokens: TokenSource,
    private val json: Json = AuthApi.DefaultJson,
) {
    private val base: HttpUrl = config.baseUrl.toHttpUrl()

    suspend fun lookupUser(userId: String): ApiResult<UserResponse> {
        val token = tokens.bearerToken() ?: return ApiResult.NetworkError
        val request = Request.Builder()
            .url(base.resolve("v1/users/$userId")!!)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return client.executeJson<UserResponse>(request, json)
    }

    suspend fun putPushToken(token: String): ApiResult<Unit> {
        val bearer = tokens.bearerToken() ?: return ApiResult.NetworkError
        val request = Request.Builder()
            .url(base.resolve("v1/push/token")!!)
            .header("Authorization", "Bearer $bearer")
            .put(json.encodeToString(PushTokenRequest.serializer(), PushTokenRequest("fcm", token)).toRequestBody(JSON))
            .build()
        return client.executeUnit(request)
    }

    /** ws(s)://host/v1/ws */
    fun webSocketUrl(): String = base.resolve("v1/ws")!!.toString().replaceFirst("http", "ws")

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

@Serializable
data class UserResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("identity_key") val identityKey: String,
)

@Serializable
data class PushTokenRequest(val provider: String, val token: String)
