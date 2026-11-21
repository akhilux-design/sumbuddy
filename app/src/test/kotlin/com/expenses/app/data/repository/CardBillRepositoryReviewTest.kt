package com.expenses.app.data.repository

import com.expenses.app.cardbill.CardBillMath
import com.expenses.app.data.model.CardBill
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Review of the card bills commit (84fd310): D7, "upsert keeps the first SMS's receivedAt and smsId ... Amounts and
 * account come from the newer SMS", when the SMS are not stored in time order. That happens with "add older
 * payments" (ScanWorker with `until`), which reads SMS from before the tracking start after the newer ones were stored.
 */
@RunWith(RobolectricTestRunner::class)
class CardBillRepositoryReviewTest {
    private val test = TestDatabase()

    @After fun close() = test.close()

    private fun sms(total: Long, smsId: Long, day: Int) = CardBill(
        accountId = null, bank = "HDFC Bank", cardLast4 = "2217", totalDueMinor = total, minimumDueMinor = 121_500,
        dueDate = LocalDate.of(2026, 11, 21), receivedAt = nov(day), smsId = smsId,
    )

    /**
     * The 18 Nov reminder is stored first, then the 1 Nov statement is read by "add older payments". The bill must date
     * from 1 Nov; it keeps 18 Nov, so the statement date becomes 6 Nov (due − 15) instead of 1 Nov: card payments of
     * 2 to 6 Nov (the next bill's) count towards this one, those of 2 to 6 Oct drop out, and a payment of the bill made
     * 2 to 6 Nov is before the statement, so the bill never shows paid and its reminder still posts.
     */
    @Test fun anOlderStatementReadAfterTheReminderDatesTheBillFromTheStatement() = runTest {
        val id = test.cardBills.upsert(sms(total = 2_000_000, smsId = 11, day = 18))
        test.cardBills.upsert(sms(total = 2_430_000, smsId = 10, day = 1))
        val stored = test.cardBills.get(id)!!
        assertEquals(nov(1), stored.receivedAt)
        assertEquals(10L, stored.smsId)
        assertEquals(LocalDate.of(2026, 11, 1), CardBillMath.statementDate(stored, TestZone))
    }

    /** Same order: the amounts are the newer SMS's (the 18 Nov reminder), not the older statement's. */
    @Test fun anOlderStatementReadAfterTheReminderKeepsTheNewerAmount() = runTest {
        val id = test.cardBills.upsert(sms(total = 2_000_000, smsId = 11, day = 18))
        test.cardBills.upsert(sms(total = 2_430_000, smsId = 10, day = 1))
        assertEquals(2_000_000L, test.cardBills.get(id)!!.totalDueMinor)
    }
}
