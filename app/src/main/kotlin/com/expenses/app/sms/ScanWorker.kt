package com.expenses.app.sms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Reads a period of the inbox in pages: the onboarding scan (from the tracking start date up to now) and "add older
 * payments" when the person moves the tracking start date earlier (only the extra period, from the new start up to
 * the old one).
 *
 * The scan posts no notifications: what it finds waits on Home as detected payments. While it runs it reports
 * [PROGRESS_READ] and [PROGRESS_TOTAL]; when it is done its output holds the counts under the `OUT_` keys. A scan that
 * stops with an error ends FAILED and changes no setting, so nothing claims a period was read when it was not.
 *
 * On success, a scan up to now stores the time it started as the last scan, so the incoming SMS reader carries on
 * from there, and an "add older payments" scan moves the tracking start back to the day it read from.
 *
 * The onboarding scan first drops everything read from SMS before its start: when the person went back and picked a
 * later day, what an earlier scan stored from before that day must not stay.
 */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.appContainer
        val startedAt = app.clock.instant()
        val from = Instant.ofEpochMilli(inputData.getLong(KEY_FROM, startedAt.toEpochMilli()))
        val until = inputData.getLong(KEY_UNTIL, NO_TIME).takeIf { it != NO_TIME }?.let(Instant::ofEpochMilli)

        val onboarding = inputData.getBoolean(KEY_ONBOARDING, false)

        if (onboarding) app.forgetSmsBefore(from)
        val inbox = app.smsInbox
        val total = inbox.count(from, until)
        setProgress(workDataOf(PROGRESS_READ to 0, PROGRESS_TOTAL to total))
        val summary = SmsScanner(inbox, app.ingestPipeline).scan(from, until) { read ->
            setProgress(workDataOf(PROGRESS_READ to read, PROGRESS_TOTAL to maxOf(total, read)))
        }
        // A scan replaced by this one may have stored a last page while it stopped; drop that too.
        if (onboarding) app.forgetSmsBefore(from)
        if (until == null && inbox.canRead()) app.settings.setLastScan(startedAt)
        if (until != null) {
            val currentStart = app.settings.settings.first().trackingStartAt
            if (currentStart == null || from < currentStart) app.settings.setTrackingStart(from)
        }

        val result = summary.result
        return Result.success(
            workDataOf(
                OUT_READ to summary.read,
                OUT_DETECTED to result.detected.size,
                OUT_CREDITS to result.credits.size,
                OUT_NEW_ACCOUNTS to result.newAccounts.size,
                OUT_CARD_BILLS to result.cardBills.size,
                OUT_REVIEW_ITEMS to result.reviewItems.size,
                OUT_DUPLICATES to result.duplicates,
                OUT_SKIPPED to result.skipped,
            ),
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = readingForegroundInfo(applicationContext, FOREGROUND_ID)

    /** Starting and following scans, and the keys of their progress and output. */
    companion object {
        /** Progress: messages read so far (Int). */
        const val PROGRESS_READ: String = "read"

        /** Progress: messages in the period (Int). */
        const val PROGRESS_TOTAL: String = "total"

        /** Output: messages in the period, including ones read before (Int). */
        const val OUT_READ: String = "out_read"

        /** Output: new detected debits of tracked accounts (Int). */
        const val OUT_DETECTED: String = "out_detected"

        /** Output: new credits (Int). */
        const val OUT_CREDITS: String = "out_credits"

        /** Output: accounts and cards seen for the first time (Int). */
        const val OUT_NEW_ACCOUNTS: String = "out_new_accounts"

        /** Output: card bills stored or updated (Int). */
        const val OUT_CARD_BILLS: String = "out_card_bills"

        /** Output: new Needs review items (Int). */
        const val OUT_REVIEW_ITEMS: String = "out_review_items"

        /** Output: second reports of a payment, not stored (Int). */
        const val OUT_DUPLICATES: String = "out_duplicates"

        /** Output: messages that were not payments, not stored (Int). */
        const val OUT_SKIPPED: String = "out_skipped"

        private const val KEY_FROM = "from"
        private const val KEY_UNTIL = "until"
        private const val KEY_ONBOARDING = "onboarding"
        private const val NO_TIME = -1L
        private const val FOREGROUND_ID = 7102
        private const val ONBOARDING_SCAN = "sms_scan_onboarding"
        private const val OLDER_SCAN_PREFIX = "sms_scan_older_"

        /**
         * Starts the onboarding scan from [from] (the tracking start) up to now. Starting it again replaces a scan
         * that is still running (for example after the person went back and picked another start). What was read
         * before [from] is dropped; what was read since stays and is not read twice. Call it once per choice, from a
         * ViewModel.
         */
        fun startOnboardingScan(context: Context, from: Instant) {
            enqueue(context, ONBOARDING_SCAN, from, until = null, onboarding = true, policy = ExistingWorkPolicy.REPLACE)
        }

        /** The onboarding scan's state, or null before it was started. */
        fun observeOnboardingScan(context: Context): Flow<WorkInfo?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ONBOARDING_SCAN).map { it.firstOrNull() }

        /**
         * Starts "add older payments" to move the tracking start from [oldStart] back to [newStart]: only messages
         * received at or after [newStart] and before [oldStart] are read. The tracking start is moved only once the
         * scan succeeds. Returns the name to follow it with [observeOlderScan].
         */
        fun startOlderScan(context: Context, newStart: Instant, oldStart: Instant): String {
            require(newStart < oldStart) { "newStart must be before oldStart" }
            val name = OLDER_SCAN_PREFIX + newStart.toEpochMilli() + "_" + oldStart.toEpochMilli()
            enqueue(context, name, newStart, oldStart, onboarding = false, policy = ExistingWorkPolicy.KEEP)
            return name
        }

        /** The state of the "add older payments" scan [name] from [startOlderScan], or null when unknown. */
        fun observeOlderScan(context: Context, name: String): Flow<WorkInfo?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name).map { it.firstOrNull() }

        private fun enqueue(
            context: Context,
            name: String,
            from: Instant,
            until: Instant?,
            onboarding: Boolean,
            policy: ExistingWorkPolicy,
        ) {
            val input = Data.Builder()
                .putLong(KEY_FROM, from.toEpochMilli())
                .putLong(KEY_UNTIL, until?.toEpochMilli() ?: NO_TIME)
                .putBoolean(KEY_ONBOARDING, onboarding)
                .build()
            val request = OneTimeWorkRequestBuilder<ScanWorker>()
                .setInputData(input)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
        }
    }
}
