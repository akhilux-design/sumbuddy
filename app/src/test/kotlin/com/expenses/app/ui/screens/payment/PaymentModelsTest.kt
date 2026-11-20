package com.expenses.app.ui.screens.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentModelsTest {
    @Test fun wholeRupees() = assertEquals(60_000L, parseAmountMinor("600"))
    @Test fun withPaise() = assertEquals(120_050L, parseAmountMinor("1200.50"))
    @Test fun withSignAndCommas() = assertEquals(120_050L, parseAmountMinor("₹1,200.50"))
    @Test fun withRs() = assertEquals(45_000L, parseAmountMinor("Rs 450"))
    @Test fun pointOnly() = assertEquals(50L, parseAmountMinor(".5"))
    @Test fun emptyIsNull() = assertNull(parseAmountMinor(""))
    @Test fun zeroIsNull() = assertNull(parseAmountMinor("0"))
    @Test fun lettersAreNull() = assertNull(parseAmountMinor("12a"))
    @Test fun threeDecimalsAreNull() = assertNull(parseAmountMinor("1.234"))
    @Test fun tooLargeIsNull() = assertNull(parseAmountMinor("20000000"))

    @Test fun fieldTextWhole() = assertEquals("1200", amountFieldText(120_000))
    @Test fun fieldTextPaise() = assertEquals("0.05", amountFieldText(5))

    @Test fun amountInDebitMessage() =
        assertEquals(120_000L, amountInMessage("Rs 1200.00 debited from A/c XX4521 to VPA rk.menon@okaxis"))

    @Test fun amountWithInrAndCommas() = assertEquals(249_900L, amountInMessage("Txn of INR 2,499 on ICICI Card XX8890"))

    @Test fun noAmountInMessage() = assertNull(amountInMessage("Your A/c XX4521 was used. Call us if not you."))

    @Test fun creditedMessageIsMoneyIn() =
        assertTrue(isMoneyInMessage("Your a/c no. XXXXXXXX7788 is credited by Rs. on 10-11-26 by a/c linked to mobile"))

    @Test fun debitedMessageIsMoneyOut() =
        assertFalse(isMoneyInMessage("Rs debited from A/c XX4521 and credited to VPA rk.menon@okaxis"))
}
