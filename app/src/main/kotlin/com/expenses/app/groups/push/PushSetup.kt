package com.expenses.app.groups.push

import android.content.Context
import com.expenses.app.BuildConfig
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.auth.SentPushToken
import com.expenses.app.groups.auth.TokenStore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Firebase calls of [PushSetup]. [FirebasePush] is the real one; tests pass a fake. */
interface PushClient {
    /** True when this build has a Firebase project for push. */
    val configured: Boolean

    /** Starts Firebase for messaging only. */
    fun start(context: Context)

    /** Turns Firebase's token sync on and gives this install's token, or null when Firebase has none now. */
    suspend fun token(): String?

    /** Turns token sync off and deletes the token. */
    fun stop()
}

/** [PushClient] through Firebase, set up from the four `app.fcm.*` values instead of `google-services.json`. */
class FirebasePush(
    private val projectId: String = BuildConfig.FCM_PROJECT_ID,
    private val applicationId: String = BuildConfig.FCM_APPLICATION_ID,
    private val apiKey: String = BuildConfig.FCM_API_KEY,
    private val senderId: String = BuildConfig.FCM_SENDER_ID,
) : PushClient {
    override val configured: Boolean
        get() = listOf(projectId, applicationId, apiKey, senderId).all { it.isNotBlank() }

    override fun start(context: Context) {
        val options = FirebaseOptions.Builder()
            .setProjectId(projectId)
            .setApplicationId(applicationId)
            .setApiKey(apiKey)
            .setGcmSenderId(senderId)
            .build()
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context, options)
    }

    // getToken and deleteToken are deprecated in firebase-messaging 26 in favour of register(), whose token arrives
    // only in the service's onRegistered. They still work and give the token at once; both paths are handled.
    @Suppress("DEPRECATION")
    override suspend fun token(): String? {
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        return suspendCancellableCoroutine { continuation ->
            messaging.token.addOnCompleteListener { task ->
                continuation.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun stop() {
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = false
        messaging.deleteToken()
    }
}

/**
 * Push without `google-services.json` (docs/server.md 9.6). Firebase is started by hand, and only when all four
 * `app.fcm.*` values are set; otherwise push is off and the app keeps up through foreground syncs, the live room and
 * the 12-hour sync. Every Firebase call goes through here, behind [enabled].
 *
 * The token goes to the server (`PUT /v1/devices/{installationId}`) on sign-in, on each app start and return to the
 * foreground while signed in, and when Firebase gives a new one. The token the server last accepted is kept with its
 * user and the time, and the same token for the same user is not sent again for [RESEND_AFTER]: a push, a worker or
 * the SMS receiver can each start the app, and each PUT is a database write. After that it is sent again, because the
 * server can drop the row without telling the phone: it keeps only the 3 most recently sent devices of a user, and
 * deletes a token that Firebase reports as gone. Firebase advises sending the token about once a month, and more often
 * than once a week gains nothing. A PUT that fails keeps nothing, so the next start or return sends it again.
 *
 * Push messages carry only ids and a version; the phone syncs and writes any notification itself.
 *
 * @param api the server's API; a function so nothing of it is made while no PUT is needed.
 * @param store the install id, who is signed in, and the token the server last accepted.
 * @param clock when a token is sent, and how long ago the saved one was.
 */
class PushSetup(
    private val api: () -> GroupsApi,
    private val store: TokenStore,
    private val client: PushClient = FirebasePush(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** True when this build has a Firebase project for push. */
    val enabled: Boolean get() = client.configured

    @Volatile private var started = false

    /** One send at a time, so a token that arrives twice at once is sent once. */
    private val sendLock = Mutex()

    /**
     * Guards writes of the saved token. Never held during a request: sign-out can run while a send waits for its
     * token, and must not wait for that send.
     */
    private val savedLock = Mutex()

    /** Counts [forgetSentToken] calls, so a send that ends after one does not save its token again. */
    private val forgets = AtomicInteger()

    /** Starts Firebase for messaging only. Call once from the application; does nothing when push is off. */
    fun init(context: Context) {
        if (!enabled || started) return
        client.start(context)
        started = true
    }

    /**
     * Signed in (a sign-in, or the app started or came back while signed in): turns push on and sends this install's
     * token unless the server accepted it recently. Does nothing when push is off or nobody is signed in.
     */
    suspend fun register() {
        if (!enabled || !started || !store.current().signedIn) return
        val token = client.token() ?: return
        sendToken(token)
    }

    /**
     * Sends [token] (from [register], or a new one from [GroupsMessagingService]) while someone is signed in, unless
     * the server accepted it for them less than [RESEND_AFTER] ago. It is saved, with the time, only when the server
     * accepts it.
     */
    suspend fun sendToken(token: String) {
        if (!enabled) return
        sendLock.withLock {
            val session = store.current()
            val userId = session.userId
            if (!session.signedIn || userId == null) return
            val now = clock.millis()
            val saved = store.sentPushToken()
            // A saved time in the future (the phone's clock was changed) counts as old.
            val sentRecently = saved != null && saved.userId == userId && saved.token == token &&
                now - saved.sentAt in 0 until RESEND_AFTER.toMillis()
            if (sentRecently) return
            val forgetsBefore = forgets.get()
            val result = api().putDevice(store.installationId(), token)
            if (result !is ApiResult.Ok) return
            savedLock.withLock {
                if (forgets.get() == forgetsBefore) store.saveSentPushToken(SentPushToken(userId, token, now))
            }
        }
    }

    /** Forgets the token the server last accepted (another account signed in), so the next one is sent. */
    suspend fun forgetSentToken() {
        savedLock.withLock {
            forgets.incrementAndGet()
            store.forgetSentPushToken()
        }
    }

    /** Signed out: no token, so nothing reaches this phone until the next sign-in. */
    suspend fun unregister() {
        forgetSentToken()
        if (!enabled || !started) return
        client.stop()
    }

    /** Timing of [PushSetup]. */
    companion object {
        /** How long a token the server accepted is not sent again. */
        val RESEND_AFTER: Duration = Duration.ofDays(7)
    }
}
