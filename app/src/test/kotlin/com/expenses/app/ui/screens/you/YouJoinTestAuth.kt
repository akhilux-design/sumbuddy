package com.expenses.app.ui.screens.you

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.expenses.app.data.repository.TestClock
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.AuthResponse
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.auth.AuthHooks
import com.expenses.app.groups.auth.AuthRepository
import com.expenses.app.groups.auth.GoogleIdTokens
import com.expenses.app.groups.auth.GoogleResult
import com.expenses.app.groups.auth.SignInResult
import com.expenses.app.groups.auth.TokenStore
import com.expenses.app.groups.auth.ValueCipher
import com.expenses.app.groups.auth.devSignIn
import com.expenses.app.groups.json
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.RecordedRequest

/**
 * A real [AuthRepository] on the fake server of [env] (dev sign-in, no Google), for the you-and-join tests: Account,
 * Your name and the You tab. [hooks] records what the session asked of the rest of the app.
 *
 * @param env the fake server.
 * @param scope the test's background scope, for the stores.
 * @param folder where the stores keep their files.
 * @param client the session's HTTP client.
 */
internal class YouJoinTestAuth(
    private val env: GroupsTestEnv,
    scope: CoroutineScope,
    folder: File,
    private val client: OkHttpClient = OkHttpClient(),
) {
    /** What the session asked of the app. */
    val hooks = RecordingHooks()

    /** The person's settings (the name used on Home). */
    val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder, "settings.preferences_pb") })

    private val store = TokenStore(PreferenceDataStoreFactory.create(scope = scope) { File(folder, "auth.preferences_pb") }, PlainCipher)

    /** The session. */
    val auth: AuthRepository

    init {
        lateinit var repo: AuthRepository
        val api = GroupsApi(env.server.url("/").toString().trimEnd('/'), client, { repo })
        repo = AuthRepository(api, store, NoGoogle, devSignIn(api), settings, hooks, TestClock, scope)
        auth = repo
    }

    /** Signs in as [user] through the fake server's dev sign-in; the server's profile (`GET /v1/me`) is the same. */
    suspend fun signIn(user: UserDto = UserDto(ME_USER, "Arjun", "+919876543210", "arjun@okhdfc"), isNew: Boolean = false) {
        val now = TestClock.millis()
        env.fake.on("POST", "/v1/auth/dev", json(200, AuthResponse.serializer(), AuthResponse(user, isNew, "a1", now + DAY_MS, "r1", now + 60 * DAY_MS)))
        env.fake.on("GET", "/v1/me", json(200, UserDto.serializer(), user))
        check(auth.signInDev("arjun", user.name) is SignInResult.Success)
    }

    /** True while no call of the session is on its way (nor its answer being handled). */
    fun idle(): Boolean = client.dispatcher.runningCallsCount() == 0 && client.dispatcher.queuedCallsCount() == 0

    /** Hooks that record each call; [flushed] is what sending the outbox answers. */
    class RecordingHooks : AuthHooks {
        /** Whether everything waiting could be sent. */
        @Volatile var flushed = true

        /** The calls, in order. */
        val calls = CopyOnWriteArrayList<String>()

        override suspend fun flushOutbox(): Boolean {
            calls += "flushOutbox"
            return flushed
        }

        override suspend fun clearGroupData() {
            calls += "clearGroupData"
        }

        override suspend fun switchAccount() {
            calls += "switchAccount"
        }

        override suspend fun onSignedIn() {
            calls += "onSignedIn"
        }

        override suspend fun onSignedOut() {
            calls += "onSignedOut"
        }

        override fun clearAppData() {
            calls += "clearAppData"
        }
    }

    private object PlainCipher : ValueCipher {
        override fun encrypt(plain: String): String = plain

        override fun decrypt(stored: String): String = stored
    }

    private object NoGoogle : GoogleIdTokens {
        override val configured: Boolean = false

        override suspend fun idToken(activity: Activity, nonce: String, auto: Boolean, autoSelect: Boolean): GoogleResult =
            GoogleResult.Failed

        override suspend fun clearState() = Unit
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}

/** The JSON body of a recorded request. */
internal fun RecordedRequest.jsonBody(): JsonObject = ApiJson.parseToJsonElement(body.readUtf8()).jsonObject
