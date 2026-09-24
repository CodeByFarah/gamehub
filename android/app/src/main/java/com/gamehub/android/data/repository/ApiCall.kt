package com.gamehub.android.data.repository

import com.gamehub.android.data.remote.ApiError
import com.gamehub.android.domain.ApiResult
import com.gamehub.android.domain.ErrorKind
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns a Retrofit [Response] into an [ApiResult], mapping failures into the
 * few categories the UI actually behaves differently for.
 *
 * Centralised so every repository classifies errors identically. Done per call
 * site, one repository would treat a timeout as retryable and another would
 * not, and that inconsistency surfaces as screens behaving differently for the
 * same underlying failure.
 */
suspend fun <T> apiCall(
    json: Json,
    block: suspend () -> Response<T>,
): ApiResult<T> = try {
    val response = block()
    val body = response.body()

    when {
        response.isSuccessful && body != null -> ApiResult.Success(body)

        // A 204 with a Unit return type is a success with no body.
        @Suppress("UNCHECKED_CAST")
        response.isSuccessful -> ApiResult.Success(Unit as T)

        else -> failureFrom(json, response)
    }
} catch (e: UnknownHostException) {
    // No DNS, which in practice means no connectivity. Retrying immediately
    // will not help, so the UI says offline rather than offering a retry.
    ApiResult.Failure(ErrorKind.OFFLINE)
} catch (e: SocketTimeoutException) {
    // The server may be up but slow, so a retry is reasonable here.
    ApiResult.Failure(ErrorKind.SERVER)
} catch (e: IOException) {
    ApiResult.Failure(ErrorKind.OFFLINE)
} catch (e: Exception) {
    // Serialisation failures land here. Not retryable: the response will be
    // just as unparseable next time.
    ApiResult.Failure(ErrorKind.UNKNOWN)
}

/**
 * Parses the documented error envelope.
 *
 * The stable `error` code and the `meta` map are both preserved, because a
 * cloud-save conflict carries `serverVersion` there and that is what makes the
 * conflict resolvable without another round trip.
 *
 * An unparseable error body is not itself an error: the status code is enough
 * to classify the failure, so the envelope is best-effort.
 */
private fun <T> failureFrom(json: Json, response: Response<T>): ApiResult.Failure {
    val kind = when (response.code()) {
        401 -> ErrorKind.UNAUTHORIZED
        in 400..499 -> ErrorKind.CLIENT
        in 500..599 -> ErrorKind.SERVER
        else -> ErrorKind.UNKNOWN
    }

    val envelope = runCatching {
        response.errorBody()?.string()?.let { json.decodeFromString<ApiError>(it) }
    }.getOrNull()

    return ApiResult.Failure(
        kind = kind,
        code = envelope?.error,
        meta = envelope?.meta ?: emptyMap(),
    )
}
