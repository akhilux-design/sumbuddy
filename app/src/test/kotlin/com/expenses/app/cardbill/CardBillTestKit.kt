package com.expenses.app.cardbill

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.CardBill
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.repository.TestDatabase
import com.expenses.app.data.repository.TestZone
import com.expenses.app.data.settings.AppSettings
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.groups.data.OwedPart
import com.expenses.app.ui.components.AvatarColor
import com.expenses.parser.Direction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows.shadowOf

/**
 * A card, its ₹24,300 bill due on 21 Nov, a test WorkManager and the reminder on top, for the reminder tests. What
 * friends owe comes from [parts], not from groups.
 */
internal class CardBillTestKit {
    val context: Application = ApplicationProvider.getApplicationContext()
    val database = TestDatabase()
    val settings = MutableStateFlow(AppSettings(onboardingDone = true, trackingMode = TrackingMode.SMS, remindersOn = true))
    val signedIn = MutableStateFlow(true)
    val parts = MutableStateFlow<List<OwedPart>>(emptyList())

    /** "Now" for the next [reminders] made. */
    var now: Instant = at(11, 10, 14)

    private var provided: CardBillReminders? = null

    val reader = CardBillReader(
        database.cardBills, database.accounts, database.expenses, { parts }, signedIn, settings, TestZone,
    )

    init {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                CardBillReminderWorker(appContext, workerParameters, provided ?: reminders())
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** The reminder, seeing [now]. */
    fun reminders(): CardBillReminders =
        CardBillReminders(context, database.cardBills, reader, settings, Clock.fixed(now, TestZone)).also { provided = it }

    /** The test WorkManager. */
    val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** HDFC Millennia ••2217, a tracked credit card. */
    suspend fun card(): Long = database.db.accountDao().insert(
        Account(bank = "HDFC Bank", type = AccountType.CREDIT_CARD, last4 = "2217", displayName = "HDFC Millennia", firstSeenAt = at(10, 1)),
    )

    /** Its ₹24,300 bill due on [due], first read on 1 Nov. */
    suspend fun bill(card: Long, due: LocalDate = LocalDate.of(2026, 11, 21), smsId: Long = 900, received: Instant = at(11, 1, 10)): Long =
        database.cardBills.upsert(
            CardBill(
                accountId = card, bank = "HDFC Bank", cardLast4 = "2217", totalDueMinor = 2_430_000, minimumDueMinor = 121_500,
                dueDate = due, receivedAt = received, smsId = smsId,
            ),
        )

    /** A payment of [amount] to the card on [day] November (a "payment received" credit). */
    suspend fun payCard(card: Long, amount: Long = 2_430_000, day: Int = 15) {
        database.db.expenseDao().insert(
            Expense(
                amountMinor = amount, direction = Direction.CREDIT, counterparty = null, category = Category.OTHER,
                occurredAt = at(11, day), source = ExpenseSource.SMS, status = ExpenseStatus.ADDED, accountId = card,
                smsId = 5_000L + day, smsReceivedAt = at(11, day),
            ),
        )
    }

    /** Rahul (on the app) owes ₹4,100 in Goa trip, Priya ₹2,600 in Flat 302 and Kiran ₹1,500 in Dinner. */
    fun drawnFriends() {
        parts.value = listOf(
            OwedPart("g-goa", "Goa trip", "m-rahul", "rahul", "Rahul", true, false, AvatarColor.Green, 500_000, 3, 410_000),
            OwedPart("g-flat", "Flat 302", "m-priya", "priya", "Priya", false, false, AvatarColor.Pink, 260_000, 5, 260_000),
            OwedPart("g-dinner", "Dinner", "m-kiran", "kiran", "Kiran", false, false, AvatarColor.Blue, 150_000, 1, 150_000),
        )
    }

    /** The card bill notifications now showing. */
    fun posted(): List<Notification> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            .filter { it.channelId == CardBillNotifications.CHANNEL_ID }

    fun close() {
        database.close()
    }

    companion object {
        fun at(month: Int, day: Int, hour: Int = 12, minute: Int = 0): Instant =
            LocalDateTime.of(2026, month, day, hour, minute).atZone(TestZone).toInstant()
    }
}
