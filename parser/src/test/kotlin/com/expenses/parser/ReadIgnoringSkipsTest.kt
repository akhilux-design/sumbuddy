package com.expenses.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [SmsParser.readIgnoringSkips], which the app's clean-up uses to tell an untouched stored row. Messages are synthetic. */
class ReadIgnoringSkipsTest {
    private val parser = SmsParser()

    private fun read(sender: String, body: String) = parser.readIgnoringSkips(SmsMessage(sender, body, NOV_16))

    @Test
    fun readsWhatAnOlderVersionStoredFromASkippedMessage() {
        val sms = SmsMessage(STORE, LOYALTY, NOV_16)
        assertEquals(ParseResult.Skipped(SkipReason.REWARD_POINTS), parser.parse(sms))

        val payment = parser.readIgnoringSkips(sms)!!
        assertEquals(Direction.CREDIT, payment.direction)
        assertEquals(20_000L, payment.amountMinor)
        assertEquals("INR", payment.currency)
    }

    @Test
    fun matchesParseForAPayment() {
        val body = "Rs 500.00 credited to A/c XX4521 on 16-11-26 by NEFT from ACME PAYROLL. -HDFC Bank"
        val parsed = parser.parse(SmsMessage("VM-HDFCBK", body, NOV_16)) as ParseResult.Payment
        assertEquals(parsed.payment, read("VM-HDFCBK", body))
    }

    @Test
    fun nullWithoutADebitOrCreditOrAnAmount() {
        assertNull(read(STORE, "Your ShopClub Loyalty account XXXXXX7731 has 1,200 points."))
        assertNull(read(STORE, "Your ShopClub account has been credited. Check the app for details."))
        assertNull(read(STORE, ""))
    }

    private companion object {
        const val NOV_16 = 1_763_280_000_000L
        const val STORE = "VM-SHPCLB-S"
        const val LOYALTY = "An update has been made to your ShopClub Loyalty account XXXXXX7731. Rs.200 loyalty points have " +
            "been credited. Valid until 30th November. *T&C Apply."
    }
}
