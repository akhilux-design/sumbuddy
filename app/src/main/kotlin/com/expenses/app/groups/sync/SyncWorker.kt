package com.expenses.app.groups.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.expenses.app.R
import com.expenses.app.sms.appContainer
import com.expenses.app.ui.theme.appLook
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Asks for group syncs. [WorkSyncScheduler] is the real one; tests record the calls. */
interface SyncScheduler {
    /**
     * Sync soon: send the outbox and pull what changed. Called on every outbox write, on app start and return to
     * the foreground, on a push and on a live room message.
     *
     * @param expedited true to run as soon as possible (a write the person just made, a push).
     */
    fun requestSync(expedited: Boolean = true)

    /** Sync, then post the reminder of a `remind` push from [memberId] in [groupId] (docs/server.md 4.11). */
    fun requestReminder(groupId: String, memberId: String)

    /**
     * A `changed` push said [groupId] is at [version]: pull that group only, and only when this phone is behind
     * (docs/server.md 4.14). A group the phone does not know gets a full sync.
     */
    fun requestGroupPull(groupId: String, version: Long)

    /** Sync every 12 hours while signed in (network required). */
    fun schedulePeriodic()

    /** Stops every group sync (sign out). */
    fun cancelAll()

    /**
     * True while a full sync runs (the outbox and `/me/groups`), so the Groups tab can show its grey placeholders
     * (02-everyday/22) on a new phone or after sign-in instead of "No groups yet" before the list arrives. A sync
     * waiting for the network does not count.
     */
    fun observeSyncing(): Flow<Boolean> = flowOf(false)
}

/**
 * Runs [SyncWorker] through WorkManager: one unique sync at a time (a request while one runs makes it run again
 * when done), network required, back-off from 30 s, and the 12-hour periodic sync.
 *
 * A sync waiting out its back-off (after a failure) is replaced by a new request, so it runs now: otherwise every
 * change made meanwhile, in any group, would wait for the back-off to end (up to WorkManager's 5 hours).
 */
class WorkSyncScheduler(private val context: Context) : SyncScheduler {
    private val dirty = AtomicBoolean(false)

    /** Where the state of the queued sync is checked, off the caller's thread, one request at a time. */
    private val checks: Executor = Executors.newSingleThreadExecutor()

    override fun requestSync(expedited: Boolean) {
        dirty.set(true)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkRequired)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        val work = WorkManager.getInstance(context)
        val queued = work.getWorkInfosForUniqueWork(SYNC_WORK)
        queued.addListener(
            {
                val backingOff = runCatching { queued.get() }.getOrNull().orEmpty()
                    .any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
                // KEEP while a sync runs (the dirty flag makes it run again) or waits for its first run; REPLACE one
                // that is only waiting out a back-off.
                work.enqueueUniqueWork(SYNC_WORK, if (backingOff) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
            },
            checks,
        )
    }

    override fun requestGroupPull(groupId: String, version: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkRequired)
            .setInputData(workDataOf(KEY_PULL_GROUP to groupId, KEY_PULL_VERSION to version))
            .addTag(PULL_TAG)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // One chain per group: a push that comes while a pull runs is checked again after it (usually nothing to do).
        WorkManager.getInstance(context).enqueueUniqueWork(PULL_WORK_PREFIX + groupId, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun requestReminder(groupId: String, memberId: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkRequired)
            .setInputData(workDataOf(KEY_REMIND_GROUP to groupId, KEY_REMIND_MEMBER to memberId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(REMIND_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(networkRequired)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun observeSyncing(): Flow<Boolean> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SYNC_WORK)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
            .distinctUntilChanged()

    override fun cancelAll() {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(SYNC_WORK)
        work.cancelUniqueWork(REMIND_WORK)
        work.cancelUniqueWork(PERIODIC_WORK)
        work.cancelAllWorkByTag(PULL_TAG)
    }

    /** True once, after a request that came in while a sync ran; [SyncWorker] then runs again. */
    fun takeRequest(): Boolean = dirty.getAndSet(false)

    private val networkRequired = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Work names and input keys. */
    companion object {
        private const val SYNC_WORK = "group_sync"
        private const val REMIND_WORK = "group_remind"
        private const val PERIODIC_WORK = "group_sync_periodic"
        private const val PULL_WORK_PREFIX = "group_pull_"
        private const val PULL_TAG = "group_pull"
        private const val BACKOFF_SECONDS = 30L
        private const val PERIOD_HOURS = 12L

        /** Input: the group of a reminder push. */
        const val KEY_REMIND_GROUP: String = "remind_group"

        /** Input: the member who sent a reminder. */
        const val KEY_REMIND_MEMBER: String = "remind_member"

        /** Input: the group of a `changed` push. */
        const val KEY_PULL_GROUP: String = "pull_group"

        /** Input: the version a `changed` push gave. */
        const val KEY_PULL_VERSION: String = "pull_version"
    }
}

/**
 * One group sync (docs/server.md 5): the outbox, then the pull, again while new requests came in meanwhile. Does
 * nothing while nobody is signed in. A reminder push's sync also posts the reminder once the data is fresh. A
 * `changed` push's work pulls only its group, and only when the phone is behind.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.appContainer
        if (!app.auth.isSignedIn()) return Result.success()
        val pullGroup = inputData.getString(WorkSyncScheduler.KEY_PULL_GROUP)
        if (pullGroup != null) {
            val version = inputData.getLong(WorkSyncScheduler.KEY_PULL_VERSION, Long.MAX_VALUE)
            // A failed pull hands over to the one main sync, which retries with back-off (and a later request
            // replaces that back-off), rather than leaving a back-off in this group's chain.
            if (app.syncEngine.pullChanged(pullGroup, version) == SyncOutcome.RETRY) app.sync.requestSync()
            return Result.success()
        }
        val scheduler = app.sync as? WorkSyncScheduler
        var outcome: SyncOutcome
        var runs = 0
        do {
            scheduler?.takeRequest()
            outcome = app.syncEngine.sync()
            runs++
        } while (outcome == SyncOutcome.DONE && scheduler?.takeRequest() == true && runs < MAX_RUNS)
        val group = inputData.getString(WorkSyncScheduler.KEY_REMIND_GROUP)
        val member = inputData.getString(WorkSyncScheduler.KEY_REMIND_MEMBER)
        if (group != null && member != null) {
            app.syncEngine.reminderEvent(group, member)?.let { app.groupNotifier.post(listOf(it)) }
        }
        return when (outcome) {
            SyncOutcome.DONE, SyncOutcome.SIGNED_OUT -> Result.success()
            SyncOutcome.RETRY -> Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.sync_channel),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = applicationContext.getString(R.string.sync_channel_description) },
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .appLook(applicationContext)
            .setContentTitle(applicationContext.getString(R.string.sync_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(FOREGROUND_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "group_sync"
        const val FOREGROUND_ID = 7_302
        const val MAX_RUNS = 5
    }
}
