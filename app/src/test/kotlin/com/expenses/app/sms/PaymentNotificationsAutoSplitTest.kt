package com.expenses.app.sms

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.repository.nov
import com.expenses.parser.Direction
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The notification of a payment that split itself (not designed): what it says, where it goes, and what it leaves. */
@RunWith(RobolectricTestRunner::class)
class PaymentNotificationsAutoSplitTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)

    private val rent = Expense(
        id = 7,
        amountMinor = 2_400_000,
        direction = Direction.DEBIT,
        counterparty = "Rent",
        category = Category.RENT_AND_BILLS,
        occurredAt = nov(1),
        source = ExpenseSource.SMS,
        status = ExpenseStatus.ADDED,
        smsReceivedAt = nov(1),
        originalMessage = "Rs.24000 debited from a/c XX1234 to Rent. Ref 123456789012",
    )

    private fun show() = PaymentNotifications.showAutoSplit(
        context = app,
        expense = rent,
        groupId = "b0000000-0000-4000-8000-000000000001",
        groupName = "Flat 302",
        myShareMinor = 800_000,
        month = YearMonth.of(2026, 11),
    )

    @Test fun saysWhatWasSplitWithWhomAndWhatCounts() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()

        val posted = shadowOf(manager).activeNotifications.single()
        val notification = posted.notification
        assertEquals(PaymentNotifications.AUTO_SPLIT_CHANNEL_ID, notification.channelId)
        val title = notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString()
        val text = notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()
        assertTrue(title.startsWith("₹24,000 "))
        assertTrue(title.contains("Rent"))
        assertTrue(title.endsWith(" · split with Flat 302"))
        assertEquals("Your ₹8,000 share counts in November. Nothing to tick.", text)
        // It replaces "Payment detected": no Add, Split or Ignore, and a tap opens the group.
        assertEquals(0, notification.actions?.size ?: 0)
        assertNotNull(notification.contentIntent)
        // The SMS text never shows.
        assertTrue(listOf(title, text).none { "debited" in it || "123456789012" in it })
        // Its own channel, so the person can turn these off apart from "Payment detected".
        assertNotNull(manager.getNotificationChannel(PaymentNotifications.AUTO_SPLIT_CHANNEL_ID))
        assertNotNull(manager.getNotificationChannel(PaymentNotifications.CHANNEL_ID))
    }

    @Test fun clearingHandledPaymentsLeavesIt() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()
        PaymentNotifications.cancelHandled(app, waitingIds = emptySet())
        assertEquals(1, shadowOf(manager).activeNotifications.size)
    }

    @Test fun nothingWithoutPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()
        assertTrue(shadowOf(manager).activeNotifications.isEmpty())
    }
}
