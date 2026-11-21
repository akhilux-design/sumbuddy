package com.expenses.app.sms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.expenses.app.data.model.Account
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.di.AppContainer
import com.expenses.app.ingest.IngestPipeline
import com.expenses.app.ingest.NewPaymentsHook
import com.expenses.app.ingest.SmsTimes
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
 * The worker gets only times, never the text: when the SMS arrived, and when the network's service centre stamped its
 * parts ([SmsReceivedReceiver]). WorkManager keeps its input on disk, and SMS text may be stored only as the
 * architecture allows. The message itself, with all its parts joined, is read from the SMS inbox, which also gives the
 * provider id that makes reading exactly-once.
 *
 * The default SMS app may write the message to the inbox a moment after the broadcast, so the worker may run before
 * it is there. It takes the SMS as read only when this run read for the first time (it marked the message read itself:
 * [com.expenses.app.ingest.IngestResult.firstRead]) a message that may be this SMS ([isTheSms]): one that arrived at
 * most two minutes before the broadcast, or one whose service-centre time (`date_sent`) is the broadcast's, however
 * early the SMS app dated it. A message read before does not count, however close: a bank SMS and a UPI SMS for one
 * payment often arrive seconds apart, and the first one read must not end the wait for the second. Otherwise it tries
 * again [MAX_ATTEMPTS] times, 10, 20, 30, 40 and 50 seconds apart, and then stops: about two and a half minutes in
 * all, longer when the system holds the work back. When another read took the SMS first, these tries find nothing new
 * and post no "Payment detected".
 *
 * Only the first run is expedited. A try is not a WorkManager retry: the run ends with success and queues the next try
 * as new, not expedited work ([nextTry]) with the same times, the try's number and a delay. On Android 11 and older
 * WorkManager runs every run of expedited work, retries included, as a foreground service with the quiet "Reading
 * SMS" notification; so only the first run shows it there, not each try.
 *
 * Another SMS read for the first time within the two minutes ends the wait too, on purpose. When two SMS arrive a
 * second or so apart, each read often finds only the other one (the SMS app writes each a moment after its read
 * starts), and the read that is still trying reads the later one. Asking for a matching `date_sent` there made both
 * reads use every try: 12 runs for a pair instead of 3.
 *
 * Still open, rare: an unread message that had no read of its own (it came during an app update, or while onboarding
 * was not done) and arrived within the two minutes ends the wait as well, and the new SMS waits for the next SMS or
 * app start.
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
        val app = given ?: ContainerParts(applicationContext.appContainer, applicationContext)
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
        val sentAt = inputData.getLongArray(KEY_SENT_AT)?.map(Instant::ofEpochMilli)?.toSet().orEmpty()
        val lastScan = settings.lastScanAt ?: trackingStart
        val from = maxOf(trackingStart, minOf(lastScan, arrivedAt).minus(OVERLAP))
        // The scan keeps only the messages read for the first time that may be this SMS, not every message it read.
        val summary = SmsScanner(inbox, app.pipeline)
            .scan(from, until = null, keepFirstRead = { isTheSms(it, arrivedAt, sentAt) })
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

        // The SMS is in only when this run read it for the first time. A message read before (by an earlier run, or by
        // another reader meanwhile) says nothing about this SMS, however close.
        val arrived = catchingUp || summary.result.firstRead.isNotEmpty()
        if (!arrived) nextTry(inputData, runAttemptCount)?.let { app.queue(it) }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = readingForegroundInfo(applicationContext, FOREGROUND_ID)

    /** The parts of the app's [AppContainer] the worker uses, each made only when first used. */
    private class ContainerParts(private val app: AppContainer, private val context: Context) : IncomingSmsParts {
        override val settings: SettingsRepository get() = app.settings
        override val inbox: PagedSmsInbox get() = app.smsInbox
        override val pipeline: IngestPipeline get() = app.ingestPipeline
        override val clock: Clock get() = app.clock
        override val hooks: List<NewPaymentsHook> get() = app.newPaymentsHooks

        override suspend fun account(id: Long): Account? = app.accounts.get(id)

        override suspend fun planCardBillReminders() = app.cardBillReminders.syncQuietly()

        override fun queue(request: OneTimeWorkRequest) {
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    /** Starting the worker. */
    companion object {
        /** How many times a read tries again while its SMS is not in the inbox; the run after the last one stops. */
        internal const val MAX_ATTEMPTS = 5

        private const val KEY_ARRIVED_AT = "arrived_at"
        private const val KEY_SENT_AT = "sent_at"
        private const val KEY_TRY = "try"
        private const val FOREGROUND_ID = 7101
        private const val CATCH_UP = "sms_catch_up"
        private val OVERLAP: Duration = Duration.ofMinutes(30)
        private val ARRIVAL_MARGIN: Duration = Duration.ofMinutes(2)
        private val NOTIFY_WINDOW: Duration = Duration.ofDays(1)
        private val RETRY_DELAY: Duration = Duration.ofSeconds(10)

        /**
         * Queues a read for an SMS that arrived at [arrivedAt], expedited when the quota allows. [sentAt] holds the
         * service-centre times of its parts; empty when the broadcast gave none. Only this first run is expedited:
         * its tries are queued by [nextTry].
         */
        fun enqueue(context: Context, arrivedAt: Instant, sentAt: List<Instant> = emptyList()) {
            WorkManager.getInstance(context).enqueue(firstRun(arrivedAt, sentAt))
        }

        /** The first run of the read for an SMS that arrived at [arrivedAt] ([enqueue]): the only expedited one. */
        internal fun firstRun(arrivedAt: Instant, sentAt: List<Instant> = emptyList()): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<IncomingSmsWorker>()
                .setInputData(arrivalInput(arrivedAt, sentAt))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS)
                .build()

        /**
         * The worker's input for an SMS that arrived at [arrivedAt] with the service-centre times [sentAt], read for
         * the [tryNumber]th time (0 for the first run): those times and that count, nothing else. With no
         * service-centre time there is no [KEY_SENT_AT]; on the first run there is no [KEY_TRY].
         */
        internal fun arrivalInput(arrivedAt: Instant, sentAt: List<Instant> = emptyList(), tryNumber: Int = 0): Data {
            val input = Data.Builder().putLong(KEY_ARRIVED_AT, arrivedAt.toEpochMilli())
            if (sentAt.isNotEmpty()) input.putLongArray(KEY_SENT_AT, sentAt.map(Instant::toEpochMilli).toLongArray())
            if (tryNumber > 0) input.putInt(KEY_TRY, tryNumber)
            return input.build()
        }

        /**
         * The next try of the read whose input is [input], or null after the last try ([MAX_ATTEMPTS]): not expedited
         * (on Android 11 and older expedited work runs as a foreground service, which would show "Reading SMS" on every
         * try), with the same times and the try's number, and delayed so the tries keep their schedule, 10, 20, 30, 40
         * and 50 seconds after the run before.
         *
         * [runAttemptCount] counts only where [input] has no try number: a WorkManager retry queued by 0.3.1 (which
         * tried again with `Result.retry()`) still runs once after the update, and it has made that many tries already.
         */
        internal fun nextTry(input: Data, runAttemptCount: Int = 0): OneTimeWorkRequest? {
            val done = if (KEY_TRY in input.keyValueMap) input.getInt(KEY_TRY, 0) else runAttemptCount
            val next = done + 1
            if (next > MAX_ATTEMPTS) return null
            val arrivedAt = Instant.ofEpochMilli(input.getLong(KEY_ARRIVED_AT, 0))
            val sentAt = input.getLongArray(KEY_SENT_AT)?.map(Instant::ofEpochMilli).orEmpty()
            return OneTimeWorkRequestBuilder<IncomingSmsWorker>()
                .setInputData(arrivalInput(arrivedAt, sentAt, next))
                .setInitialDelay(RETRY_DELAY.multipliedBy(next.toLong()))
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS)
                .build()
        }

        /**
         * Whether [read], a message a run read for the first time, may be the SMS that arrived at [arrivedAt] with
         * the service-centre times [sentAt]: it arrived at most [ARRIVAL_MARGIN] before the broadcast, or its
         * service-centre time is one of the broadcast's. A `date_sent` that does not match never rules a message
         * out: an SMS app that records another time there, or two SMS a second apart whose reads each find the
         * other one, must not cost tries.
         */
        internal fun isTheSms(read: SmsTimes, arrivedAt: Instant, sentAt: Set<Instant>): Boolean =
            read.sentAt?.let { it in sentAt } == true || read.receivedAt >= arrivedAt.minus(ARRIVAL_MARGIN)

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

    /** Queues [request], the next try of a read ([IncomingSmsWorker.nextTry]). */
    fun queue(request: OneTimeWorkRequest)
}
