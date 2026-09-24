package com.gamehub.android.domain

/**
 * The state of any screen that loads something.
 *
 * A sealed interface rather than a data class with nullable fields and a
 * boolean. With `isLoading`, `data` and `error` as independent fields, the
 * compiler permits "loading and errored with data", which is meaningless, and
 * every composable has to decide what that combination renders as.
 *
 * Here the states are mutually exclusive by construction, so a `when` is
 * exhaustive and adding a state breaks every screen that must handle it.
 */
sealed interface UiState<out T> {

    data object Loading : UiState<Nothing>

    /**
     * @param isStale true when this came from the Room cache and the network
     *        refresh failed. The screen shows the data **and** an offline
     *        banner, rather than replacing real content with an error page.
     */
    data class Success<T>(val data: T, val isStale: Boolean = false) : UiState<T>

    /**
     * @param kind decides the message and whether a retry is offered.
     *        Derived from the stable `error` code in the API envelope, never
     *        from parsing the human-readable message.
     */
    data class Error(val kind: ErrorKind, val canRetry: Boolean = true) : UiState<Nothing>
}

/**
 * Error categories the UI actually behaves differently for.
 *
 * Deliberately few. A category that produces the same screen as another is
 * not a category, it is noise.
 */
enum class ErrorKind {
    /** No connectivity. Retrying immediately will not help. */
    OFFLINE,

    /** 5xx or a timeout. Retry is reasonable. */
    SERVER,

    /** 401. The user must sign in again; a retry is pointless. */
    UNAUTHORIZED,

    /** 4xx that is not 401. Usually a bug, so retry is not offered. */
    CLIENT,

    UNKNOWN,
}
