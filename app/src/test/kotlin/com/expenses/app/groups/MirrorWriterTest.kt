package com.expenses.app.groups

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.repository.nov
import com.expenses.app.groups.api.ExpenseDto
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.data.shareEntities
import com.expenses.app.groups.data.toEntity
import com.expenses.parser.Direction
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Personal rows and group expenses (docs/server.md 9.4): every case, as the month total sees them. */
@RunWith(RobolectricTestRunner::class)
class MirrorWriterTest {
    private val env = GroupsTestEnv()
    private val dao = env.db.groupDao()
    private val personal = env.db.expenseDao()
    private val november = YearMonth.of(2026, 11)
    private val id = "f0000000-0000-4000-8000-000000000010"

    @After fun close() = env.close()

    /** Stores [expense] as the server sent it and runs the mirror rules, as a pull does. */
    private suspend fun pulled(expense: ExpenseDto) = env.transactions.run {
        dao.upsertExpense(expense.toEntity(SyncState.SYNCED, env.clock.instant())!!)
        dao.deleteShares(expense.id)
        dao.insertShares(expense.shareEntities())
        env.mirrors.sync(expense.id)
    }

    private suspend fun deleted(expenseId: String) = env.transactions.run {
        dao.upsertExpense(dao.getExpense(expenseId)!!.copy(deleted = true))
        dao.deleteShares(expenseId)
        env.mirrors.sync(expenseId)
    }

    private suspend fun month() = env.expenses.observeMonthTotal(november).first()

    @Test fun someoneElsePaidAndIHaveAShare() = runTest {
        env.seedGroup()
        pulled(expenseDto(id, M_RAHUL, 45_000, mapOf(M_ME to 15_000, M_RAHUL to 30_000), 6))
        val mirror = personal.findByGroupExpense(id)!!
        assertEquals(ExpenseSource.GROUP, mirror.source)
        assertEquals(ExpenseStatus.ADDED, mirror.status)
        assertEquals(Direction.DEBIT, mirror.direction)
        assertEquals(PaidWith.OTHER, mirror.paidWith)
        assertNull(mirror.accountId)
        assertEquals("Swiggy", mirror.counterparty)
        assertEquals(nov(12, 0, 0), mirror.occurredAt)
        assertEquals(15_000L, month())
    }

    @Test fun noShareMakesNoMirror() = runTest {
        env.seedGroup()
        pulled(expenseDto(id, M_RAHUL, 30_000, mapOf(M_RAHUL to 15_000, M_PRIYA to 15_000), 6))
        assertNull(personal.findByGroupExpense(id))
    }

    @Test fun aMirrorFollowsEditsAndGoesWhenMyShareIsZeroOrDeleted() = runTest {
        env.seedGroup()
        pulled(expenseDto(id, M_RAHUL, 45_000, mapOf(M_ME to 15_000, M_RAHUL to 30_000), 6))
        pulled(expenseDto(id, M_RAHUL, 60_000, mapOf(M_ME to 20_000, M_RAHUL to 40_000), 7, description = "Dinner"))
        assertEquals(20_000L, personal.findByGroupExpense(id)!!.myShareMinor)
        assertEquals("Dinner", personal.findByGroupExpense(id)!!.counterparty)
        pulled(expenseDto(id, M_RAHUL, 60_000, mapOf(M_RAHUL to 60_000), 8))
        assertNull(personal.findByGroupExpense(id))
        pulled(expenseDto(id, M_RAHUL, 60_000, mapOf(M_ME to 30_000, M_RAHUL to 30_000), 9))
        assertNotNull(personal.findByGroupExpense(id))
        deleted(id)
        assertNull(personal.findByGroupExpense(id))
        assertEquals(0L, month())
    }

    @Test fun myPaymentSplitCountsMyShare() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        env.groups.splitPayment(payment, G1, com.expenses.splits.SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = false, auto = false)
        val row = env.expenses.get(payment)!!
        assertEquals(15_000L, row.myShareMinor)
        assertEquals(ExpenseStatus.ADDED, row.status)
        assertEquals(15_000L, month())
        // The bank's facts stay: amount, payee, time and the original message.
        assertEquals(45_000L, row.amountMinor)
        assertEquals("Swiggy", row.counterparty)
        assertEquals(nov(10, 20, 15), row.occurredAt)
        assertNotNull(row.originalMessage)
    }

    @Test fun aPayerWithAZeroShareKeepsTheLinkAtZero() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(30_000)
        val split = env.groups.splitPayment(payment, G1, com.expenses.splits.SplitMethod.AMOUNTS, mapOf(M_ME to 0L, M_RAHUL to 30_000L), rememberRule = false, auto = false)
        val row = env.expenses.get(payment)!!
        assertEquals(split, row.groupExpenseId)
        assertEquals(0L, row.myShareMinor)
        assertEquals(0L, month())
        // A later sync of the same expense keeps it at 0, not the full amount.
        pulled(expenseDto(split, M_ME, 30_000, mapOf(M_ME to 0, M_RAHUL to 30_000), 6))
        assertEquals(0L, env.expenses.get(payment)!!.myShareMinor)
    }

    @Test fun thePayerChangingAwayFromMeUnlinksAndMirrorsMyShare() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        val split = env.groups.splitPayment(payment, G1, com.expenses.splits.SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = false, auto = false)
        pulled(expenseDto(split, M_RAHUL, 45_000, mapOf(M_ME to 15_000, M_RAHUL to 15_000, M_PRIYA to 15_000), 6))
        val row = env.expenses.get(payment)!!
        assertNull(row.groupExpenseId)
        assertEquals(45_000L, row.myShareMinor)
        val mirror = personal.findByGroupExpense(split)!!
        assertEquals(ExpenseSource.GROUP, mirror.source)
        assertEquals(15_000L, mirror.myShareMinor)
        assertEquals(60_000L, month())
    }

    @Test fun aDeletedSplitCountsMyPaymentInFullAgain() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        val split = env.groups.splitPayment(payment, G1, com.expenses.splits.SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = false, auto = false)
        pulled(expenseDto(split, M_ME, 45_000, mapOf(M_ME to 15_000, M_RAHUL to 15_000, M_PRIYA to 15_000), 6))
        deleted(split)
        val row = env.expenses.get(payment)!!
        assertNull(row.groupExpenseId)
        assertEquals(45_000L, row.myShareMinor)
        assertEquals(ExpenseStatus.ADDED, row.status)
    }

    @Test fun aRestoredExpenseLinksToTheOneMatchingPayment() = runTest {
        env.seedGroup()
        // The payment read again by "Scan SMS on this phone": waiting, not linked, same amount, a day apart at most.
        val payment = env.smsPayment(45_000)
        pulled(expenseDto(id, M_ME, 45_000, mapOf(M_ME to 15_000, M_RAHUL to 30_000), 6, occurredOn = "2026-11-10"))
        val row = env.expenses.get(payment)!!
        assertEquals(id, row.groupExpenseId)
        assertEquals(ExpenseStatus.ADDED, row.status)
        assertEquals(15_000L, row.myShareMinor)
        assertEquals(1, personal.observeLatest(10).first().size)
    }

    @Test fun aRestoredExpenseWithTwoMatchesMakesAMirror() = runTest {
        env.seedGroup()
        val first = env.smsPayment(45_000)
        env.expenses.insert(env.expenses.get(first)!!.copy(id = 0, smsId = 9_999))
        pulled(expenseDto(id, M_ME, 45_000, mapOf(M_ME to 15_000, M_RAHUL to 30_000), 6, occurredOn = "2026-11-10"))
        val mirror = personal.findByGroupExpense(id)!!
        assertEquals(ExpenseSource.GROUP, mirror.source)
        assertEquals(15_000L, mirror.myShareMinor)
        assertNull(env.expenses.get(first)!!.groupExpenseId)
    }

    @Test fun aRestoredExpenseFarFromAnyPaymentMakesAMirror() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        pulled(expenseDto(id, M_ME, 45_000, mapOf(M_ME to 15_000, M_RAHUL to 30_000), 6, occurredOn = "2026-11-14"))
        assertNull(env.expenses.get(payment)!!.groupExpenseId)
        assertEquals(ExpenseSource.GROUP, personal.findByGroupExpense(id)!!.source)
    }

    @Test fun aTypedGroupExpenseIPaidIsATypedPayment() = runTest {
        env.seedGroup()
        val split = env.groups.addGroupExpense(
            G1,
            com.expenses.app.groups.data.GroupExpenseDraft(
                "Groceries", Category.GROCERIES, 90_000, java.time.LocalDate.of(2026, 11, 14), M_ME,
                com.expenses.splits.SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null),
            ),
        )
        val row = personal.findByGroupExpense(split)!!
        assertEquals(ExpenseSource.MANUAL, row.source)
        assertEquals(90_000L, row.amountMinor)
        assertEquals(30_000L, row.myShareMinor)
        assertEquals(30_000L, month())
    }

    @Test fun forgettingASplitWithNothingRememberedKeepsATypedExpenseAddedAndLetsAnSmsPaymentWait() = runTest {
        env.seedGroup()
        val halves = mapOf(M_ME to null, M_RAHUL to null)
        val typed = env.groups.addGroupExpense(
            G1,
            com.expenses.app.groups.data.GroupExpenseDraft(
                "Groceries", Category.GROCERIES, 90_000, java.time.LocalDate.of(2026, 11, 14), M_ME,
                com.expenses.splits.SplitMethod.EQUAL, halves,
            ),
        )
        val typedRow = personal.findByGroupExpense(typed)!!.id
        val payment = env.smsPayment(45_000)
        val split = env.groups.splitPayment(payment, G1, com.expenses.splits.SplitMethod.EQUAL, halves, rememberRule = false, auto = false)
        assertEquals(45_000L + 22_500L, month())
        // Links made before the phone remembered how a payment was before its split (database version 3).
        env.db.openHelper.writableDatabase.execSQL("DELETE FROM grp_link_priors")
        assertNull(dao.getLinkPrior(typed))

        // The person's join was undone: the splits of the place are forgotten.
        env.transactions.run {
            env.mirrors.forget(typed)
            env.mirrors.forget(split)
        }

        // Typed in by the person: their own expense, in full. It was never detected, so it must not wait as
        // "Payment detected" on Home and in Review payments.
        val own = env.expenses.get(typedRow)!!
        assertEquals(ExpenseSource.MANUAL, own.source)
        assertEquals(ExpenseStatus.ADDED, own.status)
        assertEquals(90_000L, own.myShareMinor)
        assertNull(own.groupExpenseId)
        // Read from an SMS: it waits again for Add, Split or Ignore.
        val sms = env.expenses.get(payment)!!
        assertEquals(ExpenseStatus.DETECTED, sms.status)
        assertEquals(45_000L, sms.myShareMinor)
        assertNull(sms.groupExpenseId)
        assertEquals(90_000L, month())
    }

    @Test fun forgettingASplitPutsAPaymentBackToTheStatusRemembered() = runTest {
        env.seedGroup()
        val halves = mapOf(M_ME to null, M_RAHUL to null)
        val added = env.smsPayment(45_000, status = ExpenseStatus.ADDED)
        val split = env.groups.splitPayment(added, G1, com.expenses.splits.SplitMethod.EQUAL, halves, rememberRule = false, auto = false)

        env.transactions.run { env.mirrors.forget(split) }

        // Added before it was split: added again, in full. What is remembered wins over the row's source.
        assertEquals(ExpenseStatus.ADDED, env.expenses.get(added)!!.status)
        assertEquals(45_000L, env.expenses.get(added)!!.myShareMinor)
    }

    @Test fun aDifferentAccountDropsMirrorsAndUnlinksPayments() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        env.groups.splitPayment(payment, G1, com.expenses.splits.SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = false, auto = false)
        pulled(expenseDto(id, M_RAHUL, 30_000, mapOf(M_ME to 15_000, M_RAHUL to 15_000), 6))
        env.local.switchAccount()
        assertNull(personal.findByGroupExpense(id))
        assertNull(env.expenses.get(payment)!!.groupExpenseId)
        assertNull(dao.getGroup(G1))
        assertEquals(0, env.db.outboxDao().all().size)
    }

    @Test fun signingOutKeepsMirrors() = runTest {
        env.seedGroup()
        pulled(expenseDto(id, M_RAHUL, 30_000, mapOf(M_ME to 15_000, M_RAHUL to 15_000), 6))
        env.local.clearAll()
        assertNull(dao.getGroup(G1))
        assertEquals(15_000L, personal.findByGroupExpense(id)!!.myShareMinor)
        assertEquals(15_000L, month())
    }
}
