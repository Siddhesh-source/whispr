package dev.whispr.data.network

import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Executes [request] and decodes a JSON body. Bodies are never logged. */
internal suspend inline fun <reified Res> OkHttpClient.executeJson(request: Request, json: Json): ApiResult<Res> {
    val response = try {
        newCall(request).await()
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

/** Executes [request], expecting no body. */
internal suspend fun OkHttpClient.executeUnit(request: Request): ApiResult<Unit> {
    val response = try {
        newCall(request).await()
    } catch (_: IOException) {
        return ApiResult.NetworkError
    }
    return response.use { if (it.isSuccessful) ApiResult.Success(Unit) else ApiResult.HttpError(it.code) }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
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
