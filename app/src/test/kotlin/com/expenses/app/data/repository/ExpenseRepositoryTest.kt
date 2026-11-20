package com.expenses.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.parser.Direction
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseRepositoryTest {
    private val test = TestDatabase()
    private val repo = test.expenses
    private val november = YearMonth.of(2026, 11)

    @After fun close() = test.close()

    private fun sms(
        amountMinor: Long,
        at: Instant = nov(10),
        direction: Direction = Direction.DEBIT,
        status: ExpenseStatus = ExpenseStatus.DETECTED,
        currency: String = Expense.INR,
        counterparty: String? = "Swiggy",
        accountId: Long? = null,
        smsId: Long? = null,
        referenceId: String? = null,
    ) = Expense(
        amountMinor = amountMinor,
        currency = currency,
        direction = direction,
        counterparty = counterparty,
        category = Category.FOOD,
        occurredAt = at,
        source = ExpenseSource.SMS,
        status = status,
        paidWith = PaidWith.UPI,
        accountId = accountId,
        referenceId = referenceId,
        smsId = smsId,
        smsReceivedAt = at,
    )

    @Test fun monthTotalCountsOnlyAddedRupeeDebitsByShare() = runTest {
        repo.insert(sms(45_000, status = ExpenseStatus.ADDED))
        repo.insert(sms(10_000, status = ExpenseStatus.DETECTED))
        repo.insert(sms(20_000, status = ExpenseStatus.IGNORED))
        repo.insert(sms(30_000, status = ExpenseStatus.ADDED, direction = Direction.CREDIT))
        repo.insert(sms(5_000, status = ExpenseStatus.ADDED, currency = "USD"))
        repo.insert(sms(60_000, status = ExpenseStatus.ADDED).copy(myShareMinor = 20_000))
        repo.insert(sms(7_000, status = ExpenseStatus.ADDED).copy(scope = ExpenseScope.FAMILY))

        assertEquals(45_000L + 20_000 + 7_000, repo.observeMonthTotal(november).first())
    }

    @Test fun monthTotalUsesLocalMonthBounds() = runTest {
        // 1 Nov 00:10 in India is still 31 Oct in UTC; 1 Dec 00:00 in India belongs to December.
        repo.insert(sms(1_000, at = nov(1, 0, 10), status = ExpenseStatus.ADDED))
        repo.insert(sms(2_000, at = nov(30, 23, 59), status = ExpenseStatus.ADDED))
        repo.insert(sms(4_000, at = nov(30, 23, 59).plusSeconds(60), status = ExpenseStatus.ADDED))
        repo.insert(sms(8_000, at = nov(1).minusSeconds(86_400), status = ExpenseStatus.ADDED))

        assertEquals(3_000L, repo.observeMonthTotal(november).first())
        assertEquals(4_000L, repo.observeMonthTotal(YearMonth.of(2026, 12)).first())
        assertEquals(0L, repo.observeMonthTotal(YearMonth.of(2027, 1)).first())
    }

    @Test fun addIgnoreAndUndo() = runTest {
        val id = repo.insert(sms(45_000))
        repo.add(id, Category.GROCERIES, ExpenseScope.FAMILY, note = "  ")
        val added = repo.get(id)!!
        assertEquals(ExpenseStatus.ADDED, added.status)
        assertEquals(Category.GROCERIES, added.category)
        assertEquals(ExpenseScope.FAMILY, added.scope)
        assertNull(added.note)
        assertEquals(45_000L, repo.observeMonthTotal(november).first())

        repo.undoAdd(id)
        assertEquals(ExpenseStatus.DETECTED, repo.get(id)!!.status)
        assertEquals(0L, repo.observeMonthTotal(november).first())

        repo.ignore(id)
        assertEquals(ExpenseStatus.IGNORED, repo.get(id)!!.status)
        assertTrue(repo.observeDetected().first().isEmpty())
    }

    @Test fun detectedQueueHidesUntrackedAccountsNewestFirst() = runTest {
        val tracked = test.accounts.findOrCreate("HDFC Bank", AccountType.BANK_ACCOUNT, "4521").account
        val off = test.accounts.findOrCreate("SBI", AccountType.BANK_ACCOUNT, "0034").account
        test.accounts.setTracked(off.id, false)
        val older = repo.insert(sms(100, at = nov(10), accountId = tracked.id))
        val newer = repo.insert(sms(200, at = nov(11)))
        repo.insert(sms(300, at = nov(12), accountId = off.id))
        repo.insert(sms(400, at = nov(13), status = ExpenseStatus.ADDED))

        val queue = repo.observeDetected().first()
        assertEquals(listOf(newer, older), queue.map { it.expense.id })
        assertEquals("HDFC Bank", queue[1].account?.bank)
        assertNull(queue[0].account)

        test.accounts.setTracked(off.id, true)
        assertEquals(3, repo.observeDetected().first().size)
    }

    @Test fun latestAndByDayListAddedDebitsAndCredits() = runTest {
        repo.insert(sms(100, at = nov(10, 9), status = ExpenseStatus.ADDED))
        repo.insert(sms(200, at = nov(10, 18), status = ExpenseStatus.ADDED, direction = Direction.CREDIT))
        repo.insert(sms(300, at = nov(12), status = ExpenseStatus.ADDED))
        repo.insert(sms(400, at = nov(13)))

        assertEquals(listOf(300L, 200L), repo.observeLatest(2).first().map { it.expense.amountMinor })

        val days = repo.observeByDay(november).first()
        assertEquals(listOf(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 10)), days.map { it.date })
        assertEquals(listOf(200L, 100L), days[1].expenses.map { it.expense.amountMinor })
        assertEquals(100L, days[1].spentMinor)
    }

    @Test fun categoryTotalsLargestFirst() = runTest {
        repo.insert(sms(100, status = ExpenseStatus.ADDED))
        repo.insert(sms(200, status = ExpenseStatus.ADDED))
        repo.insert(sms(500, status = ExpenseStatus.ADDED).copy(category = Category.TRAVEL))
        repo.insert(sms(900, status = ExpenseStatus.ADDED, direction = Direction.CREDIT).copy(category = Category.FUN))

        val totals = repo.observeCategoryTotals(november).first()
        assertEquals(listOf(Category.TRAVEL, Category.FOOD), totals.map { it.category })
        assertEquals(2, totals[1].count)
        assertEquals(300L, totals[1].totalMinor)
    }

    @Test fun manualExpenseIsAddedDebit() = runTest {
        val id = repo.addManual(60_000, " Dinner at Truffles ", Category.FOOD, occurredAt = nov(5))
        val expense = repo.get(id)!!
        assertEquals(ExpenseSource.MANUAL, expense.source)
        assertEquals(ExpenseStatus.ADDED, expense.status)
        assertEquals(Direction.DEBIT, expense.direction)
        assertEquals("Dinner at Truffles", expense.counterparty)
        assertEquals(60_000L, expense.myShareMinor)
        assertEquals(60_000L, repo.observeMonthTotal(november).first())
    }

    @Test(expected = SQLiteConstraintException::class)
    fun oneExpensePerSms() = runTest {
        repo.insert(sms(100, smsId = 7))
        repo.insert(sms(200, smsId = 7))
    }

    @Test fun possibleCopiesByWindowOrReference() = runTest {
        val a = repo.insert(sms(45_000, at = nov(10, 12, 0)))
        repo.insert(sms(45_000, at = nov(10, 12, 30)))
        val c = repo.insert(sms(45_000, at = nov(1), referenceId = "631234567890"))
        repo.insert(sms(45_000, at = nov(10, 12, 5), direction = Direction.CREDIT))

        val nearA = repo.findPossibleCopies(Direction.DEBIT, 45_000, Expense.INR, nov(10, 12, 10), null)
        assertEquals(listOf(a), nearA.map { it.id })
        val byRef = repo.findPossibleCopies(Direction.DEBIT, 45_000, Expense.INR, nov(20), "631234567890")
        assertEquals(listOf(c), byRef.map { it.id })
        assertTrue(repo.findPossibleCopies(Direction.DEBIT, 45_001, Expense.INR, nov(10, 12, 0), null).isEmpty())
    }

    @Test fun rememberedCategoryWinsOverKeywords() = runTest {
        assertEquals(Category.FOOD, repo.guessCategory("SWIGGY"))
        val id = repo.insert(sms(45_000, counterparty = "Swiggy"))
        repo.add(id, Category.RENT_AND_BILLS, alwaysThisCategory = true)
        assertEquals(Category.RENT_AND_BILLS, repo.guessCategory("SWIGGY"))
        assertEquals(Category.OTHER, repo.guessCategory("Rahul"))
    }

    @Test fun updateAndDelete() = runTest {
        val id = repo.insert(sms(45_000, status = ExpenseStatus.ADDED))
        repo.update(repo.get(id)!!.copy(note = "Friday dinner"))
        repo.setCategory(id, Category.FUN)
        val detail = repo.observe(id).first()!!
        assertEquals("Friday dinner", detail.expense.note)
        assertEquals(Category.FUN, detail.expense.category)
        repo.delete(id)
        assertNull(repo.observe(id).first())
    }

    /** Records what [ExpenseRepository] tells it, and whether it was told inside a transaction. */
    private inner class RecordingListener : PersonalChangeListener {
        val calls = mutableListOf<String>()

        override suspend fun onChanged(ids: Collection<Long>) {
            calls += "changed ${ids.sorted()} ${test.db.inTransaction()}"
        }

        override suspend fun onDeleting(rows: List<Expense>) {
            calls += "deleting ${rows.map { it.id }.sorted()} ${test.db.inTransaction()}"
        }
    }

    @Test fun everyWriteThatCanChangeSharingTellsTheListenerInsideItsTransaction() = runTest {
        val listener = RecordingListener()
        val repo = ExpenseRepository(test.db.expenseDao(), test.db.categoryRuleDao(), RoomTransactionRunner(test.db), test.clock, personalChanges = { listener })
        val a = repo.insert(sms(10_000, smsId = 1))
        val b = repo.insert(sms(20_000, smsId = 2))
        val c = repo.insert(sms(30_000, smsId = 3))
        assertTrue(listener.calls.isEmpty())

        repo.add(a, Category.FOOD, ExpenseScope.FAMILY)
        repo.addWaiting(listOf(b))
        repo.backToWaiting(listOf(a, b))
        repo.ignoreWaiting(listOf(b))
        repo.ignore(c)
        repo.add(a, Category.FOOD, ExpenseScope.FAMILY)
        repo.undoAdd(a)
        repo.setCategory(a, Category.FUN)
        repo.update(repo.get(a)!!.copy(note = "x"))
        val typed = repo.addManual(5_000, "Milk", Category.GROCERIES, scope = ExpenseScope.FAMILY)
        repo.delete(typed)
        test.db.expenseDao().update(repo.get(c)!!.copy(familyExpenseId = "f1"))
        repo.deleteSmsBefore(nov(30))

        assertEquals(
            listOf(
                "changed [$a] true", "changed [$b] true", "changed [$a, $b] true", "changed [$b] true", "changed [$c] true",
                "changed [$a] true", "changed [$a] true", "changed [$a] true", "changed [$a] true", "changed [$typed] true",
                "deleting [$typed] true", "deleting [$c] true",
            ),
            listener.calls,
        )
        assertNull(repo.get(c))
    }

    @Test fun doThisEveryTimeRemembersTheScopeAndANewPaymentWaitsWithIt() = runTest {
        assertEquals(ExpenseScope.ME, repo.guessScope("Swiggy"))
        assertEquals(ExpenseScope.ME, repo.guessScope(null))
        val first = repo.insert(sms(45_000, counterparty = "Swiggy", smsId = 1))
        repo.add(first, Category.FOOD, ExpenseScope.FAMILY, alwaysThisCategory = true)
        assertEquals(ExpenseScope.FAMILY, repo.guessScope("SWIGGY"))
        assertEquals(Category.FOOD, repo.guessCategory("SWIGGY"))
        assertEquals(ExpenseScope.ME, repo.guessScope("Zomato"))

        // A category remembered without a scope keeps the scope.
        repo.rememberCategory("Swiggy", Category.FUN)
        assertEquals(ExpenseScope.FAMILY, repo.guessScope("Swiggy"))
        assertEquals(Category.FUN, repo.guessCategory("Swiggy"))

        // Just me with the switch on is remembered too; without the switch nothing changes.
        val second = repo.insert(sms(45_000, counterparty = "Swiggy", smsId = 2))
        repo.add(second, Category.FOOD, ExpenseScope.ME, alwaysThisCategory = true)
        assertEquals(ExpenseScope.ME, repo.guessScope("Swiggy"))
        val third = repo.insert(sms(45_000, counterparty = "Swiggy", smsId = 3))
        repo.add(third, Category.FOOD, ExpenseScope.FAMILY)
        assertEquals(ExpenseScope.ME, repo.guessScope("Swiggy"))
        assertEquals(ExpenseScope.FAMILY, repo.get(third)!!.scope)
    }

}
