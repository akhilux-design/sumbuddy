package com.expenses.app.groups.auth

import com.expenses.app.groups.api.ApiError
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** What asking for an access token gave. */
sealed interface TokenResult {
    /** A token with more than a minute left. */
    data class Valid(val token: String) : TokenResult

    /** Nobody is signed in, or the server refused the refresh token (401): sign-in is needed. */
    data object SignedOut : TokenResult

    /**
     * A refresh was needed and failed for a passing reason (no answer, 5xx, an answer that is not ours, 429). The
     * session is still stored: try again later. [error] is never a 401.
     */
    data class Unavailable(val error: ApiError) : TokenResult
}

/** What the API client needs from the signed-in session. [AuthRepository] is the real one. */
interface SessionProvider {
    /** This install's id, made once (`X-Installation-Id`). */
    suspend fun installationId(): String

    /** A valid access token, refreshed first when under 60 s are left. */
    suspend fun token(): TokenResult

    /** The token of [token], or null when there is none now (signed out, or the refresh failed for now). */
    suspend fun accessToken(): String? = (token() as? TokenResult.Valid)?.token

    /**
     * Called after the server refused [rejected] (401 `token_expired` or `unauthorized`): a newer token when another
     * request already refreshed, else the result of one refresh.
     */
    suspend fun refreshAfterRejected(rejected: String?): TokenResult
}

/**
 * Adds `Authorization: Bearer <access token>` and `X-Installation-Id` to every request of the signed-in client
 * (docs/server.md 4.1). It runs on OkHttp's threads, so it may block for the token (one refresh at most).
 */
class AuthInterceptor(private val session: SessionProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val (installationId, token) = runBlocking { session.installationId() to session.accessToken() }
        val builder = chain.request().newBuilder().header(INSTALLATION_HEADER, installationId)
        if (token != null && chain.request().header(AUTHORIZATION) == null) builder.header(AUTHORIZATION, "Bearer $token")
        return chain.proceed(builder.build())
    }

    /** Header names. */
    companion object {
        /** The bearer token header. */
        const val AUTHORIZATION: String = "Authorization"

        /** The install id header. */
        const val INSTALLATION_HEADER: String = "X-Installation-Id"
    }
}

/**
 * Refreshes the access token once when the server answers 401 `token_expired` or `unauthorized`, and repeats the
 * request with the new token. Any other 401 (a failed sign-in, `refresh_invalid`), a second 401 for the same request,
 * or a refused refresh token, is handed back to the caller as the 401. A refresh that failed for a passing reason
 * fails the call like a lost connection (an [IOException]), so the caller retries later instead of asking the person
 * to sign in again.
 */
class TokenAuthenticator(private val session: SessionProvider) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        val code = errorCode(response.peekBody(MAX_ERROR_BODY).string())
        if (code != "token_expired" && code != "unauthorized") return null
        val rejected = response.request.header(AuthInterceptor.AUTHORIZATION)?.removePrefix("Bearer ")
        val fresh = when (val result = runBlocking { session.refreshAfterRejected(rejected) }) {
            is TokenResult.Valid -> result.token
            TokenResult.SignedOut -> return null
            is TokenResult.Unavailable -> {
                response.close()
                throw RefreshUnavailable()
            }
        }
        return response.request.newBuilder().header(AuthInterceptor.AUTHORIZATION, "Bearer $fresh").build()
    }

    /** The refresh failed for a passing reason; the call fails as if the connection had dropped. */
    class RefreshUnavailable : IOException("refresh failed for now")

    private fun errorCode(body: String): String? = runCatching {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
    }.getOrNull()

    private companion object {
        const val MAX_ERROR_BODY = 4_096L
    }
}
