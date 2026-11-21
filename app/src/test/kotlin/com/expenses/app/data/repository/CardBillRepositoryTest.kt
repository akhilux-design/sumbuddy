package com.expenses.app.data.repository

import com.expenses.app.data.model.CardBill
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Storing card bills: a later SMS of the same bill keeps when the bill first came (docs/card-bills.md D7). */
@RunWith(RobolectricTestRunner::class)
class CardBillRepositoryTest {
    private val test = TestDatabase()

    @After fun close() = test.close()

    private fun statement(total: Long = 2_430_000, smsId: Long = 10, day: Int = 1) = CardBill(
        accountId = null, bank = "HDFC Bank", cardLast4 = "2217", totalDueMinor = total, minimumDueMinor = 121_500,
        dueDate = LocalDate.of(2026, 11, 21), receivedAt = nov(day), smsId = smsId,
    )

    @Test fun aReminderAfterTheStatementKeepsTheFirstSmsAndTakesTheNewAmount() = runTest {
        val id = test.cardBills.upsert(statement())
        test.cardBills.setRemindedAt(id, nov(18, 9))
        val again = test.cardBills.upsert(statement(total = 2_000_000, smsId = 11, day = 15))
        assertEquals(id, again)
        val stored = test.cardBills.get(id)!!
        assertEquals(2_000_000L, stored.totalDueMinor)
        assertEquals(nov(1), stored.receivedAt)
        assertEquals(10L, stored.smsId)
        assertEquals(nov(18, 9), stored.remindedAt)
        assertEquals(1, test.cardBills.all().size)
    }

    @Test fun aNewBillIsNotReminded() = runTest {
        val id = test.cardBills.upsert(statement())
        assertNull(test.cardBills.get(id)!!.remindedAt)
    }
}
