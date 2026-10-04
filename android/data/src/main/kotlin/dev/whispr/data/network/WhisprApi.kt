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

    suspend fun lookupUsername(username: String): ApiResult<UserResponse> =
        authed { b -> b.url(base.newBuilder().addPathSegments("v1/usernames").addPathSegment(username).build()).get() }

    suspend fun myProfile(): ApiResult<UserResponse> = authed { b -> b.url(base.resolve("v1/me/profile")!!).get() }

    suspend fun putDisplayName(name: String): ApiResult<Unit> = authedUnit { b ->
        b.url(
            base.resolve("v1/me/profile")!!,
        ).put(json.encodeToString(DisplayNameRequest.serializer(), DisplayNameRequest(name)).toRequestBody(JSON))
    }

    suspend fun putUsername(nickname: String): ApiResult<UsernameResponse> = authed { b ->
        b.url(
            base.resolve("v1/me/username")!!,
        ).put(json.encodeToString(NicknameRequest.serializer(), NicknameRequest(nickname)).toRequestBody(JSON))
    }

    suspend fun deleteUsername(): ApiResult<Unit> = authedUnit { b -> b.url(base.resolve("v1/me/username")!!).delete() }

    private suspend inline fun <reified T> authed(build: (Request.Builder) -> Request.Builder): ApiResult<T> {
        val token = tokens.bearerToken() ?: return ApiResult.NetworkError
        return client.executeJson(build(Request.Builder().header("Authorization", "Bearer $token")).build(), json)
    }

    private suspend inline fun authedUnit(build: (Request.Builder) -> Request.Builder): ApiResult<Unit> {
        val token = tokens.bearerToken() ?: return ApiResult.NetworkError
        return client.executeUnit(build(Request.Builder().header("Authorization", "Bearer $token")).build())
    }

    /** Our server's origin (scheme://host[:port]), as carried in contact QR codes. */
    val origin: String get() = base.scheme + "://" + base.host +
        if (base.port == HttpUrl.defaultPort(base.scheme)) "" else ":${base.port}"

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
    val username: String? = null,
)

@Serializable
data class PushTokenRequest(val provider: String, val token: String)

@Serializable
data class DisplayNameRequest(@SerialName("display_name") val displayName: String)

@Serializable
data class NicknameRequest(val nickname: String)

@Serializable
data class UsernameResponse(val username: String)
