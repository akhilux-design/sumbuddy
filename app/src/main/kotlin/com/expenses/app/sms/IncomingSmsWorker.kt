package com.expenses.app.sms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.expenses.app.data.model.Account
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.di.AppContainer
import com.expenses.app.ingest.IngestPipeline
import com.expenses.app.ingest.NewPaymentsHook
import com.expenses.app.ingest.runNewPaymentsHooks
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Reads new SMS after [SmsReceivedReceiver] saw one arrive, stores what they give and posts a "Payment detected"
 * notification for each new detected debit.
 *
 * The worker gets only the time the SMS arrived, never its text: WorkManager keeps its input on disk, and SMS text
 * may be stored only as the architecture allows. The message itself, with all its parts joined, is read from the SMS
 * inbox, which also gives the provider id that makes reading exactly-once.
 *
 * The default SMS app may write the message to the inbox a moment after the broadcast, so the worker may run before
 * it is there. It takes the SMS as read only when this run read a message for the first time (it marked the message
 * read itself: [com.expenses.app.ingest.IngestResult.newestFirstRead]) that arrived at most two minutes before the
 * broadcast. A message read before does not count, however close: a bank SMS and a UPI SMS for one payment often
 * arrive seconds apart, and the first one read must not end the wait for the second. Otherwise it tries again
 * [MAX_ATTEMPTS] times, 10, 20, 30, 40 and 50 seconds apart (about two and a half minutes in all, longer when the
 * system holds the work back), and then stops. When another read took the SMS first, these tries find nothing new
 * and post nothing.
 *
 * It reads from the last scan (less a small overlap), so messages missed while the app could not run are caught up;
 * messages read before are skipped by the pipeline. With no last scan (the onboarding scan did not finish) it reads
 * from the tracking start. Nothing is read before onboarding is done, in manual mode, or before the tracking start
 * date. [catchUp] runs the same read with no new SMS, when the app starts and when tracking begins.
 */
class IncomingSmsWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val given: IncomingSmsParts?,
) : CoroutineWorker(context, params) {
    /** The constructor WorkManager calls. */
    constructor(context: Context, params: WorkerParameters) : this(context, params, null)

    override suspend fun doWork(): Result {
        val app = given ?: ContainerParts(applicationContext.appContainer)
        val settings = app.settings.settings.first()
        val trackingStart = settings.trackingStartAt
        if (!settings.onboardingDone || settings.trackingMode != TrackingMode.SMS || trackingStart == null) {
            return Result.success()
        }
        val inbox = app.inbox
        if (!inbox.canRead()) return Result.success()

        val now = app.clock.instant()
        val catchingUp = KEY_ARRIVED_AT !in inputData.keyValueMap
        val arrivedAt = Instant.ofEpochMilli(inputData.getLong(KEY_ARRIVED_AT, now.toEpochMilli()))
        val lastScan = settings.lastScanAt ?: trackingStart
        val from = maxOf(trackingStart, minOf(lastScan, arrivedAt).minus(OVERLAP))
        val summary = SmsScanner(inbox, app.pipeline).scan(from, until = null)
        app.settings.setLastScan(now)

        // Splitting looks first (a payment that settles a balance, then an automatic split rule): what it handled gets
        // no "Payment detected" notification, and a payment one hook handled is not given to the next. A failing hook
        // never stops the others or the notifications.
        val handled = runNewPaymentsHooks(app.hooks, summary.result)

        // A new card bill, or a payment on a card that may pay one: the bill reminders are planned again.
        if (summary.result.cardBills.isNotEmpty() || summary.result.credits.isNotEmpty()) app.planCardBillReminders()

        val notifyFrom = now.minus(NOTIFY_WINDOW)
        for (expense in summary.result.detected) {
            if (expense.id in handled) continue
            if ((expense.smsReceivedAt ?: expense.occurredAt) < notifyFrom) continue
            val account = expense.accountId?.let { app.account(it) }
            PaymentNotifications.show(applicationContext, expense, account)
        }

        // The SMS is in only when this run read a message for the first time that arrived about when it did. A message
        // read before (by an earlier run, or by another reader meanwhile) says nothing about this SMS, however close.
        val firstRead = summary.result.newestFirstRead
        val arrived = catchingUp || (firstRead != null && firstRead >= arrivedAt.minus(ARRIVAL_MARGIN))
        return if (!arrived && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = readingForegroundInfo(applicationContext, FOREGROUND_ID)

    /** The parts of the app's [AppContainer] the worker uses, each made only when first used. */
    private class ContainerParts(private val app: AppContainer) : IncomingSmsParts {
        override val settings: SettingsRepository get() = app.settings
        override val inbox: PagedSmsInbox get() = app.smsInbox
        override val pipeline: IngestPipeline get() = app.ingestPipeline
        override val clock: Clock get() = app.clock
        override val hooks: List<NewPaymentsHook> get() = app.newPaymentsHooks

        override suspend fun account(id: Long): Account? = app.accounts.get(id)

        override suspend fun planCardBillReminders() = app.cardBillReminders.syncQuietly()
    }

    /** Starting the worker. */
    companion object {
        /** How many times a read tries again while its SMS is not in the inbox; the run after the last one stops. */
        internal const val MAX_ATTEMPTS = 5

        private const val KEY_ARRIVED_AT = "arrived_at"
        private const val FOREGROUND_ID = 7101
        private const val CATCH_UP = "sms_catch_up"
        private val OVERLAP: Duration = Duration.ofMinutes(30)
        private val ARRIVAL_MARGIN: Duration = Duration.ofMinutes(2)
        private val NOTIFY_WINDOW: Duration = Duration.ofDays(1)
        private val RETRY_DELAY: Duration = Duration.ofSeconds(10)

        /** Queues a read for an SMS that arrived at [arrivedAt], expedited when the quota allows. */
        fun enqueue(context: Context, arrivedAt: Instant) {
            val request = OneTimeWorkRequestBuilder<IncomingSmsWorker>()
                .setInputData(arrivalInput(arrivedAt))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        /** The worker's input for an SMS that arrived at [arrivedAt]: that time and nothing else. */
        internal fun arrivalInput(arrivedAt: Instant): Data = workDataOf(KEY_ARRIVED_AT to arrivedAt.toEpochMilli())

        /**
         * Queues a read from the last scan up to now without a new SMS, so messages that arrived while nothing read
         * them (the person was still on Confirm accounts, or the app was stopped) are not left until the next SMS.
         * A catch-up already queued is kept. It does nothing before onboarding is done or in manual mode.
         */
        fun catchUp(context: Context) {
            val request = OneTimeWorkRequestBuilder<IncomingSmsWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** What [IncomingSmsWorker] reads with and stores to: the app's [AppContainer] in the app, stand-ins in tests. */
internal interface IncomingSmsParts {
    /** The person's settings: tracking mode and start, onboarding, last scan. */
    val settings: SettingsRepository

    /** The phone's SMS inbox. */
    val inbox: PagedSmsInbox

    /** Stores what the messages give, each message exactly once. */
    val pipeline: IngestPipeline

    /** "Now". */
    val clock: Clock

    /** What runs on new payments before their notifications ([runNewPaymentsHooks]). */
    val hooks: List<NewPaymentsHook>

    /** The account [id], for a notification's text. */
    suspend fun account(id: Long): Account?

    /** Plans the card bill reminders again; never fails. */
    suspend fun planCardBillReminders()
}
