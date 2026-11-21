package com.expenses.app.cardbill

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.expenses.app.data.repository.CardBillRepository
import com.expenses.app.data.settings.AppSettings
import com.expenses.app.data.settings.TrackingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The automatic card bill reminder (docs/card-bills.md 2.7, D1 to D4): one notification to the person, never to
 * friends, at 09:00 India time three days before a bill is due, with no reminder in quiet hours (21:00 to 09:00).
 *
 * [sync] plans it: one WorkManager work per bill that is due a reminder, cancelled for every other bill. WorkManager
 * keeps the work across reboots by itself. [remind] is what the work runs: it checks everything again, since the data
 * may have changed since it was planned.
 *
 * @param context any context; only its application context is kept.
 * @param bills the stored bills (every bill, and "reminded").
 * @param reader each bill with its card, payments and what friends owe.
 * @param settings the person's settings (reminders switch, tracking mode).
 * @param clock now, in India time.
 * @param workManager where the reminders wait.
 */
class CardBillReminders(
    context: Context,
    private val bills: CardBillRepository,
    private val reader: CardBillReader,
    private val settings: Flow<AppSettings>,
    private val clock: Clock,
    private val workManager: () -> WorkManager = { WorkManager.getInstance(context.applicationContext) },
) {
    private val context: Context = context.applicationContext

    /** What [remind] did. */
    enum class Outcome {
        /** The notification was posted and the bill marked reminded. */
        Posted,

        /** The bill is not due a reminder (any more): nothing was posted. */
        NotDue,

        /** It ran early or into quiet hours: it waits again for its time. */
        Later,

        /** Notifications are off: nothing was posted, and the bill is not marked reminded. */
        NotificationsOff,
    }

    /**
     * Plans the reminders of every stored bill: the work of each bill that is due one waits for its time
     * ([CardBillMath.reminderAt]); the work of every other bill is cancelled, and so is the posted notification of a
     * bill now paid, replaced, deleted, naming no card or of a switched-off card. Switching reminders off or going to
     * manual mode leaves a posted notification alone. A second run with nothing changed changes nothing.
     */
    suspend fun sync() {
        val now = clock.instant()
        val today = now.atZone(clock.zone).toLocalDate()
        val appSettings = settings.first()
        val sms = appSettings.trackingMode == TrackingMode.SMS
        val planned = HashMap<Long, Instant>()
        val keepNotification = HashSet<Long>()
        for (bill in bills.all()) {
            val detail = reader.detail(bill.id) ?: continue
            if (!detail.current || detail.state(today) is BillState.Paid) continue
            keepNotification += bill.id
            val live = CardBillMath.isLive(detail.facts, listOf(detail.bill), sms, clock.zone)
            CardBillMath.reminderAt(detail.bill, live, detail.paidOn, appSettings.remindersOn, now, clock.zone)
                ?.let { planned[bill.id] = it }
        }
        val manager = workManager()
        val waiting = manager.getWorkInfosByTagFlow(TAG).first().filter { !it.state.isFinished }
        // A reminder that is running finishes on its own checks (it posts, or waits again for its time). It is neither
        // cancelled nor replaced: REPLACE would cancel it, and a cancel between its post and its "reminded" mark would
        // let the new work post it a second time.
        val running = waiting.filter { it.state == WorkInfo.State.RUNNING }.mapNotNullTo(HashSet()) { billIdOf(it) }
        for (info in waiting) {
            val billId = billIdOf(info) ?: continue
            if (billId !in planned && billId !in running) manager.cancelUniqueWork(uniqueName(billId))
        }
        for ((billId, at) in planned) {
            if (billId in running) continue
            val same = waiting.any { billIdOf(it) == billId && it.state == WorkInfo.State.ENQUEUED && fireAtOf(it) == at.toEpochMilli() }
            if (!same) enqueue(manager, billId, at, now)
        }
        CardBillNotifications.cancelAllBut(context, keepNotification)
    }

    /**
     * [sync], where a failure must not stop the caller (an SMS read, the app's start): the next change or start plans
     * again. Nothing is logged.
     */
    suspend fun syncQuietly() {
        try {
            sync()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Planned again at the next change or start.
        }
    }

    /**
     * Runs the reminder of bill [billId] now, after checking again that it is due one (the data may have changed since
     * [sync] planned it): posts the notification and marks the bill reminded. A run more than a minute before its time
     * (early, or late into quiet hours) waits again for it.
     */
    suspend fun remind(billId: Long): Outcome {
        val now = clock.instant()
        val today = now.atZone(clock.zone).toLocalDate()
        val appSettings = settings.first()
        val detail = reader.detail(billId) ?: return Outcome.NotDue
        val live = CardBillMath.isLive(detail.facts, listOf(detail.bill), appSettings.trackingMode == TrackingMode.SMS, clock.zone) &&
            detail.current
        val at = CardBillMath.reminderAt(detail.bill, live, detail.paidOn, appSettings.remindersOn, now, clock.zone)
            ?: return Outcome.NotDue
        if (Duration.between(now, at) > CardBillMath.ON_TIME) {
            enqueue(workManager(), billId, at, now)
            return Outcome.Later
        }
        if (!CardBillNotifications.enabled(context)) return Outcome.NotificationsOff
        CardBillNotifications.post(context, detail, today)
        bills.setRemindedAt(billId, now)
        return Outcome.Posted
    }

    private fun enqueue(manager: WorkManager, billId: Long, at: Instant, now: Instant) {
        val delay = Duration.between(now, at).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<CardBillReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(CardBillReminderWorker.KEY_BILL_ID to billId))
            .addTag(TAG)
            .addTag(uniqueName(billId))
            .addTag(FIRE_AT_PREFIX + at.toEpochMilli())
            .build()
        manager.enqueueUniqueWork(uniqueName(billId), ExistingWorkPolicy.REPLACE, request)
    }

    /** Constants of the reminder. */
    companion object {
        /** How many days before the due date the reminder goes (D2; You draws "3 days before a card bill is due"). */
        const val DAYS_BEFORE: Int = 3

        /** The tag of every reminder's work. */
        const val TAG: String = "card_bill_reminder"

        private const val FIRE_AT_PREFIX = "card_bill_fire_at_"

        /** The unique work name, and a tag, of bill [billId]'s reminder. */
        fun uniqueName(billId: Long): String = "${TAG}_$billId"

        private fun billIdOf(info: WorkInfo): Long? =
            info.tags.firstNotNullOfOrNull { tag -> tag.removePrefix(TAG + "_").takeIf { it != tag }?.toLongOrNull() }

        private fun fireAtOf(info: WorkInfo): Long? =
            info.tags.firstNotNullOfOrNull { tag -> tag.removePrefix(FIRE_AT_PREFIX).takeIf { it != tag }?.toLongOrNull() }
    }
}
