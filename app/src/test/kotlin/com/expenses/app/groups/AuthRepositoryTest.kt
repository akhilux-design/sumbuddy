package com.expenses.app.groups

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.expenses.app.data.category.Category
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.AuthResponse
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.api.SetTo
import com.expenses.app.groups.api.TokenPair
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.auth.AuthHooks
import com.expenses.app.groups.auth.AuthRepository
import com.expenses.app.groups.auth.AuthState
import com.expenses.app.groups.auth.GoogleIdTokens
import com.expenses.app.groups.auth.GoogleResult
import com.expenses.app.groups.auth.SentPushToken
import com.expenses.app.groups.auth.SignInResult
import com.expenses.app.groups.auth.SignOutResult
import com.expenses.app.groups.auth.TokenStore
import com.expenses.app.groups.auth.ValueCipher
import com.expenses.app.groups.auth.devSignIn
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.splits.SplitMethod
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Values marked as encrypted, so a test can see that nothing is stored in the clear. */
private object MarkingCipher : ValueCipher {
    override fun encrypt(plain: String): String = "enc:" + plain.reversed()

    override fun decrypt(stored: String): String? = stored.removePrefix("enc:").reversed()
}

/** A clock the test moves. */
private class MovableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("Asia/Kolkata")

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

private class FakeGoogle(
    override val configured: Boolean = true,
    private val result: GoogleResult = GoogleResult.Token("google-id-token"),
    private val calls: MutableList<String> = mutableListOf(),
) : GoogleIdTokens {
    var nonce: String? = null

    /** The autoSelect of each request, in order. */
    val autoSelects = mutableListOf<Boolean>()

    var cleared = 0

    override suspend fun idToken(activity: Activity, nonce: String, auto: Boolean, autoSelect: Boolean): GoogleResult {
        this.nonce = nonce
        autoSelects += autoSelect
        return result
    }

    override suspend fun clearState() {
        cleared++
        calls += "clearState"
    }
}

private class FakeHooks : AuthHooks {
    /** clearAppData, and whatever a test adds, in order. */
    val calls = mutableListOf<String>()
    var flushed = true
    var signedIn = 0
    var signedOut = 0
    var cleared = 0
    var switched = 0

    override suspend fun flushOutbox(): Boolean = flushed

    override suspend fun clearGroupData() {
        cleared++
    }

    override suspend fun switchAccount() {
        switched++
    }

    override suspend fun onSignedIn() {
        signedIn++
    }

    override suspend fun onSignedOut() {
        signedOut++
    }

    override fun clearAppData() {
        calls += "clearAppData"
    }
}

@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {
    private val env = GroupsTestEnv()
    private val folder: File = Files.createTempDirectory("auth").toFile()
    private val clock = MovableClock(Instant.parse("2026-11-16T06:30:00Z"))
    private val hooks = FakeHooks()
    private lateinit var store: TokenStore
    private lateinit var settings: SettingsRepository

    @After fun close() {
        env.close()
        folder.deleteRecursively()
    }

    private fun TestScope.auth(google: GoogleIdTokens = FakeGoogle(), client: OkHttpClient = OkHttpClient()): AuthRepository {
        store = TokenStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder, "auth.preferences_pb") }, MarkingCipher)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder, "settings.preferences_pb") })
        return newProcess(google, client)
    }

    /** Runs [block] in real time, not the test's virtual time, giving up after 5 s. */
    private suspend fun inRealTime(block: suspend () -> Unit) = withContext(Dispatchers.Default) { withTimeout(5_000) { block() } }

    /** Another [AuthRepository] on the same stored session: what a new process of the app starts with. */
    private fun TestScope.newProcess(google: GoogleIdTokens = FakeGoogle(), client: OkHttpClient = OkHttpClient()): AuthRepository {
        lateinit var repo: AuthRepository
        val api = GroupsApi(env.server.url("/").toString(), client, { repo })
        repo = AuthRepository(api, store, google, devSignIn(api), settings, hooks, clock, backgroundScope)
        return repo
    }

    private fun signedIn(user: String, access: String = "a1", accessLife: Long = 86_400_000, refresh: String = "r1", isNew: Boolean = true) =
        json(
            200, AuthResponse.serializer(),
            AuthResponse(UserDto(user, user.replaceFirstChar(Char::titlecase)), isNew, access, clock.millis() + accessLife, refresh, clock.millis() + 60L * 86_400_000),
        )

    private fun pair(access: String, refresh: String) =
        json(200, TokenPair.serializer(), TokenPair(access, clock.millis() + 86_400_000, refresh, clock.millis() + 60L * 86_400_000))

    @Test fun devSignInStoresTheSessionEncrypted() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))

        assertEquals(SignInResult.Success(isNew = true, name = "Alice", phone = null), auth.signInDev("alice", "Alice"))

        assertEquals(AuthState.SignedIn("alice", "Alice"), auth.state.first { it is AuthState.SignedIn })
        assertEquals("r1", store.current().refreshToken)
        assertEquals(1, hooks.signedIn)
        assertEquals("a1", auth.accessToken())
        val body = ApiJson.parseToJsonElement(env.fake.requests.single().body.readUtf8()).jsonObject
        assertEquals("alice", body["devUser"]!!.jsonPrimitive.content)
        assertEquals(auth.installationId(), body["installationId"]!!.jsonPrimitive.content)
        // Nothing of the session is written in the clear.
        val raw = File(folder, "auth.preferences_pb").readText(Charsets.ISO_8859_1)
        assertTrue(!raw.contains("alice") && !raw.contains("Alice"))
    }

    @Test fun theProfileWithAUpiIdIsOneRequestAndIsKept() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        auth.signInDev("alice", "Alice")
        env.fake.on("PATCH", "/v1/me", json(200, UserDto.serializer(), UserDto("alice", "Alice M", "+919876543210", "alice@okaxis")))

        assertEquals(null, auth.saveProfile("Alice M", "+919876543210", upiId = SetTo("alice@okaxis")))

        val body = ApiJson.parseToJsonElement(env.fake.requestsTo("PATCH", "/v1/me").single().body.readUtf8()).jsonObject
        assertEquals(setOf("name", "phone", "upiId"), body.keys)
        assertEquals("alice@okaxis", body["upiId"]!!.jsonPrimitive.content)
        assertEquals("alice@okaxis", auth.profile()!!.upiId)
        // Without a UPI ID argument the field is left as it is on the server.
        auth.saveProfile("Alice M", null)
        val second = ApiJson.parseToJsonElement(env.fake.requestsTo("PATCH", "/v1/me").last().body.readUtf8()).jsonObject
        assertEquals(setOf("name", "phone"), second.keys)
    }

    @Test fun anAccessTokenNearItsEndIsRefreshedOnceForEveryone() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice", accessLife = 30_000))
        env.fake.on("POST", "/v1/auth/refresh", pair("a2", "r2"))
        auth.signInDev("alice", "Alice")

        val tokens = (1..5).map { async { auth.accessToken() } }.awaitAll()

        assertEquals(List(5) { "a2" }, tokens)
        assertEquals(1, env.fake.requestsTo("POST", "/v1/auth/refresh").size)
        assertEquals("r2", store.current().refreshToken)
    }

    @Test fun aLostRefreshAnswerKeepsTheTokenForTheNextTry() = runTest {
        // OkHttp would otherwise repeat the request by itself, which the server also accepts (10 minute window).
        val auth = auth(client = OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice", accessLife = 0))
        env.fake.on(
            "POST", "/v1/auth/refresh",
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            pair("a2", "r2"),
        )
        auth.signInDev("alice", "Alice")

        assertNull(auth.accessToken())
        assertEquals("r1", store.current().refreshToken)
        assertTrue(store.current().signedIn)
        assertEquals("a2", auth.accessToken())

        val sent = env.fake.requestsTo("POST", "/v1/auth/refresh").map {
            ApiJson.parseToJsonElement(it.body.readUtf8()).jsonObject["refreshToken"]!!.jsonPrimitive.content
        }
        // The server takes the previous token again within 10 minutes and answers with a new pair.
        assertEquals(listOf("r1", "r1"), sent)
        assertEquals("r2", store.current().refreshToken)
    }

    @Test fun aNewProcessReusesTheStoredAccessTokenWithoutARefresh() = runTest {
        auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice", access = "a1", accessLife = 86_400_000))
        env.fake.on("GET", "/v1/me", json(200, UserDto.serializer(), UserDto("alice", "Alice")))
        env.fake.on("POST", "/v1/auth/refresh", pair("a2", "r2"))
        newProcess().signInDev("alice", "Alice")

        // The app is opened again in a new process (a push, the periodic sync): the stored token still has a day.
        val later = newProcess()
        val api = GroupsApi(env.server.url("/").toString(), OkHttpClient(), { later })
        assertTrue(api.me() is ApiResult.Ok)

        assertEquals("Bearer a1", env.fake.requestsTo("GET", "/v1/me").single().getHeader("Authorization"))
        assertTrue(env.fake.requestsTo("POST", "/v1/auth/refresh").isEmpty())
        // Near its end it is refreshed as before, and the new one is stored.
        clock.now = clock.now.plusMillis(86_400_000 - 30_000)
        assertEquals("a2", newProcess().accessToken())
        assertEquals("a2", store.current().accessToken)
        assertEquals(1, env.fake.requestsTo("POST", "/v1/auth/refresh").size)
    }

    @Test fun aRefreshThatFailsForNowIsNotASignOut() = runTest {
        val auth = auth(client = OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice", accessLife = 0))
        env.fake.on(
            "POST", "/v1/auth/refresh",
            error(503, "internal"),
            MockResponse().setResponseCode(429).setBody("<html>Cloudflare</html>"),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        )
        auth.signInDev("alice", "Alice")
        val api = GroupsApi(env.server.url("/").toString(), OkHttpClient(), { auth })

        val serverError = (api.me() as ApiResult.Error).error
        assertEquals(503, serverError.status)
        assertTrue(serverError.isTransient && !serverError.needsSignIn)
        val notOurs = (api.me() as ApiResult.Error).error
        assertTrue(notOurs.isTransient && !notOurs.needsSignIn)
        assertTrue((api.me() as ApiResult.Error).error.isOffline)

        assertTrue(env.fake.requestsTo("GET", "/v1/me").isEmpty())
        assertTrue(auth.isSignedIn())
        assertEquals("r1", store.current().refreshToken)
        assertEquals(0, hooks.signedOut)
    }

    @Test fun theAuthenticatorTurnsARefreshThatFailsForNowIntoAnOfflineError() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        env.fake.on("GET", "/v1/me", error(401, "token_expired"))
        env.fake.on("POST", "/v1/auth/refresh", error(502, "internal"))
        auth.signInDev("alice", "Alice")
        val api = GroupsApi(env.server.url("/").toString(), OkHttpClient(), { auth })

        val error = (api.me() as ApiResult.Error).error

        assertTrue(error.isOffline)
        assertTrue(auth.isSignedIn())
        assertEquals(0, hooks.signedOut)
    }

    @Test fun aRefusedRefreshTokenNeedsSignInButKeepsGroupsAndTheOutbox() = runTest {
        val google = FakeGoogle()
        val auth = auth(google)
        env.seedGroup()
        env.groups.addGroupExpense(
            G1,
            GroupExpenseDraft("Dinner", Category.FOOD, 30_000, LocalDate.of(2026, 11, 12), M_RAHUL, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null)),
        )
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice", accessLife = 0))
        env.fake.on("POST", "/v1/auth/refresh", error(401, "refresh_invalid"))
        auth.signInDev("alice", "Alice")

        assertNull(auth.accessToken())

        assertEquals(AuthState.SignedOut, auth.state.first { it is AuthState.SignedOut })
        assertNull(store.current().refreshToken)
        assertEquals("alice", store.current().lastUserId)
        assertEquals(1, env.outbox.entries().size)
        assertNotNull(env.db.groupDao().getGroup(G1))
        assertEquals(0, hooks.cleared)
        assertEquals(1, hooks.signedOut)
        // Not a sign-out the person chose: Google's state stays, and its sheet may still sign them in without a tap.
        assertEquals(0, google.cleared)
        assertFalse(store.current().signedOutByUser)
        // Signing in again as the same person goes on with the same data.
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice", refresh = "r3", isNew = false))
        auth.signInDev("alice", "Alice")
        assertEquals(0, hooks.switched)
    }

    @Test fun theAuthenticatorRefreshesOnceAfterA401AndRepeatsTheRequest() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        env.fake.on("GET", "/v1/me", error(401, "token_expired"), json(200, UserDto.serializer(), UserDto("alice", "Alice")))
        env.fake.on("POST", "/v1/auth/refresh", pair("a2", "r2"))
        auth.signInDev("alice", "Alice")
        val api = GroupsApi(env.server.url("/").toString(), OkHttpClient(), { auth })

        val me = api.me()

        assertTrue(me is ApiResult.Ok)
        val calls = env.fake.requests.drop(1).map { "${it.method} ${it.requestUrl!!.encodedPath} ${it.getHeader("Authorization")}" }
        assertEquals(listOf("GET /v1/me Bearer a1", "POST /v1/auth/refresh null", "GET /v1/me Bearer a2"), calls)
    }

    @Test fun aDifferentAccountDropsTheOldAccountsGroupDataFirst() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        env.fake.on("POST", "/v1/auth/signout", MockResponse().setResponseCode(204))
        auth.signInDev("alice", "Alice")
        assertEquals(SignOutResult.DONE, auth.signOut())
        assertEquals(1, hooks.cleared)
        env.fake.on("POST", "/v1/auth/dev", signedIn("bob"))
        auth.signInDev("bob", "Bob")
        assertEquals(1, hooks.switched)
        assertEquals("bob", store.current().lastUserId)
    }

    @Test fun signOutWithUnsentChangesAsksFirst() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        env.fake.on("POST", "/v1/auth/signout", MockResponse().setResponseCode(204))
        auth.signInDev("alice", "Alice")
        hooks.flushed = false

        assertEquals(SignOutResult.UNSENT_CHANGES, auth.signOut())
        assertTrue(auth.isSignedIn())
        assertEquals(SignOutResult.DONE, auth.signOut(force = true))
        assertTrue(!auth.isSignedIn())
        assertEquals(1, env.fake.requestsTo("POST", "/v1/auth/signout").size)
    }

    @Test fun googleSignInSendsTheIdTokenWithTheServersNonce() = runTest {
        val google = FakeGoogle()
        val auth = auth(google)
        env.fake.on("POST", "/v1/auth/nonce", json(200, """{"nonce":"n-123","expiresAt":0}"""))
        env.fake.on("POST", "/v1/auth/google", signedIn("google-user"))
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        val result = auth.signInWithGoogle(activity, auto = false)

        assertTrue(result is SignInResult.Success)
        assertEquals("n-123", google.nonce)
        val body = ApiJson.parseToJsonElement(env.fake.requestsTo("POST", "/v1/auth/google").single().body.readUtf8()).jsonObject
        assertEquals("google-id-token", body["idToken"]!!.jsonPrimitive.content)
        assertEquals("n-123", body["nonce"]!!.jsonPrimitive.content)
    }

    @Test fun aRefusedGoogleSignInChangesNothing() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/nonce", json(200, """{"nonce":"n-1"}"""))
        env.fake.on("POST", "/v1/auth/google", error(401, "google_token_invalid"))
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        assertEquals(SignInResult.Failed, auth.signInWithGoogle(activity, auto = false))
        // The sheet that opens by itself fails silently.
        assertEquals(SignInResult.Cancelled, auth.signInWithGoogle(activity, auto = true))
        assertEquals(AuthState.SignedOut, auth.state.first { it != AuthState.Loading })
        assertEquals(0, hooks.signedIn)
    }

    @Test fun withoutAGoogleClientIdSignInSaysSo() = runTest {
        val auth = auth(FakeGoogle(configured = false))
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertEquals(SignInResult.NotConfigured, auth.signInWithGoogle(activity, auto = false))
        assertTrue(env.fake.requests.isEmpty())
    }

    @Test fun signOutClearsGooglesStateAndTheNextAutomaticSheetWaitsForATap() = runTest {
        val google = FakeGoogle()
        val auth = auth(google)
        env.fake.on("POST", "/v1/auth/nonce", json(200, """{"nonce":"n-1"}"""))
        env.fake.on("POST", "/v1/auth/google", signedIn("alice", isNew = false))
        env.fake.on("POST", "/v1/auth/signout", MockResponse().setResponseCode(204))
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        // A new install: the automatic sheet may pick the one account used before.
        assertTrue(auth.signInWithGoogle(activity, auto = true) is SignInResult.Success)
        assertEquals(SignOutResult.DONE, auth.signOut())
        assertEquals(1, google.cleared)
        assertTrue(store.current().signedOutByUser)

        // After a sign-out it waits for a tap, also in a new process; the button never picks by itself.
        assertTrue(newProcess(google).signInWithGoogle(activity, auto = true) is SignInResult.Success)
        assertFalse(store.current().signedOutByUser)
        assertTrue(auth.signInWithGoogle(activity, auto = true) is SignInResult.Success)
        assertTrue(auth.signInWithGoogle(activity, auto = false) is SignInResult.Success)
        assertEquals(listOf(true, false, true, false), google.autoSelects)
    }

    @Test fun signOutWithUnsentChangesKeepsGooglesState() = runTest {
        val google = FakeGoogle()
        val auth = auth(google)
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        auth.signInDev("alice", "Alice")
        hooks.flushed = false

        assertEquals(SignOutResult.UNSENT_CHANGES, auth.signOut())

        assertEquals(0, google.cleared)
        assertFalse(store.current().signedOutByUser)
    }

    @Test fun signOutForgetsThePushTokenTheServerHad() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        env.fake.on("POST", "/v1/auth/signout", MockResponse().setResponseCode(204))
        auth.signInDev("alice", "Alice")
        store.saveSentPushToken(SentPushToken("alice", "fcm-1", sentAt = 0))

        auth.signOut()

        assertNull(store.sentPushToken())
    }

    @Test fun deleteAccountClearsGooglesStateBeforeTheAppData() = runTest {
        val google = FakeGoogle(calls = hooks.calls)
        val auth = auth(google)
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        env.fake.on("DELETE", "/v1/me", MockResponse().setResponseCode(204))
        auth.signInDev("alice", "Alice")

        assertNull(auth.deleteAccount())

        // Clearing the app data ends the process, so Google's state must be cleared first.
        assertEquals(listOf("clearState", "clearAppData"), hooks.calls)
    }

    @Test fun aRefusedAccountDeletionKeepsGooglesState() = runTest {
        val google = FakeGoogle(calls = hooks.calls)
        val auth = auth(google)
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        env.fake.on("DELETE", "/v1/me", error(409, "has_balances"))
        auth.signInDev("alice", "Alice")

        assertNotNull(auth.deleteAccount())

        assertTrue(hooks.calls.isEmpty())
        assertTrue(auth.isSignedIn())
    }

    @Test fun theProfileIsReadAgainFromTheServer() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        auth.signInDev("alice", "Alice")
        // Alice changed her profile on her other phone.
        val changed = UserDto("alice", "Alice Mehta", "+919876543210", "alice@okaxis")
        env.fake.on("GET", "/v1/me", json(200, UserDto.serializer(), changed))

        assertEquals(changed, auth.refreshProfile())

        assertEquals(changed, auth.profile())
        assertEquals(AuthState.SignedIn("alice", "Alice Mehta"), auth.state.first { it == AuthState.SignedIn("alice", "Alice Mehta") })
        // A rename reaches the name used on Home, as a save on this phone does.
        assertEquals("Alice Mehta", settings.settings.first().userName)
        assertEquals(1, env.fake.requestsTo("GET", "/v1/me").size)
    }

    @Test fun theSameNameOnTheServerLeavesTheHomeNameAsItIs() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        auth.signInDev("alice", "Alice")
        // The name used on Home was changed on this phone only.
        settings.setUserName("Ali")
        env.fake.on("GET", "/v1/me", json(200, UserDto.serializer(), UserDto("alice", "Alice", null, "alice@ybl")))

        assertEquals("alice@ybl", auth.refreshProfile()!!.upiId)

        assertEquals("Ali", settings.settings.first().userName)
    }

    @Test fun theAppsStartReadsTheProfileAtMostOnceInTenMinutes() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        env.fake.on("GET", "/v1/me", json(200, UserDto.serializer(), UserDto("alice", "Alice")))
        fun reads() = env.fake.requestsTo("GET", "/v1/me").size
        auth.signInDev("alice", "Alice")

        // The sign-in gave the profile just now.
        clock.now = clock.now.plusSeconds(9 * 60)
        assertNull(auth.refreshProfile(onlyIfDue = true))
        assertEquals(0, reads())

        clock.now = clock.now.plusSeconds(60)
        assertNotNull(auth.refreshProfile(onlyIfDue = true))
        assertEquals(1, reads())
        assertNull(auth.refreshProfile(onlyIfDue = true))
        // Also in a new process: the time is stored.
        clock.now = clock.now.plusSeconds(5 * 60)
        assertNull(newProcess().refreshProfile(onlyIfDue = true))
        assertEquals(1, reads())

        // A read that got no answer counts too.
        clock.now = clock.now.plusSeconds(5 * 60)
        env.fake.on("GET", "/v1/me", error(503, "internal"))
        assertNull(auth.refreshProfile(onlyIfDue = true))
        assertEquals(2, reads())
        clock.now = clock.now.plusSeconds(60)
        assertNull(auth.refreshProfile(onlyIfDue = true))
        assertEquals(2, reads())

        // Account asks every time it opens.
        env.fake.on("GET", "/v1/me", json(200, UserDto.serializer(), UserDto("alice", "Alice")))
        assertNotNull(auth.refreshProfile())
        assertEquals(3, reads())

        // A clock set back does not hold the next read off.
        clock.now = clock.now.minusSeconds(60 * 60)
        assertNotNull(auth.refreshProfile(onlyIfDue = true))
        assertEquals(4, reads())
    }

    @Test fun nothingIsReadWhileSignedOut() = runTest {
        val auth = auth()

        assertNull(auth.refreshProfile())
        assertNull(auth.refreshProfile(onlyIfDue = true))

        assertTrue(env.fake.requests.isEmpty())
    }

    @Test fun aSaveThatLandsWhileTheProfileIsReadIsKept() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        auth.signInDev("alice", "Alice")
        // The server read the profile before the save reached it, and its answer is slow.
        env.fake.on("GET", "/v1/me", json(200, UserDto.serializer(), UserDto("alice", "Alice")).setHeadersDelay(1, TimeUnit.SECONDS))
        val saved = UserDto("alice", "Alice M", null, "alice@okaxis")
        env.fake.on("PATCH", "/v1/me", json(200, UserDto.serializer(), saved))

        val read = async { auth.refreshProfile() }
        inRealTime { while (env.fake.requestsTo("GET", "/v1/me").isEmpty()) delay(10) }
        assertNull(auth.saveProfile("Alice M", null, SetTo("alice@okaxis")))

        assertNull(read.await())
        assertEquals(saved, auth.profile())
        assertEquals("Alice M", settings.settings.first().userName)
    }

    @Test fun aProfileThatComesAfterSignOutIsDropped() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice"))
        env.fake.on("POST", "/v1/auth/signout", MockResponse().setResponseCode(204))
        auth.signInDev("alice", "Alice")
        env.fake.on("GET", "/v1/me", json(200, UserDto.serializer(), UserDto("alice", "Alice M", "+919876543210", null)).setHeadersDelay(1, TimeUnit.SECONDS))

        val read = async { auth.refreshProfile() }
        inRealTime { while (env.fake.requestsTo("GET", "/v1/me").isEmpty()) delay(10) }
        assertEquals(SignOutResult.DONE, auth.signOut())

        assertNull(read.await())
        assertNull(auth.profile())
        val stored = store.current()
        assertNull(stored.name)
        assertNull(stored.phone)
    }

    @Test fun aRefreshIsNotCutOffWhenItsCallerGoes() = runTest {
        val auth = auth()
        env.fake.on("POST", "/v1/auth/dev", signedIn("alice", accessLife = 0))
        env.fake.on("POST", "/v1/auth/refresh", pair("a2", "r2").setHeadersDelay(500, TimeUnit.MILLISECONDS))
        auth.signInDev("alice", "Alice")

        // A screen asks for something, then closes while the refresh is on its way.
        val caller = launch { auth.accessToken() }
        inRealTime { while (env.fake.requestsTo("POST", "/v1/auth/refresh").isEmpty()) delay(10) }
        caller.cancel()

        // The server has turned r1 over: the new pair is kept, or only the old token would be left.
        inRealTime { while (store.current().refreshToken != "r2") delay(10) }
        assertEquals("a2", store.current().accessToken)
        assertEquals("a2", auth.accessToken())
        assertEquals(1, env.fake.requestsTo("POST", "/v1/auth/refresh").size)
    }

    @Test fun eachInstallHasOneId() = runTest {
        val auth = auth()
        val first = auth.installationId()
        assertEquals(first, auth.installationId())
        assertNotEquals("", first)
    }
}
