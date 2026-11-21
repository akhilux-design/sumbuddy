package com.expenses.app.ui.theme

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.testing.TestListenableWorkerBuilder
import com.expenses.app.R
import com.expenses.app.cardbill.CardBillDetail
import com.expenses.app.cardbill.CardBillNotifications
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.CardBill
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.repository.nov
import com.expenses.app.groups.sync.GroupEvent
import com.expenses.app.groups.sync.GroupNotifications
import com.expenses.app.groups.sync.SyncWorker
import com.expenses.app.sms.PaymentNotifications
import com.expenses.app.sms.readingForegroundInfo
import com.expenses.parser.Direction
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every notification the app builds has the design's look (02-everyday/04, 53, 54, 146): the "₹" of the App badge as
 * its one small icon, and the brand colour, `color/brand/primary`, as its colour; none is colourised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationLookTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)

    private val swiggy = Expense(
        id = 11,
        amountMinor = 45_000,
        direction = Direction.DEBIT,
        counterparty = "Swiggy",
        category = Category.FOOD,
        occurredAt = nov(16),
        source = ExpenseSource.SMS,
        status = ExpenseStatus.DETECTED,
        smsReceivedAt = nov(16),
    )

    private val card = Account(
        id = 3, bank = "HDFC Bank", type = AccountType.CREDIT_CARD, last4 = "2217", displayName = "HDFC Millennia", firstSeenAt = nov(1),
    )

    @Before fun allowNotifications() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun posted(channel: String): List<Notification> =
        shadowOf(manager).allNotifications.filter { it.channelId == channel }

    private fun assertAppLook(notification: Notification) {
        assertEquals(R.drawable.ic_notification, notification.smallIcon.resId)
        assertEquals(ContextCompat.getColor(app, R.color.brand_primary), notification.color)
        assertFalse(notification.extras.getBoolean(NotificationCompat.EXTRA_COLORIZED))
    }

    @Test fun paymentDetected() {
        PaymentNotifications.show(app, swiggy, card)
        assertAppLook(posted(PaymentNotifications.CHANNEL_ID).single())
    }

    @Test fun automaticSplit() {
        PaymentNotifications.showAutoSplit(app, swiggy, "g-flat", "Flat 302", 15_000, YearMonth.of(2026, 11))
        assertAppLook(posted(PaymentNotifications.AUTO_SPLIT_CHANNEL_ID).single())
    }

    @Test fun cardBillReminder() {
        val bill = CardBill(
            id = 5, accountId = card.id, bank = "HDFC Bank", cardLast4 = "2217", totalDueMinor = 2_430_000,
            minimumDueMinor = 121_500, dueDate = LocalDate.of(2026, 11, 21), receivedAt = nov(1), smsId = 900,
        )
        val detail = CardBillDetail(
            bill = bill, account = card, statementDate = LocalDate.of(2026, 11, 1), paidOn = null, signedIn = false,
            friends = emptyList(), current = true,
        )
        CardBillNotifications.post(app, detail, LocalDate.of(2026, 11, 18))
        assertAppLook(posted(CardBillNotifications.CHANNEL_ID).single())
    }

    @Test fun groupsAndTheirSummary() {
        GroupNotifications(app).post(
            listOf(
                GroupEvent.SettlementRecorded("g-flat", "s1", "Rahul", 120_000, canConfirm = true),
                GroupEvent.MemberJoined("g-goa", "m-priya", "Priya", "Goa trip"),
            ),
        )
        // Nothing else was posted: two notifications and the summary over them.
        val groups = shadowOf(manager).allNotifications
        assertEquals(3, groups.size)
        assertTrue(groups.any { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 })
        groups.forEach(::assertAppLook)
    }

    @Test fun readingSms() {
        assertAppLook(readingForegroundInfo(app, 41).notification)
    }

    @Test fun groupSync() = runTest {
        assertAppLook(TestListenableWorkerBuilder<SyncWorker>(app).build().getForegroundInfo().notification)
    }

    @Test fun theColourIsTheBrandTokenOfTheLightTheme() {
        assertEquals(LightColors.brandPrimary.toArgb(), ContextCompat.getColor(app, R.color.brand_primary))
    }

    @Config(qualifiers = "night")
    @Test fun theColourIsTheBrandTokenOfTheDarkTheme() {
        assertEquals(DarkColors.brandPrimary.toArgb(), ContextCompat.getColor(app, R.color.brand_primary))
    }

    @Test fun theIconIsA24dpVector() {
        val icon = ContextCompat.getDrawable(app, R.drawable.ic_notification)!!
        val px = (24 * app.resources.displayMetrics.density).toInt()
        assertEquals(px, icon.intrinsicWidth)
        assertEquals(px, icon.intrinsicHeight)
    }
}
