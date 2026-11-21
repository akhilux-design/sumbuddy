package com.expenses.app

import android.app.Application
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.di.AppContainer
import com.expenses.app.sms.IncomingSmsWorker
import com.expenses.app.sms.PaymentNotifications
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The app's [Application]. It makes the [AppContainer] that every screen and worker gets its dependencies from,
 * starts push and, while someone is signed in, the 12-hour group sync and the push token's registration,
 * removes a payment's notification once the payment stops waiting, wherever it was added or ignored, catches up
 * on SMS that arrived while nothing read them: when the app starts with SMS tracking on, and each time tracking
 * begins (onboarding done, or tracking turned on again), and plans the card bill reminders (docs/card-bills.md 2.7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
            // Card bill reminders are planned at every start (an update, a missed run) and again whenever what they
            // read changes: the reminders switch, the tracking mode, a card switched on or off, a bill stored,
            // replaced, reminded or deleted. Paid bills come from SMS reads, which plan them too. Nothing before
            // onboarding is done.
            container.settings.settings
                .map { it.onboardingDone }
                .distinctUntilChanged()
                .flatMapLatest { done ->
                    if (!done) {
                        emptyFlow()
                    } else {
                        combine(
                            container.settings.settings.map { it.remindersOn to it.trackingMode }.distinctUntilChanged(),
                            container.accounts.observeAll().map { all -> all.filter { it.tracked }.map { it.id }.toSet() }.distinctUntilChanged(),
                            container.cardBills.observeAll().distinctUntilChanged(),
                        ) { _, _, _ -> }
                    }
                }
                .collectLatest { container.cardBillReminders.syncQuietly() }
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
