package dev.whispr.data.network

import dev.whispr.data.auth.TokenSource
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface DownloadResult {
    class Ok(val blob: ByteArray) : DownloadResult

    /** The server no longer has it (retention) or never did. */
    data object Gone : DownloadResult

    /** Larger than announced: refused without reading it all. */
    data object TooLarge : DownloadResult
    data object NetworkError : DownloadResult
}

/** Uploads and downloads encrypted attachment blobs. Only ciphertext ever goes through here. */
class MediaApi(
    private val client: OkHttpClient,
    config: ServerConfig,
    private val tokens: TokenSource,
    private val json: Json = AuthApi.DefaultJson,
) {
    private val base: HttpUrl = config.baseUrl.toHttpUrl()

    suspend fun upload(blob: ByteArray): ApiResult<String> {
        val token = tokens.bearerToken() ?: return ApiResult.NetworkError
        val request = Request.Builder()
            .url(base.resolve("v1/attachments")!!)
            .header("Authorization", "Bearer $token")
            .post(blob.toRequestBody(OCTETS))
            .build()
        return when (val r = client.executeJson<UploadResponse>(request, json)) {
            is ApiResult.Success -> ApiResult.Success(r.body.id)
            is ApiResult.HttpError -> ApiResult.HttpError(r.code)
            ApiResult.NetworkError -> ApiResult.NetworkError
        }
    }

    /** Downloads at most [maxBytes]; anything longer is refused. */
    suspend fun download(id: String, maxBytes: Long): DownloadResult {
        val token = tokens.bearerToken() ?: return DownloadResult.NetworkError
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("v1/attachments").addPathSegment(id).build())
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (_: IOException) {
            return DownloadResult.NetworkError
        }
        return response.use {
            when {
                it.code == HTTP_NOT_FOUND -> DownloadResult.Gone
                !it.isSuccessful -> DownloadResult.NetworkError
                it.body.contentLength() > maxBytes -> DownloadResult.TooLarge
                else -> try {
                    val source = it.body.source()
                    // Read one byte past the limit to detect an oversized body without a length.
                    source.request(maxBytes + 1)
                    if (source.buffer.size > maxBytes) {
                        DownloadResult.TooLarge
                    } else {
                        DownloadResult.Ok(source.readByteArray())
                    }
                } catch (_: IOException) {
                    DownloadResult.NetworkError
                }
            }
        }
    }

    @Serializable
    private data class UploadResponse(val id: String)

    private companion object {
        const val HTTP_NOT_FOUND = 404
        val OCTETS = "application/octet-stream".toMediaType()
    }
}
