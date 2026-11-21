package com.expenses.app.cardbill

import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.CardBill
import com.expenses.parser.ParseResult
import com.expenses.parser.SmsMessage
import com.expenses.parser.SmsParser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review of the card bills commit (84fd310): which bill of a card is current (docs/card-bills.md 2.2, D14).
 */
class CardBillMathReviewTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun at(month: Int, day: Int, hour: Int = 12): Instant =
        LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant()

    private fun bill(id: Long, due: LocalDate?, received: Instant) =
        CardBill(id, 1, "HDFC Bank", "2217", 2_430_000, 121_500, due, received, smsId = id)

    private val card = Account(
        id = 1, bank = "HDFC Bank", type = AccountType.CREDIT_CARD, last4 = "2217", displayName = "HDFC Millennia",
        firstSeenAt = at(10, 1),
    )

    /**
     * Statement on 1 Nov, due 21 Nov. On 10 Nov the bank sends a reminder that prints no date; the parser reads it as a
     * card bill without a due date, and `upsert` stores every undated bill as a new one. It is the same bill, still due
     * on 21 Nov, but `isCurrent` ranks the undated one by "statement + 15" = 25 Nov, after 21 Nov, so it takes over:
     * the dated bill's 18 Nov reminder is cancelled by the next sync and never posts (the undated bill gets none), and
     * Home shows "₹24,300 to pay" instead of "due in N days".
     */
    @Test fun aDateLessReminderInsideTheCycleDoesNotReplaceTheDatedBill() {
        // The premise: such an SMS really becomes a bill without a due date on the same card.
        val parsed = SmsParser(zone).parse(
            SmsMessage(
                sender = "VM-HDFCBK",
                body = "Rs.24,300 is due on your HDFC Bank Credit Card XX2217. Please pay before the due date.",
                receivedAtMillis = at(11, 10).toEpochMilli(),
            ),
        )
        assertTrue("parsed as $parsed", parsed is ParseResult.CardBill)
        parsed as ParseResult.CardBill
        assertNull(parsed.bill.dueDateMillis)
        assertEquals("2217", parsed.bill.cardLast4)

        val dated = bill(1, LocalDate.of(2026, 11, 21), at(11, 1))
        val undated = bill(2, null, at(11, 10))
        val all = listOf(dated, undated)

        assertTrue("the dated bill due 21 Nov must stay current", CardBillMath.isCurrent(dated, all, zone))
        val live = CardBillMath.isLive(BillFacts(dated, card, null), all, smsMode = true, zone = zone)
        assertNotNull(
            "the 21 Nov bill must still be reminded on 18 Nov at 09:00",
            CardBillMath.reminderAt(dated, live, null, remindersOn = true, now = at(11, 10, 13), zone = zone),
        )
    }
}
