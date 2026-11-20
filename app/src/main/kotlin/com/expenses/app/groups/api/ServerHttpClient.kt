package com.expenses.app.groups.api

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** The time limits of the splitting server's HTTP client ([serverHttpClient]). */
object ServerTimeouts {
    /** Making the connection, TLS included. */
    const val CONNECT_MS: Long = 10_000

    /** The longest silence while an answer is read. */
    const val READ_MS: Long = 15_000

    /** The longest stall while a request is sent. */
    const val WRITE_MS: Long = 15_000

    /**
     * A whole call: connecting, sending, the server's work and reading the answer, with OkHttp's own retries and
     * redirects. Account and Your name keep Back while a save runs, so this is the longest they wait for it.
     */
    const val CALL_MS: Long = 20_000
}

/**
 * The HTTP client of the splitting server, made once and shared by [GroupsApi] (`AppContainer.httpClient`). Nothing is
 * logged. A call that takes longer than [callTimeoutMs] in all fails as if the connection had dropped, so the caller
 * gets [ApiError.offline] and the screen says to check the connection. A group's live WebSocket keeps the limit for
 * its handshake only: OkHttp stops the call timeout once the socket is open.
 *
 * @param callTimeoutMs the limit of a whole call; tests pass a short one.
 */
fun serverHttpClient(callTimeoutMs: Long = ServerTimeouts.CALL_MS): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(ServerTimeouts.CONNECT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ServerTimeouts.READ_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(ServerTimeouts.WRITE_MS, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()
