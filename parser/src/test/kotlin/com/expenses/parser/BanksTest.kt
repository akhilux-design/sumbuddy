package com.expenses.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading the bank from a sender header ([Banks.headerCode], [Banks.fromSender]). */
class BanksTest {
    @Test fun theOperatorPrefixAndSuffixAreDropped() {
        assertEquals("HDFCBK", Banks.headerCode("VM-HDFCBK"))
        assertEquals("HDFCBK", Banks.headerCode("VM-HDFCBK-S"))
        assertEquals("HDFCBK", Banks.headerCode(" vm-hdfcbk "))
        assertEquals("HDFCBK", Banks.headerCode("HDFCBK"))
    }

    @Test fun aKnownHeaderWithoutTheHyphenIsKnown() {
        // As some phones give it. The emulator keeps the sender as "adb emu sms send" was given it, hyphen or not.
        assertEquals("HDFCBK", Banks.headerCode("VMHDFCBK"))
        assertEquals("HDFCBK", Banks.headerCode("VMHDFCBK-S"))
        assertEquals(Banks.HDFC, Banks.fromSender("VMHDFCBK"))
        assertEquals(Banks.ICICI, Banks.fromSender("VMICICIB"))
        assertEquals(Banks.KOTAK, Banks.fromSender("VMKOTAKB"))
        assertEquals(Banks.SBI, Banks.fromSender("ADSBIUPI"))
    }

    @Test fun anUnknownHeaderWithoutTheHyphenStaysAsItIs() {
        assertEquals("VMGPAYIN", Banks.headerCode("VMGPAYIN"))
        assertNull(Banks.fromSender("VMGPAYIN"))
        // Only two letters before a six-letter header are an operator prefix.
        assertNull(Banks.fromSender("VMXHDFCBK"))
        assertNull(Banks.fromSender("9HDFCBK"))
        assertNull(Banks.fromSender("+919876543210"))
    }

    @Test fun aUpiDebitFromAHeaderWithoutTheHyphenGetsItsBank() {
        // The text names no bank, so only the sender can: the debit belongs on HDFC Bank ••4521, not a bank-less account.
        val sms = SmsMessage(
            sender = "VMHDFCBK",
            body = "Rs 275.00 debited from a/c **4521 on 21-11-26 to VPA tea.stall@ybl (UPI Ref No 632500000055). " +
                "Not you? Call on 18002586161 to report this",
            receivedAtMillis = 1_763_740_000_000L,
        )
        val result = SmsParser().parse(sms)
        check(result is ParseResult.Payment) { "not a payment: $result" }
        assertEquals(Banks.HDFC, result.payment.bank)
        assertEquals("4521", result.payment.instrumentLast4)
    }
}
