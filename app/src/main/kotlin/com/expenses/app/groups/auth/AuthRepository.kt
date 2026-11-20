package com.expenses.app.groups.auth

import android.app.Activity
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.AuthResponse
import com.expenses.app.groups.api.GoogleSignInRequest
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.api.Patch
import com.expenses.app.groups.api.SetTo
import com.expenses.app.groups.api.Unchanged
import com.expenses.app.groups.api.UserDto
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether someone is signed in on this phone. */
sealed interface AuthState {
    /** The stored session is being read (only right after the app starts). */
    data object Loading : AuthState

    /** Nobody is signed in, or the server ended the session. Personal tracking works as always. */
    data object SignedOut : AuthState

    /** [userId] is signed in as [name]. */
    data class SignedIn(val userId: String, val name: String) : AuthState
}

/** What a sign-in gave. */
sealed interface SignInResult {
    /** Signed in. [isNew] is true for a new account: show "Your name (after sign-in)" filled with [name]. */
    data class Success(val isNew: Boolean, val name: String, val phone: String?) : SignInResult

    /** The person closed Google's sheet. Nothing changed. */
    data object Cancelled : SignInResult

    /** Sign-in did not work ("Could not sign in"). Nothing on the phone changed. */
    data object Failed : SignInResult

    /** This build has no Google web client id (no Google project yet). Debug builds explain it. */
    data object NotConfigured : SignInResult
}

/** What signing out did. */
enum class SignOutResult {
    /** Signed out. */
    DONE,

    /** Changes still wait to be sent and could not be sent now; nothing was done. Ask, then pass `force`. */
    UNSENT_CHANGES,
}

/** What the session needs from the rest of the app; wired in [com.expenses.app.di.AppContainer]. */
interface AuthHooks {
    /** Sends what waits in the outbox, for up to 10 s. True when nothing is left. */
    suspend fun flushOutbox(): Boolean

    /** Deletes the group tables, outbox, links and split rules; personal rows (mirrors included) stay. */
    suspend fun clearGroupData()

    /** A different account signed in: drop the old account's group data, its mirrors and its push token (9.2). */
    suspend fun switchAccount()

    /** Signed in: sync now, sync every 12 hours, register for push. */
    suspend fun onSignedIn()

    /** Signed out: stop group work. */
    suspend fun onSignedOut()

    /** Deletes everything of the app on this phone (after account deletion): the app is as new. */
    fun clearAppData()
}

/**
 * The signed-in session (docs/server.md 9.2): Google sign-in, the debug-only dev sign-in, tokens, profile, sign-out
 * and account deletion. Tokens and profile are stored encrypted by [TokenStore]. The access token is kept in memory
 * too and read back from the store in a new process, so a cold start (a push, the periodic sync, the app reopened)
 * does not refresh while the token has more than a minute left; refreshes run one at a time.
 *
 * A refused refresh token (`refresh_invalid`) is not a sign-out: tokens are forgotten and Sign in shows where it is
 * needed, but the group tables and the outbox stay ("Nothing is lost"). Signing in again as the same person goes on
 * from there; a different person's sign-in drops them first.
 *
 * The stored profile is read again from the server ([refreshProfile]) when the app starts and when Account opens, so
 * a change made on another phone shows here too.
 */
class AuthRepository(
    private val api: GroupsApi,
    private val store: TokenStore,
    private val google: GoogleIdTokens,
    private val dev: DevSignIn,
    private val settings: SettingsRepository,
    private val hooks: AuthHooks,
    private val clock: Clock,
    scope: CoroutineScope,
) : SessionProvider {
    private val refreshLock = Mutex()
    private val signInLock = Mutex()

    /** Held only while a profile is written, never during a request. */
    private val profileLock = Mutex()

    /** Goes up each time a sign-in or a save stores the profile, so an older read from the server never replaces it. */
    private val profileWrites = AtomicLong()

    @Volatile private var access: String? = null

    @Volatile private var accessExpiresAt: Long = 0

    /** False until the stored access token was read into memory (once per process, under [refreshLock]). */
    private var accessLoaded = false

    /** Who is signed in; [AuthState.Loading] until the stored session is read. */
    val state: StateFlow<AuthState> = store.session
        .map { stored ->
            if (stored.signedIn) AuthState.SignedIn(stored.userId!!, stored.name.orEmpty()) else AuthState.SignedOut
        }
        .stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)

    /** True when Google sign-in can work in this build (a web client id is set). */
    val googleConfigured: Boolean get() = google.configured

    /** True in debug builds, which can sign in against the local server without Google. */
    val devSignInAvailable: Boolean get() = dev.available

    /** True while a session is stored (read from disk, so it works before [state] has loaded). */
    suspend fun isSignedIn(): Boolean = store.current().signedIn

    /** The signed-in profile as last stored (name, phone, UPI ID), or null when signed out. */
    suspend fun profile(): UserDto? {
        val stored = store.current()
        return if (stored.signedIn) UserDto(stored.userId!!, stored.name.orEmpty(), stored.phone, stored.upiId) else null
    }

    /**
     * Signs in with Google: a nonce from the server, Google's account sheet over [activity], then the server checks
     * the ID token and starts a session.
     *
     * @param auto true for the sheet shown by itself when Sign in opens; its errors are silent ([SignInResult.Cancelled]).
     * It may sign in with the one account used before without a tap, but not after the person signed out.
     */
    suspend fun signInWithGoogle(activity: Activity, auto: Boolean): SignInResult = signInLock.withLock {
        if (!google.configured) return if (auto) SignInResult.Cancelled else SignInResult.NotConfigured
        val failed = if (auto) SignInResult.Cancelled else SignInResult.Failed
        val nonce = (api.nonce() as? ApiResult.Ok)?.value?.nonce ?: return failed
        val autoSelect = auto && !store.current().signedOutByUser
        val idToken = when (val result = google.idToken(activity, nonce, auto, autoSelect)) {
            is GoogleResult.Token -> result.idToken
            GoogleResult.Cancelled -> return SignInResult.Cancelled
            GoogleResult.Failed -> return failed
        }
        val answer = api.signInWithGoogle(GoogleSignInRequest(idToken, nonce, store.installationId()))
        val response = (answer as? ApiResult.Ok)?.value ?: return failed
        complete(response)
    }

    /** Debug builds only: signs in against the local server as [devUser], made with [name] the first time. */
    suspend fun signInDev(devUser: String, name: String): SignInResult = signInLock.withLock {
        if (!dev.available) return SignInResult.Failed
        val answer = dev.signIn(devUser, name, store.installationId())
        val response = (answer as? ApiResult.Ok)?.value ?: return SignInResult.Failed
        complete(response)
    }

    private suspend fun complete(response: AuthResponse): SignInResult {
        val user = response.user
        val last = store.current().lastUserId
        // A different account on this phone: the old account's groups, outbox and mirrors must not mix with it.
        if (last != null && last != user.id) hooks.switchAccount()
        refreshLock.withLock {
            access = response.accessToken
            accessExpiresAt = response.accessExpiresAt
            accessLoaded = true
            profileLock.withLock {
                profileWrites.incrementAndGet()
                store.saveSignIn(
                    user.id, user.name, user.phone, user.upiId,
                    Tokens(response.accessToken, response.accessExpiresAt, response.refreshToken, response.refreshExpiresAt),
                    profileCheckedAt = clock.millis(),
                )
            }
        }
        // "I already have an account": the name used on Home comes from the account when none was typed yet.
        if (settings.settings.first().userName == null) settings.setUserName(user.name)
        hooks.onSignedIn()
        return SignInResult.Success(isNew = response.isNew, name = user.name, phone = user.phone)
    }

    /**
     * Saves "Your name (after sign-in)" and Account: the name (also used on Home), the optional phone number, which
     * is not checked, and, when [upiId] is [SetTo], the optional UPI ID (null clears it; check it first with
     * `GroupText.checkUpiId`, docs/server.md 4.5). The phone is sent only now, after sign-in. One request.
     *
     * @return the server's error (a refused UPI ID is `bad_request` with field `upiId`), or null when saved.
     */
    suspend fun saveProfile(name: String, phone: String?, upiId: Patch<String?> = Unchanged): ApiError? {
        val result = api.updateMe(
            name = SetTo(name.trim()),
            phone = SetTo(phone),
            upiId = upiId,
            idempotencyKey = UUID.randomUUID().toString(),
        )
        return when (result) {
            is ApiResult.Ok -> {
                storeSaved(result.value)
                settings.setUserName(result.value.name)
                null
            }
            is ApiResult.Error -> result.error
        }
    }

    /** Saves the UPI ID friends pay to, or clears it with null. Returns the server's error, or null when saved. */
    suspend fun setUpiId(upiId: String?): ApiError? {
        return when (val result = api.updateMe(upiId = SetTo(upiId), idempotencyKey = UUID.randomUUID().toString())) {
            is ApiResult.Ok -> {
                storeSaved(result.value)
                null
            }
            is ApiResult.Error -> result.error
        }
    }

    /** Stores what a `PATCH /v1/me` answered: the newest profile, so a read already on its way is dropped. */
    private suspend fun storeSaved(user: UserDto) = profileLock.withLock {
        profileWrites.incrementAndGet()
        store.saveProfile(user.id, user.name, user.phone, user.upiId, checkedAt = clock.millis())
    }

    /**
     * Reads the profile from the server (`GET /v1/me`) and stores it, so a change made on another phone shows here:
     * Account's fields and, when the name changed, the name used on Home (a save there sets both). Nothing is sent
     * while signed out. The answer is dropped when a save or a sign-in stored a profile while it was on its way (that
     * one is newer), or when the person signed out or another account signed in.
     *
     * @param onlyIfDue true for the app's start: nothing is sent when the profile was asked for, saved or given by a
     * sign-in less than 10 minutes ago. Account asks every time it opens.
     * @return the profile now stored, or null when nothing was read or stored (signed out, not due, offline, refused).
     */
    suspend fun refreshProfile(onlyIfDue: Boolean = false): UserDto? {
        val stored = store.current()
        if (!stored.signedIn) return null
        val now = clock.millis()
        if (onlyIfDue) {
            val last = stored.profileCheckedAt
            // A time ahead of now (the phone's clock was set back) does not hold the next read off.
            if (last != null && now >= last && now - last < PROFILE_CHECK_GAP_MS) return null
            store.markProfileChecked(now)
        }
        val writes = profileWrites.get()
        val user = (api.me() as? ApiResult.Ok)?.value ?: return null
        return profileLock.withLock {
            if (profileWrites.get() != writes) return@withLock null
            val before = store.current()
            if (!store.saveProfile(user.id, user.name, user.phone, user.upiId, checkedAt = clock.millis())) return@withLock null
            // Renamed on another phone: the name used on Home follows, as a save on this phone does.
            if (before.name != user.name) settings.setUserName(user.name)
            user
        }
    }

    /**
     * Signs out (Account): sends what waits (up to 10 s), ends the session on the server, then forgets the tokens
     * and the group data on this phone, and tells Google's sign-in to forget the session too. Personal expenses stay,
     * split mirrors included. When changes could not be sent and [force] is false, nothing is done and
     * [SignOutResult.UNSENT_CHANGES] says so.
     */
    suspend fun signOut(force: Boolean = false): SignOutResult {
        val flushed = hooks.flushOutbox()
        if (!flushed && !force) return SignOutResult.UNSENT_CHANGES
        // Offline is fine: the refresh token is forgotten below, so the session cannot be used again from here.
        api.signOut()
        forgetSession()
        google.clearState()
        return SignOutResult.DONE
    }

    /**
     * Deletes the account on the server, then everything of the app on this phone (flow 09: "The app is as new").
     *
     * @return the server's error, or null when the account was deleted.
     */
    suspend fun deleteAccount(): ApiError? {
        val result = api.deleteMe(UUID.randomUUID().toString())
        if (result is ApiResult.Error) return result.error
        forgetSession()
        // Before the app data goes: clearing it ends the process, and nothing after it runs.
        google.clearState()
        hooks.clearAppData()
        return null
    }

    private suspend fun forgetSession() {
        refreshLock.withLock {
            access = null
            accessExpiresAt = 0
            accessLoaded = true
            store.clearSession()
        }
        hooks.onSignedOut()
        hooks.clearGroupData()
    }

    override suspend fun installationId(): String = store.installationId()

    override suspend fun token(): TokenResult = refreshLock.withLock {
        loadStoredAccess()
        val current = access
        if (current != null && accessExpiresAt - clock.millis() > MIN_TOKEN_LIFE_MS) return TokenResult.Valid(current)
        refreshLocked()
    }

    override suspend fun refreshAfterRejected(rejected: String?): TokenResult = refreshLock.withLock {
        loadStoredAccess()
        val current = access
        // Another request refreshed while this one waited: use the newer token.
        if (current != null && current != rejected && accessExpiresAt - clock.millis() > MIN_TOKEN_LIFE_MS) {
            return TokenResult.Valid(current)
        }
        refreshLocked()
    }

    /** A new process: the access token the last one stored, if any. Under [refreshLock]. */
    private suspend fun loadStoredAccess() {
        if (accessLoaded) return
        val stored = store.current()
        if (stored.signedIn && access == null) {
            access = stored.accessToken
            accessExpiresAt = stored.accessExpiresAt ?: 0
        }
        accessLoaded = true
    }

    /**
     * One refresh, under [refreshLock]. The new tokens are stored before the lock is released. A refusal (401) is the
     * only answer that means sign-in is needed; anything else is a passing failure, and the stored session stays.
     *
     * It is not cut off when the caller goes (a screen closed while it waited): the server has turned the old refresh
     * token over by then, and an answer dropped here would leave only that old token, which it takes back for just
     * 10 minutes. The shared client's call timeout bounds the wait.
     */
    private suspend fun refreshLocked(): TokenResult = withContext(NonCancellable) {
        val stored = store.current()
        val refreshToken = stored.refreshToken ?: return@withContext TokenResult.SignedOut
        when (val result = api.refresh(refreshToken)) {
            is ApiResult.Ok -> {
                val pair = result.value
                store.saveTokens(Tokens(pair.accessToken, pair.accessExpiresAt, pair.refreshToken, pair.refreshExpiresAt))
                access = pair.accessToken
                accessExpiresAt = pair.accessExpiresAt
                TokenResult.Valid(pair.accessToken)
            }
            is ApiResult.Error -> {
                val error = result.error
                if (error.status == 401) {
                    // The server refused the refresh token: sign-in is needed, but groups and the outbox stay. The
                    // person did not sign out, so Google's state is left as it is.
                    access = null
                    accessExpiresAt = 0
                    store.forgetTokens()
                    hooks.onSignedOut()
                    TokenResult.SignedOut
                } else {
                    // Offline, 5xx, Cloudflare's own page or a lost answer: keep the session and try again later (the
                    // server accepts the previous token again within 10 minutes).
                    TokenResult.Unavailable(passing(error))
                }
            }
        }
    }

    /** A refresh failure as the error of the call that needed the token: never a 401, never a rejection. */
    private fun passing(error: ApiError): ApiError = when {
        error.isOffline -> ApiError.offline
        error.isRateLimited -> error
        else -> ApiError(status = if (error.status >= 500) error.status else SERVICE_UNAVAILABLE, code = ApiError.INTERNAL, fromServer = false)
    }

    private companion object {
        /** Refresh when the access token has less than this left. */
        const val MIN_TOKEN_LIFE_MS = 60_000L

        /** The app's start reads the profile again only this long after it was last read, saved or signed in. */
        const val PROFILE_CHECK_GAP_MS = 10 * 60_000L
        const val SERVICE_UNAVAILABLE = 503
    }
}
