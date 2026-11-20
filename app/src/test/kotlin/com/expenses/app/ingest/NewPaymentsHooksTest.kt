package com.expenses.app.ingest

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.repository.nov
import com.expenses.parser.Direction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The order of the hooks after new SMS: what one handled never reaches the next (docs/groups-build-plan.md). */
class NewPaymentsHooksTest {
    private fun payment(id: Long, direction: Direction = Direction.DEBIT) = Expense(
        id = id, amountMinor = 120_000, direction = direction, counterparty = "Rahul", category = Category.OTHER,
        occurredAt = nov(16), source = ExpenseSource.SMS,
        status = if (direction == Direction.DEBIT) ExpenseStatus.DETECTED else ExpenseStatus.ADDED,
    )

    private val result = IngestResult(detected = listOf(payment(1), payment(2)), credits = listOf(payment(3, Direction.CREDIT)))

    @Test fun aPaymentTheMatcherTookNeverReachesTheSplitter() = runTest {
        val seen = ArrayList<IngestResult>()
        val matcher = NewPaymentsHook { setOf(1L, 3L) }
        val splitter = NewPaymentsHook { r -> seen += r; r.detected.map { it.id }.toSet() }

        val handled = runNewPaymentsHooks(listOf(matcher, splitter), result)

        assertEquals(listOf(2L), seen.single().detected.map { it.id })
        assertEquals(emptyList<Long>(), seen.single().credits.map { it.id })
        assertEquals(setOf(1L, 2L, 3L), handled)
    }

    @Test fun aFailingHookIsSkippedAndTheNextSeesEverything() = runTest {
        val seen = ArrayList<IngestResult>()
        val failing = NewPaymentsHook { error("boom") }
        val next = NewPaymentsHook { r -> seen += r; emptySet() }

        val handled = runNewPaymentsHooks(listOf(failing, next), result)

        assertEquals(result, seen.single())
        assertEquals(emptySet<Long>(), handled)
    }
}
