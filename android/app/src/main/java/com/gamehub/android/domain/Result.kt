package com.gamehub.android.domain

/**
 * Outcome of a repository call.
 *
 * Repositories return this rather than throwing. An exception crossing a
 * coroutine boundary into a ViewModel is easy to forget to catch, and the
 * failure mode is a crash in front of the user. A returned type is one the
 * compiler makes you handle.
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>

    /**
     * @param code the stable code from the API error envelope, such as
     *        VERSION_CONFLICT. Callers branch on this, never on the message.
     * @param meta error-specific context. A cloud-save conflict carries
     *        serverVersion here, which is what makes the conflict resolvable
     *        without a second request.
     */
    data class Failure(
        val kind: ErrorKind,
        val code: String? = null,
        val meta: Map<String, Any?> = emptyMap(),
    ) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.data
