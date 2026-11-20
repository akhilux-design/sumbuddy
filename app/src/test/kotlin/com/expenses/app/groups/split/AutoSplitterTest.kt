package com.expenses.app.groups.split

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.ingest.IngestResult
import com.expenses.app.sms.PaymentNotifications
import com.expenses.splits.SplitMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The automatic split (docs/server.md 9.4 "Recurring rule"): which new payments split themselves, and which do not. */
@RunWith(RobolectricTestRunner::class)
class AutoSplitterTest {
    private val env = GroupsTestEnv()
    private val app: Application = RuntimeEnvironment.getApplication()
    private val splitter = AutoSplitter(app, env.groups, env.expenses, env.signedIn, env.clock)
    private val everyone = mapOf<String, Long?>(M_ME to null, M_RAHUL to null, M_PRIYA to null)

    @Before fun grant() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @After fun close() = env.close()

    /** Rent split with Flat 302, equally, "Yes, every month". */
    private suspend fun automaticRent() {
        env.seedGroup()
        env.groups.splitPayment(env.smsPayment(2_400_000, "Rent"), G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = true)
    }

    private suspend fun newPayments(vararg ids: Long) = IngestResult(detected = ids.map { env.expenses.get(it)!! })

    private fun autoSplitNotifications() =
        shadowOf(app.getSystemService(NotificationManager::class.java)).activeNotifications
            .filter { it.notification.channelId == PaymentNotifications.AUTO_SPLIT_CHANNEL_ID }

    @Test fun anAutomaticRuleSplitsTheNewPaymentAndSaysSo() = runTest {
        automaticRent()
        val rent = env.smsPayment(2_400_300, "Rent")
        val swiggy = env.smsPayment(45_000, "Swiggy")

        val handled = splitter.onNewPayments(newPayments(rent, swiggy))

        assertEquals(setOf(rent), handled)
        val row = env.expenses.get(rent)!!
        assertEquals(ExpenseStatus.ADDED, row.status)
        assertEquals(800_100L, row.myShareMinor)
        val stored = env.groups.observeGroup(G1).first()!!.expenses.single { it.expense.id == row.groupExpenseId }
        assertTrue(stored.expense.auto)
        assertEquals(SplitMethod.EQUAL, stored.expense.splitMethod)
        // Swiggy has no rule: it waits for a decision.
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(swiggy)!!.status)

        // One notification, for the rent, in place of "Payment detected".
        val posted = autoSplitNotifications().single()
        val extras = posted.notification.extras
        assertTrue(extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString().endsWith(" · split with Flat 302"))
        assertEquals("Your ₹8,001 share counts in November. Nothing to tick.", extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertNotNull(posted.notification.contentIntent)
    }

    @Test fun aRuleThatAsksEachTimeDoesNothing() = runTest {
        env.seedGroup()
        env.groups.splitPayment(env.smsPayment(2_400_000, "Rent"), G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = false)
        val rent = env.smsPayment(2_400_300, "Rent")

        assertEquals(emptySet<Long>(), splitter.onNewPayments(newPayments(rent)))
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(rent)!!.status)
    }

    @Test fun aRemovedMemberStopsTheRule() = runTest {
        automaticRent()
        val dao = env.db.groupDao()
        dao.upsertMembers(listOf(dao.getMember(M_PRIYA)!!.copy(removed = true)))
        val rent = env.smsPayment(2_400_300, "Rent")

        assertEquals(emptySet<Long>(), splitter.onNewPayments(newPayments(rent)))
        val row = env.expenses.get(rent)!!
        assertEquals(ExpenseStatus.DETECTED, row.status)
        assertNull(row.groupExpenseId)
        assertTrue(autoSplitNotifications().isEmpty())
    }

    @Test fun signedOutNothingIsSplit() = runTest {
        automaticRent()
        val rent = env.smsPayment(2_400_300, "Rent")
        env.me = null

        assertEquals(emptySet<Long>(), splitter.onNewPayments(newPayments(rent)))
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(rent)!!.status)
    }

    @Test fun aPaymentNoLongerWaitingIsLeftAlone() = runTest {
        automaticRent()
        val added = env.smsPayment(2_400_300, "Rent")
        val ignored = env.smsPayment(2_400_500, "Rent")
        val result = newPayments(added, ignored)
        // Decided in the meantime, before the hook ran.
        env.expenses.add(added, Category.RENT_AND_BILLS)
        env.expenses.ignore(ignored)

        assertEquals(emptySet<Long>(), splitter.onNewPayments(result))
        assertNull(env.expenses.get(added)!!.groupExpenseId)
        assertEquals(ExpenseStatus.IGNORED, env.expenses.get(ignored)!!.status)
    }

    @Test fun fixedAmountsThatDoNotFitAreLeftAlone() = runTest {
        env.seedGroup()
        env.groups.splitPayment(
            env.smsPayment(2_400_000, "Rent"), G1, SplitMethod.AMOUNTS,
            mapOf(M_ME to 1_200_000L, M_RAHUL to 600_000L, M_PRIYA to 600_000L), rememberRule = true, auto = true,
        )
        val rent = env.smsPayment(2_500_000, "Rent")

        assertEquals(emptySet<Long>(), splitter.onNewPayments(newPayments(rent)))
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(rent)!!.status)
    }

    @Test fun nothingOfTheSmsReachesTheServer() = runTest {
        automaticRent()
        val rent = env.smsPayment(2_400_300, "Rent")
        splitter.onNewPayments(newPayments(rent))
        // The split is queued as every split is; nothing was sent, and nothing will carry the SMS text.
        assertTrue(env.fake.requests.isEmpty())
        val bodies = env.db.outboxDao().all().mapNotNull { it.bodyJson }
        assertTrue(bodies.none { "debited" in it || "123456789012" in it || "VM-HDFCBK" in it })
    }
}
