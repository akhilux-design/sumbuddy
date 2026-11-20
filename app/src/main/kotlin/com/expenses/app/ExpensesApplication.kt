package com.expenses.app

import android.app.Application
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.di.AppContainer
import com.expenses.app.sms.IncomingSmsWorker
import com.expenses.app.sms.PaymentNotifications
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The app's [Application]. It makes the [AppContainer] that every screen and worker gets its dependencies from,
 * starts push and, while someone is signed in, the 12-hour group sync and the push token's registration,
 * removes a payment's notification once the payment stops waiting, wherever it was added or ignored, and catches up
 * on SMS that arrived while nothing read them: when the app starts with SMS tracking on, and each time tracking
 * begins (onboarding done, or tracking turned on again).
 */
class ExpensesApplication : Application() {
    /** The app's dependencies; made in [onCreate], before any activity, receiver or worker runs. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Push only when this build has a Firebase project (docs/server.md 9.6); otherwise nothing of Firebase runs.
        container.push.init(this)
        container.appScope.launch {
            if (container.auth.isSignedIn()) {
                container.sync.schedulePeriodic()
                // On each start, as Firebase advises: push may be new in this build, the last PUT may have failed, or
                // the server may have dropped this phone's row. Nothing is sent while this token was accepted recently.
                container.push.register()
            }
        }
        container.appScope.launch {
            // After an update whose parser skips more messages (store loyalty points), drop the stored rows it now
            // skips that the person never acted on. Off the main thread; a failure leaves it for the next start.
            try {
                container.parserRulesCleanup.runIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing is logged: the rows hold SMS text.
            }
        }
        container.appScope.launch {
            container.expenses.observeDetected()
                .map { waiting -> waiting.mapTo(HashSet()) { it.expense.id } }
                .distinctUntilChanged()
                .collect { PaymentNotifications.cancelHandled(this@ExpensesApplication, it) }
        }
        container.appScope.launch {
            container.settings.settings
                .map { it.onboardingDone && it.trackingMode == TrackingMode.SMS }
                .distinctUntilChanged()
                .filter { it }
                .collect { IncomingSmsWorker.catchUp(this@ExpensesApplication) }
        }
    }
}
