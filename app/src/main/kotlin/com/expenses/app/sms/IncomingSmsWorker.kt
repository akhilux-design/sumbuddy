package com.expenses.app.sms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.ingest.runNewPaymentsHooks
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
 * inbox, which also gives the provider id that makes reading exactly-once. Because the default SMS app may write the
 * message to the inbox a moment after the broadcast, the worker tries again a few times when it is not there yet.
 *
 * It reads from the last scan (less a small overlap), so messages missed while the app could not run are caught up;
 * messages read before are skipped by the pipeline. With no last scan (the onboarding scan did not finish) it reads
 * from the tracking start. Nothing is read before onboarding is done, in manual mode, or before the tracking start
 * date. [catchUp] runs the same read with no new SMS, when the app starts and when tracking begins.
 */
class IncomingSmsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.appContainer
        val settings = app.settings.settings.first()
        val trackingStart = settings.trackingStartAt
        if (!settings.onboardingDone || settings.trackingMode != TrackingMode.SMS || trackingStart == null) {
            return Result.success()
        }
        val inbox = app.smsInbox
        if (!inbox.canRead()) return Result.success()

        val now = app.clock.instant()
        val catchingUp = KEY_ARRIVED_AT !in inputData.keyValueMap
        val arrivedAt = Instant.ofEpochMilli(inputData.getLong(KEY_ARRIVED_AT, now.toEpochMilli()))
        val lastScan = settings.lastScanAt ?: trackingStart
        val from = maxOf(trackingStart, minOf(lastScan, arrivedAt).minus(OVERLAP))
        val summary = SmsScanner(inbox, app.ingestPipeline).scan(from, until = null)
        app.settings.setLastScan(now)

        // Splitting looks first (a payment that settles a balance, then an automatic split rule): what it handled gets
        // no "Payment detected" notification, and a payment one hook handled is not given to the next. A failing hook
        // never stops the others or the notifications.
        val handled = runNewPaymentsHooks(app.newPaymentsHooks, summary.result)

        val notifyFrom = now.minus(NOTIFY_WINDOW)
        for (expense in summary.result.detected) {
            if (expense.id in handled) continue
            if ((expense.smsReceivedAt ?: expense.occurredAt) < notifyFrom) continue
            val account = expense.accountId?.let { app.accounts.get(it) }
            PaymentNotifications.show(applicationContext, expense, account)
        }

        val arrived = catchingUp || summary.newest?.let { it >= arrivedAt.minus(ARRIVAL_MARGIN) } ?: false
        return if (!arrived && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = readingForegroundInfo(applicationContext, FOREGROUND_ID)

    /** Starting the worker. */
    companion object {
        private const val KEY_ARRIVED_AT = "arrived_at"
        private const val MAX_ATTEMPTS = 5
        private const val FOREGROUND_ID = 7101
        private const val CATCH_UP = "sms_catch_up"
        private val OVERLAP: Duration = Duration.ofMinutes(30)
        private val ARRIVAL_MARGIN: Duration = Duration.ofMinutes(2)
        private val NOTIFY_WINDOW: Duration = Duration.ofDays(1)
        private val RETRY_DELAY: Duration = Duration.ofSeconds(10)

        /** Queues a read for an SMS that arrived at [arrivedAt], expedited when the quota allows. */
        fun enqueue(context: Context, arrivedAt: Instant) {
            val request = OneTimeWorkRequestBuilder<IncomingSmsWorker>()
                .setInputData(workDataOf(KEY_ARRIVED_AT to arrivedAt.toEpochMilli()))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

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
