package com.expenses.app.groups

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.auth.SentPushToken
import com.expenses.app.groups.auth.TokenStore
import com.expenses.app.groups.auth.Tokens
import com.expenses.app.groups.auth.ValueCipher
import com.expenses.app.groups.push.PushClient
import com.expenses.app.groups.push.PushSetup
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Firebase as the test sets it: [token] is what it gives. */
private class FakePush(override val configured: Boolean = true, @Volatile var token: String? = "fcm-1") : PushClient {
    var starts = 0
    var tokenCalls = 0
    var stops = 0

    override fun start(context: Context) {
        starts++
    }

    override suspend fun token(): String? {
        tokenCalls++
        return token
    }

    override fun stop() {
        stops++
    }
}

/** A clock the test moves. */
private class PushClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("Asia/Kolkata")

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

private object PlainCipher : ValueCipher {
    override fun encrypt(plain: String) = plain

    override fun decrypt(stored: String) = stored
}

@RunWith(RobolectricTestRunner::class)
class PushSetupTest {
    private val env = GroupsTestEnv()
    private val folder: File = Files.createTempDirectory("push").toFile()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = TokenStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(folder, "auth.preferences_pb") }, PlainCipher)
    private val firebase = FakePush()
    private val clock = PushClock(Instant.parse("2026-11-16T06:30:00Z"))

    @After fun close() {
        storeScope.cancel()
        env.close()
        folder.deleteRecursively()
    }

    /** A [PushSetup] as a new process of the app makes it, started. */
    private fun push(client: PushClient = firebase): PushSetup =
        PushSetup(api = { env.api }, store = store, client = client, clock = clock).apply { init(RuntimeEnvironment.getApplication()) }

    private suspend fun signIn(userId: String) =
        store.saveSignIn(userId, "Alice", null, null, Tokens("a1", Long.MAX_VALUE, "r1", Long.MAX_VALUE))

    private fun devicePuts() = env.fake.requests.filter { it.method == "PUT" && it.requestUrl!!.encodedPath.startsWith("/v1/devices/") }

    @Test fun eachStartWhileSignedInSendsTheTokenOnlyWhenTheServerDoesNotHaveIt() = runTest {
        env.fake.on("PUT", "/v1/devices/${store.installationId()}", MockResponse().setResponseCode(204))
        signIn("alice")

        push().register()
        // The app starts again (a push, a worker, the app reopened) with the same token: nothing is sent.
        push().register()
        push().sendToken("fcm-1")

        val put = devicePuts().single()
        assertEquals("fcm-1", ApiJson.parseToJsonElement(put.body.readUtf8()).jsonObject["fcmToken"]!!.jsonPrimitive.content)
        assertEquals(SentPushToken("alice", "fcm-1", clock.millis()), store.sentPushToken())
    }

    @Test fun aTokenAcceptedLongAgoIsSentAgain() = runTest {
        env.fake.on("PUT", "/v1/devices/${store.installationId()}", MockResponse().setResponseCode(204))
        signIn("alice")
        push().register()
        val firstSent = clock.millis()

        // Just inside the limit: still nothing.
        clock.now = clock.now.plus(PushSetup.RESEND_AFTER).minusMillis(1)
        push().register()
        assertEquals(1, devicePuts().size)

        // At the limit: sent once more, as the server may have dropped the row. Then nothing again.
        clock.now = clock.now.plusMillis(1)
        push().register()
        push().register()

        assertEquals(2, devicePuts().size)
        assertEquals(SentPushToken("alice", "fcm-1", firstSent + PushSetup.RESEND_AFTER.toMillis()), store.sentPushToken())
    }

    @Test fun aSavedTimeInTheFutureCountsAsOld() = runTest {
        env.fake.on("PUT", "/v1/devices/${store.installationId()}", MockResponse().setResponseCode(204))
        signIn("alice")
        push().register()

        // The phone's clock was set back after the send.
        clock.now = clock.now.minusSeconds(60)
        push().register()

        assertEquals(2, devicePuts().size)
        assertEquals(SentPushToken("alice", "fcm-1", clock.millis()), store.sentPushToken())
    }

    @Test fun aNewTokenIsSent() = runTest {
        env.fake.on("PUT", "/v1/devices/${store.installationId()}", MockResponse().setResponseCode(204))
        signIn("alice")
        push().register()

        firebase.token = "fcm-2"
        push().register()

        assertEquals(2, devicePuts().size)
        assertEquals(SentPushToken("alice", "fcm-2", clock.millis()), store.sentPushToken())
    }

    @Test fun aFailedPutKeepsNothingAndTheNextStartSendsAgain() = runTest {
        env.fake.on("PUT", "/v1/devices/${store.installationId()}", error(503, "internal"), MockResponse().setResponseCode(204))
        signIn("alice")

        push().register()
        assertNull(store.sentPushToken())

        push().register()
        assertEquals(2, devicePuts().size)
        assertEquals(SentPushToken("alice", "fcm-1", clock.millis()), store.sentPushToken())
    }

    @Test fun nothingIsSentWhileSignedOut() = runTest {
        val push = push()

        push.register()
        push.sendToken("fcm-1")

        assertEquals(0, firebase.tokenCalls)
        assertTrue(devicePuts().isEmpty())
        assertNull(store.sentPushToken())
    }

    @Test fun withoutAFirebaseProjectNothingRuns() = runTest {
        val off = FakePush(configured = false)
        signIn("alice")
        val push = push(off)

        push.register()
        push.sendToken("fcm-1")
        push.unregister()

        assertEquals(0, off.starts + off.tokenCalls + off.stops)
        assertTrue(devicePuts().isEmpty())
    }

    @Test fun unregisterForgetsTheTokenSoTheNextSignInSendsItAgain() = runTest {
        env.fake.on("PUT", "/v1/devices/${store.installationId()}", MockResponse().setResponseCode(204))
        signIn("alice")
        val push = push()
        push.register()

        push.unregister()

        assertNull(store.sentPushToken())
        assertEquals(1, firebase.stops)
        push.register()
        assertEquals(2, devicePuts().size)
    }

    @Test fun anotherAccountSendsTheTokenAgain() = runTest {
        env.fake.on("PUT", "/v1/devices/${store.installationId()}", MockResponse().setResponseCode(204))
        signIn("alice")
        val push = push()
        push.register()

        // The hook of an account switch forgets the token; Bob's sign-in then sends it for him.
        push.forgetSentToken()
        assertNull(store.sentPushToken())
        signIn("bob")
        push.register()
        // Even without the hook, the saved token belongs to Bob, not to whoever signs in next.
        signIn("carol")
        push.register()

        assertEquals(3, devicePuts().size)
        assertEquals(SentPushToken("carol", "fcm-1", clock.millis()), store.sentPushToken())
    }

    @Test fun aTokenThatArrivesTwiceAtOnceIsSentOnce() = runTest {
        env.fake.on("PUT", "/v1/devices/${store.installationId()}", MockResponse().setResponseCode(204).setHeadersDelay(300, TimeUnit.MILLISECONDS))
        signIn("alice")
        val push = push()

        // Sign-in's own registration and Firebase's onRegistered give the same token at the same time.
        withContext(Dispatchers.IO) {
            launch { push.register() }
            launch { push.sendToken("fcm-1") }
        }

        assertEquals(1, devicePuts().size)
        assertEquals(SentPushToken("alice", "fcm-1", clock.millis()), store.sentPushToken())
    }

    @Test fun aSignOutWhileTheTokenIsOnItsWayKeepsNothing() = runTest {
        env.fake.on("PUT", "/v1/devices/${store.installationId()}", MockResponse().setResponseCode(204).setHeadersDelay(1, TimeUnit.SECONDS))
        signIn("alice")
        val push = push()

        withContext(Dispatchers.IO) {
            val sending = launch { push.sendToken("fcm-1") }
            assertNotNull(env.server.takeRequest(5, TimeUnit.SECONDS))
            push.unregister()
            sending.join()
        }

        assertNull(store.sentPushToken())
    }
}
