package com.expenses.app.groups.api

import com.expenses.app.groups.auth.AuthInterceptor
import com.expenses.app.groups.auth.SessionProvider
import com.expenses.app.groups.auth.TokenAuthenticator
import com.expenses.app.groups.auth.TokenResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The JSON settings of the API: unknown fields ignored, nulls written out as the contract's examples show. */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

/**
 * The phone's client of the splitting server (docs/server.md section 4): one function per route, each main-safe.
 * Only group data goes through it; nothing here takes SMS text or a personal expense.
 *
 * Signed-in routes go through a client with [AuthInterceptor] and [TokenAuthenticator]; the sign-in routes go
 * through the plain client. Nothing is logged: no bodies, headers, ids or tokens.
 *
 * @param baseUrl the server's address without a trailing slash (`BuildConfig.API_BASE_URL`).
 * @param client the shared OkHttp client ([serverHttpClient]); its timeouts are used as they are.
 * @param session the signed-in session; a function so the client and the session can be made in either order.
 */
class GroupsApi(
    baseUrl: String,
    client: OkHttpClient,
    session: () -> SessionProvider,
    private val json: Json = ApiJson,
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val sessionProvider by lazy(session)
    private val plain: OkHttpClient = client
    private val signedIn: OkHttpClient by lazy {
        client.newBuilder()
            .addInterceptor(AuthInterceptor(sessionProvider))
            .authenticator(TokenAuthenticator(sessionProvider))
            .build()
    }
    // No read timeout once open (pings watch the line); the client's call timeout bounds only the handshake.
    private val sockets: OkHttpClient by lazy {
        signedIn.newBuilder().pingInterval(PING_SECONDS, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    }

    // ---- 4.4 Auth (no bearer, no Idempotency-Key) ----

    /** `POST /v1/auth/nonce`: a fresh nonce for Google sign-in. */
    suspend fun nonce(): ApiResult<NonceResponse> =
        call(plain, "POST", "/v1/auth/nonce", JsonObject(emptyMap()), NonceResponse.serializer())

    /** `POST /v1/auth/google`: signs in with a Google ID token. */
    suspend fun signInWithGoogle(request: GoogleSignInRequest): ApiResult<AuthResponse> =
        call(plain, "POST", "/v1/auth/google", json.encodeToJsonElement(GoogleSignInRequest.serializer(), request), AuthResponse.serializer())

    /** `POST /v1/auth/refresh`: swaps the refresh token for a new pair. */
    suspend fun refresh(refreshToken: String): ApiResult<TokenPair> =
        call(plain, "POST", "/v1/auth/refresh", json.encodeToJsonElement(RefreshRequest.serializer(), RefreshRequest(refreshToken)), TokenPair.serializer())

    /** `POST /v1/auth/signout`: ends the session and forgets this install's push token. */
    suspend fun signOut(): ApiResult<Unit> = authed("POST", "/v1/auth/signout", JsonObject(emptyMap()), null)

    /**
     * A POST without sign-in to [path], for routes only debug builds use (`/v1/auth/dev`). Release builds contain no
     * caller of it.
     */
    suspend fun <T> postPublic(path: String, body: JsonElement, answer: KSerializer<T>): ApiResult<T> =
        call(plain, "POST", path, body, answer)

    // ---- 4.5 Me and devices ----

    /** `GET /v1/me`. */
    suspend fun me(): ApiResult<UserDto> = authed("GET", "/v1/me", null, UserDto.serializer())

    /**
     * `PATCH /v1/me`. Each argument is [Unchanged] to leave it, or the new value (null clears phone or UPI ID).
     */
    suspend fun updateMe(
        name: Patch<String> = Unchanged,
        phone: Patch<String?> = Unchanged,
        upiId: Patch<String?> = Unchanged,
        idempotencyKey: String,
    ): ApiResult<UserDto> {
        val body = buildJsonObject {
            if (name is SetTo) put("name", name.value)
            if (phone is SetTo) put("phone", phone.value?.let(::JsonPrimitive) ?: JsonNull)
            if (upiId is SetTo) put("upiId", upiId.value?.let(::JsonPrimitive) ?: JsonNull)
        }
        return authed("PATCH", "/v1/me", body, UserDto.serializer(), idempotencyKey)
    }

    /** `DELETE /v1/me`: deletes the account. */
    suspend fun deleteMe(idempotencyKey: String): ApiResult<Unit> = authed("DELETE", "/v1/me", null, null, idempotencyKey)

    /** `GET /v1/me/groups`: the groups the person is an active member of. */
    suspend fun meGroups(): ApiResult<MeGroupsResponse> = authed("GET", "/v1/me/groups", null, MeGroupsResponse.serializer())

    /** `PUT /v1/devices/{installationId}`: this install's push token. */
    suspend fun putDevice(installationId: String, fcmToken: String): ApiResult<Unit> =
        authed("PUT", "/v1/devices/$installationId", json.encodeToJsonElement(DeviceRequest.serializer(), DeviceRequest(fcmToken)), null)

    /** `DELETE /v1/devices/{installationId}`. */
    suspend fun deleteDevice(installationId: String): ApiResult<Unit> =
        authed("DELETE", "/v1/devices/$installationId", null, null)

    // ---- 4.6 Groups and members ----

    /** `POST /v1/groups` with a body made by [GroupBodies.create]. */
    suspend fun createGroup(body: JsonObject, idempotencyKey: String): ApiResult<CreateGroupResponse> =
        authed("POST", "/v1/groups", body, CreateGroupResponse.serializer(), idempotencyKey)

    /** `PATCH /v1/groups/{gid}`: renames a group. */
    suspend fun renameGroup(groupId: String, name: String, idempotencyKey: String): ApiResult<GroupDto> =
        authed("PATCH", "/v1/groups/$groupId", json.encodeToJsonElement(RenameGroupRequest.serializer(), RenameGroupRequest(name)), GroupDto.serializer(), idempotencyKey)

    /** `DELETE /v1/groups/{gid}` (owner, every net 0). */
    suspend fun deleteGroup(groupId: String, idempotencyKey: String): ApiResult<Unit> =
        authed("DELETE", "/v1/groups/$groupId", null, null, idempotencyKey)

    /** `POST /v1/groups/{gid}/leave` (the caller's net must be 0). */
    suspend fun leaveGroup(groupId: String, idempotencyKey: String): ApiResult<Unit> =
        authed("POST", "/v1/groups/$groupId/leave", JsonObject(emptyMap()), null, idempotencyKey)

    /** `POST /v1/groups/{gid}/members` with a body made by [GroupBodies.member]. */
    suspend fun addMember(groupId: String, body: JsonObject, idempotencyKey: String): ApiResult<MemberDto> =
        authed("POST", "/v1/groups/$groupId/members", body, MemberDto.serializer(), idempotencyKey)

    /** `PATCH /v1/groups/{gid}/members/{mid}` (friends without the app only). */
    suspend fun editMember(groupId: String, memberId: String, body: JsonObject, idempotencyKey: String): ApiResult<MemberDto> =
        authed("PATCH", "/v1/groups/$groupId/members/$memberId", body, MemberDto.serializer(), idempotencyKey)

    /** `DELETE /v1/groups/{gid}/members/{mid}`. */
    suspend fun removeMember(groupId: String, memberId: String, idempotencyKey: String): ApiResult<Unit> =
        authed("DELETE", "/v1/groups/$groupId/members/$memberId", null, null, idempotencyKey)

    /**
     * `POST /v1/groups/{gid}/members/{mid}/undo-join` (the owner, within 7 days): the place goes back to the friend
     * without the app it was before the join, and its links stop working. Answers the restored member. Errors:
     * `not_owner`, `not_allowed` (the owner's own place), `not_joined_by_link`, `undo_expired` (docs/server.md 4.6).
     */
    suspend fun undoJoin(groupId: String, memberId: String, idempotencyKey: String): ApiResult<MemberDto> =
        authed("POST", "/v1/groups/$groupId/members/$memberId/undo-join", JsonObject(emptyMap()), MemberDto.serializer(), idempotencyKey)

    // ---- 4.7 Expenses and 4.8 Settlements ----

    /** `PUT /v1/groups/{gid}/expenses/{eid}`: creates (201) or updates (200) an expense. */
    suspend fun putExpense(groupId: String, expenseId: String, input: ExpenseInputDto, idempotencyKey: String): ApiResult<ExpenseDto> =
        authed("PUT", "/v1/groups/$groupId/expenses/$expenseId", json.encodeToJsonElement(ExpenseInputDto.serializer(), input), ExpenseDto.serializer(), idempotencyKey)

    /** `DELETE /v1/groups/{gid}/expenses/{eid}`. */
    suspend fun deleteExpense(groupId: String, expenseId: String, idempotencyKey: String): ApiResult<VersionResponse> =
        authed("DELETE", "/v1/groups/$groupId/expenses/$expenseId", null, VersionResponse.serializer(), idempotencyKey)

    /** `PUT /v1/groups/{gid}/settlements/{sid}`: records a settlement. */
    suspend fun putSettlement(groupId: String, settlementId: String, input: SettlementInputDto, idempotencyKey: String): ApiResult<SettlementDto> =
        authed("PUT", "/v1/groups/$groupId/settlements/$settlementId", json.encodeToJsonElement(SettlementInputDto.serializer(), input), SettlementDto.serializer(), idempotencyKey)

    /** `POST /v1/groups/{gid}/settlements/{sid}/confirm`. */
    suspend fun confirmSettlement(groupId: String, settlementId: String, idempotencyKey: String): ApiResult<SettlementDto> =
        authed("POST", "/v1/groups/$groupId/settlements/$settlementId/confirm", JsonObject(emptyMap()), SettlementDto.serializer(), idempotencyKey)

    /** `DELETE /v1/groups/{gid}/settlements/{sid}`. */
    suspend fun deleteSettlement(groupId: String, settlementId: String, idempotencyKey: String): ApiResult<VersionResponse> =
        authed("DELETE", "/v1/groups/$groupId/settlements/$settlementId", null, VersionResponse.serializer(), idempotencyKey)

    /**
     * Sends an outbox entry as it was stored: [method] to [path] with [bodyJson] and [idempotencyKey]. The answer is
     * returned as JSON for the sync engine to read by entity kind.
     */
    suspend fun send(method: String, path: String, bodyJson: String?, idempotencyKey: String): ApiResult<JsonElement> =
        authed(method, path, bodyJson?.let(json::parseToJsonElement), JsonElement.serializer(), idempotencyKey)

    // ---- 4.9 Pulling ----

    /** `GET /v1/groups/{gid}/changes?since=N`: one page of up to 100 versions. */
    suspend fun changes(groupId: String, since: Long): ApiResult<ChangesPage> =
        authed("GET", "/v1/groups/$groupId/changes?since=$since", null, ChangesPage.serializer())

    /** `GET /v1/groups/{gid}/balances`. */
    suspend fun balances(groupId: String): ApiResult<BalancesResponse> =
        authed("GET", "/v1/groups/$groupId/balances", null, BalancesResponse.serializer())

    // ---- 4.10 Links and invites ----

    /** `PUT /v1/groups/{gid}/members/{mid}/links/{linkId}` with the token this phone made. */
    suspend fun putLink(groupId: String, memberId: String, linkId: String, token: String): ApiResult<LinkResponse> =
        authed("PUT", "/v1/groups/$groupId/members/$memberId/links/$linkId", json.encodeToJsonElement(LinkRequest.serializer(), LinkRequest(token)), LinkResponse.serializer())

    /** `DELETE /v1/groups/{gid}/links/{linkId}`: revokes one link. */
    suspend fun deleteLink(groupId: String, linkId: String, idempotencyKey: String): ApiResult<Unit> =
        authed("DELETE", "/v1/groups/$groupId/links/$linkId", null, null, idempotencyKey)

    /** `DELETE /v1/groups/{gid}/members/{mid}/links`: revokes every link of a friend. */
    suspend fun deleteMemberLinks(groupId: String, memberId: String, idempotencyKey: String): ApiResult<Unit> =
        authed("DELETE", "/v1/groups/$groupId/members/$memberId/links", null, null, idempotencyKey)

    /** `GET /v1/invites/{token}`: the Join group preview. */
    suspend fun invite(token: String): ApiResult<InvitePreviewDto> =
        authed("GET", "/v1/invites/$token", null, InvitePreviewDto.serializer())

    /** `POST /v1/invites/{token}/accept`. */
    suspend fun acceptInvite(token: String, idempotencyKey: String): ApiResult<AcceptInviteResponse> =
        authed("POST", "/v1/invites/$token/accept", JsonObject(emptyMap()), AcceptInviteResponse.serializer(), idempotencyKey)

    // ---- 4.11 Reminders, 4.12 Live room, 4.13 Health ----

    /** `POST /v1/groups/{gid}/reminders`. */
    suspend fun remind(groupId: String, memberIds: List<String>, idempotencyKey: String): ApiResult<RemindResponse> =
        authed("POST", "/v1/groups/$groupId/reminders", json.encodeToJsonElement(RemindRequest.serializer(), RemindRequest(memberIds)), RemindResponse.serializer(), idempotencyKey)

    /**
     * Opens `GET /v1/groups/{gid}/live` as a WebSocket with the usual headers (no token in the address), with
     * protocol-level pings every 30 s.
     */
    fun openLive(groupId: String, listener: WebSocketListener): WebSocket {
        val url = base.newBuilder().addPathSegments("v1/groups/$groupId/live").build()
        return sockets.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    /** `GET /v1/health`. */
    suspend fun health(): ApiResult<JsonElement> = call(plain, "GET", "/v1/health", null, JsonElement.serializer())

    // ---- Plumbing ----

    private suspend fun <T> authed(
        method: String,
        path: String,
        body: JsonElement?,
        answer: DeserializationStrategy<T>?,
        idempotencyKey: String? = null,
    ): ApiResult<T> {
        when (val token = sessionProvider.token()) {
            is TokenResult.Valid -> Unit
            // Nobody signed in, or the server refused the session: nothing is sent.
            TokenResult.SignedOut -> return ApiResult.Error(ApiError.signedOut)
            // Signed in, but the refresh failed for now (offline, 5xx): the caller retries; this is not a sign-out.
            is TokenResult.Unavailable -> return ApiResult.Error(token.error)
        }
        return call(signedIn, method, path, body, answer, idempotencyKey)
    }

    private suspend fun <T> call(
        client: OkHttpClient,
        method: String,
        path: String,
        body: JsonElement?,
        answer: DeserializationStrategy<T>?,
        idempotencyKey: String? = null,
    ): ApiResult<T> {
        val url = (base.toString().trimEnd('/') + path).toHttpUrl()
        val requestBody = body?.let { json.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON_TYPE) }
            ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(JSON_TYPE) else null
        val request = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .header("Accept", "application/json")
            .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
            .build()
        val raw = try {
            client.newCall(request).await()
        } catch (_: IOException) {
            return ApiResult.Error(ApiError.offline)
        }
        return parse(raw, answer)
    }

    private fun <T> parse(raw: RawResponse, answer: DeserializationStrategy<T>?): ApiResult<T> {
        if (raw.status in 200..299) {
            @Suppress("UNCHECKED_CAST")
            val value: T = when {
                answer == null -> Unit as T
                // A 204 has no body; only a JSON answer type (the outbox's) accepts that, as null.
                else -> runCatching { json.decodeFromString(answer, raw.body?.takeIf { it.isNotBlank() } ?: "null") }.getOrElse {
                    return ApiResult.Error(ApiError(raw.status, ApiError.INTERNAL, fromServer = false))
                }
            }
            return ApiResult.Ok(value, raw.status, raw.replay)
        }
        val detail = raw.body?.let { body -> runCatching { json.decodeFromString(ErrorBody.serializer(), body).error }.getOrNull() }
        val error = if (detail == null) {
            ApiError(raw.status, ApiError.INTERNAL, fromServer = false, retryAfterSeconds = raw.retryAfter)
        } else {
            ApiError(raw.status, detail.code, detail.field, fromServer = raw.status < 500, retryAfterSeconds = raw.retryAfter, reason = detail.reason)
        }
        return ApiResult.Error(error)
    }

    /** The parts of an answer the client reads; the body is read on OkHttp's thread. */
    private class RawResponse(val status: Int, val body: String?, val replay: Boolean, val retryAfter: Long?)

    private suspend fun Call.await(): RawResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val raw = try {
                        response.use {
                            RawResponse(
                                status = it.code,
                                body = it.body.string().takeIf { text -> text.length <= MAX_BODY_CHARS },
                                replay = it.header("Idempotent-Replay") == "true",
                                retryAfter = it.header("Retry-After")?.toLongOrNull(),
                            )
                        }
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                        return
                    }
                    continuation.resume(raw)
                }
            },
        )
    }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        const val PING_SECONDS = 30L
        const val MAX_BODY_CHARS = 4_000_000
    }
}

/** A field of a PATCH: left as it is, or set to a value. */
sealed interface Patch<out T>

/** The field is not sent, so the server leaves it unchanged. */
data object Unchanged : Patch<Nothing>

/** The field is sent with [value] (null clears it). */
data class SetTo<out T>(val value: T) : Patch<T>
