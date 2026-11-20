package com.expenses.app.groups.api

/**
 * Why a request failed (docs/server.md 4.1).
 *
 * @param status the HTTP status, or 0 when no answer came.
 * @param code the server's error `code`; `internal` for a 5xx or an answer that is not ours (Cloudflare's own page,
 * for example when the day's free limit is reached), `offline` when no answer came.
 * @param field for validation errors, the JSON path of the bad field.
 * @param fromServer true when the body was our JSON error; false for anything else.
 * @param retryAfterSeconds the `Retry-After` of a 429, or null.
 * @param reason for `not_member`, why the person lost the group ([LostReason]), or null when the server does not say.
 */
data class ApiError(
    val status: Int,
    val code: String,
    val field: String? = null,
    val fromServer: Boolean = true,
    val retryAfterSeconds: Long? = null,
    val reason: String? = null,
) {
    /** True when no answer came (no network, timeout, connection dropped). */
    val isOffline: Boolean get() = status == 0

    /** True when the request may work later as it is: offline, 5xx, or an answer that is not ours. */
    val isTransient: Boolean get() = isOffline || status >= 500 || !fromServer

    /** True for our own 429: wait [retryAfterSeconds], then go on. */
    val isRateLimited: Boolean get() = status == 429 && fromServer

    /** True when the session is gone and the person has to sign in again. */
    val needsSignIn: Boolean get() = status == 401

    /** Codes of docs/server.md 4.1. */
    companion object {
        /** No answer came. */
        const val OFFLINE: String = "offline"

        /** A 5xx, or an answer that is not our JSON. */
        const val INTERNAL: String = "internal"

        /** The person is not signed in on this phone. */
        const val SIGNED_OUT: String = "refresh_invalid"

        /** An error for a request that got no answer. */
        val offline: ApiError = ApiError(status = 0, code = OFFLINE, fromServer = false)

        /** An error for a request that was not sent because nobody is signed in. */
        val signedOut: ApiError = ApiError(status = 401, code = SIGNED_OUT)
    }
}

/** The outcome of one API call. */
sealed interface ApiResult<out T> {
    /**
     * The server accepted the request.
     *
     * @param value the parsed answer (Unit for 204).
     * @param status the HTTP status (200, 201 or 204).
     * @param replay true when the server answered from an earlier identical request (`Idempotent-Replay`).
     */
    data class Ok<out T>(val value: T, val status: Int, val replay: Boolean = false) : ApiResult<T>

    /** The request failed; see [ApiError]. */
    data class Error(val error: ApiError) : ApiResult<Nothing>
}

/** The value of a successful call, or null. */
fun <T> ApiResult<T>.valueOrNull(): T? = (this as? ApiResult.Ok)?.value

/** The error of a failed call, or null. */
fun ApiResult<*>.errorOrNull(): ApiError? = (this as? ApiResult.Error)?.error

/** The same outcome with the value changed by [transform]. */
inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(transform(value), status, replay)
    is ApiResult.Error -> this
}
