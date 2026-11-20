package com.expenses.app.data.repository

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.parser.Direction
import java.time.Instant
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

/** The Review payments side of [ExpenseRepository]: the waiting stack, Add all, Ignore and Undo. */
@RunWith(RobolectricTestRunner::class)
class WaitingPaymentsTest {
    private val test = TestDatabase()
    private val repo = test.expenses
    private val november = YearMonth.of(2026, 11)

    @After fun close() = test.close()

    private fun sms(
        amountMinor: Long,
        at: Instant = nov(10),
        direction: Direction = Direction.DEBIT,
        status: ExpenseStatus = ExpenseStatus.DETECTED,
        category: Category = Category.FOOD,
        accountId: Long? = null,
        smsId: Long? = null,
    ) = Expense(
        amountMinor = amountMinor,
        direction = direction,
        counterparty = "Swiggy",
        category = category,
        occurredAt = at,
        source = ExpenseSource.SMS,
        status = status,
        paidWith = PaidWith.UPI,
        accountId = accountId,
        smsId = smsId,
        smsReceivedAt = at,
        originalMessage = "Rs.$amountMinor debited",
    )

    @Test fun waitingIsOldestFirstDebitsOfTrackedAccountsOnly() = runTest {
        val tracked = test.accounts.findOrCreate("HDFC Bank", AccountType.CREDIT_CARD, "4521").account
        val off = test.accounts.findOrCreate("SBI", AccountType.BANK_ACCOUNT, "0034").account
        test.accounts.setTracked(off.id, false)
        val newest = repo.insert(sms(300, at = nov(12)))
        val oldest = repo.insert(sms(100, at = nov(9), accountId = tracked.id))
        val middle = repo.insert(sms(200, at = nov(10)))
        repo.insert(sms(400, at = nov(8), direction = Direction.CREDIT))
        repo.insert(sms(500, at = nov(8), accountId = off.id))
        repo.insert(sms(600, at = nov(8), status = ExpenseStatus.ADDED))
        repo.insert(sms(700, at = nov(8), status = ExpenseStatus.IGNORED))

        val waiting = repo.observeWaiting().first()
        assertEquals(listOf(oldest, middle, newest), waiting.map { it.expense.id })
        assertEquals("HDFC Bank", waiting[0].account?.bank)
    }

    @Test fun addWaitingAddsEachWithItsCategoryInOneGo() = runTest {
        val food = repo.insert(sms(100, category = Category.FOOD))
        val travel = repo.insert(sms(200, at = nov(11), category = Category.TRAVEL))
        val credit = repo.insert(sms(400, direction = Direction.CREDIT))
        val ignored = repo.insert(sms(800, status = ExpenseStatus.IGNORED))

        val added = repo.addWaiting(listOf(food, travel, credit, ignored, 999))

        assertEquals(setOf(food, travel), added.toSet())
        assertEquals(ExpenseStatus.ADDED, repo.get(food)!!.status)
        assertEquals(Category.FOOD, repo.get(food)!!.category)
        assertEquals(Category.TRAVEL, repo.get(travel)!!.category)
        assertEquals(ExpenseStatus.IGNORED, repo.get(ignored)!!.status)
        assertEquals(300L, repo.observeMonthTotal(november).first())
        assertTrue(repo.observeWaiting().first().isEmpty())
        // Added payments keep their SMS text for Expense detail.
        assertEquals("Rs.100 debited", repo.get(food)!!.originalMessage)
    }

    @Test fun undoOfAddAllPutsEveryPaymentBackAsItWas() = runTest {
        val first = repo.insert(sms(100, category = Category.GROCERIES))
        val second = repo.insert(sms(200, at = nov(11)))
        val added = repo.addWaiting(listOf(first, second))

        val back = repo.backToWaiting(added)

        assertEquals(added.toSet(), back.toSet())
        assertEquals(listOf(first, second), repo.observeWaiting().first().map { it.expense.id })
        assertEquals(Category.GROCERIES, repo.get(first)!!.category)
        assertEquals(0L, repo.observeMonthTotal(november).first())
    }

    @Test fun ignoreWaitingDropsTextAndUndoDoesNotBringItBack() = runTest {
        val id = repo.insert(sms(100))

        assertEquals(listOf(id), repo.ignoreWaiting(listOf(id)))
        assertEquals(ExpenseStatus.IGNORED, repo.get(id)!!.status)
        assertNull(repo.get(id)!!.originalMessage)

        repo.backToWaiting(listOf(id))
        assertEquals(ExpenseStatus.DETECTED, repo.get(id)!!.status)
        assertNull(repo.get(id)!!.originalMessage)
    }

    @Test fun decisionsLeaveAlonePaymentsNoLongerWaiting() = runTest {
        val handledElsewhere = repo.insert(sms(100))
        repo.add(handledElsewhere, Category.FUN)

        assertTrue(repo.ignoreWaiting(listOf(handledElsewhere)).isEmpty())
        assertEquals(ExpenseStatus.ADDED, repo.get(handledElsewhere)!!.status)

        // Undo touches only payments read from SMS that were decided, never a manual expense or a waiting one.
        val manual = repo.addManual(500, "Chai", Category.FOOD)
        val waiting = repo.insert(sms(200, at = nov(11)))
        assertEquals(emptyList<Long>(), repo.backToWaiting(listOf(manual, waiting)))
        assertEquals(ExpenseStatus.ADDED, repo.get(manual)!!.status)
    }

    @Test fun duplicateRulesStillSeeDecidedPayments() = runTest {
        val id = repo.insert(sms(45_000, smsId = 7))
        repo.addWaiting(listOf(id))

        val copies = repo.findPossibleCopies(Direction.DEBIT, 45_000, Expense.INR, nov(10).plusSeconds(600), null)
        assertEquals(listOf(id), copies.map { it.id })
    }

    @Test fun manyIdsAreSplitIntoChunks() = runTest {
        val ids = (1..1_000).map { repo.insert(sms(it.toLong(), at = nov(1).plusSeconds(it.toLong()))) }

        assertEquals(1_000, repo.addWaiting(ids).size)
        assertTrue(repo.observeWaiting().first().isEmpty())
        assertEquals(1_000, repo.backToWaiting(ids).size)
        assertEquals(1_000, repo.observeWaiting().first().size)
    }
}
