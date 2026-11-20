package com.expenses.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicatesTest {
    private val parser = SmsParser()
    private val minute = 60_000L

    private fun read(sender: String, body: String, atMinute: Long): ReadPayment {
        val sms = SmsMessage(sender, body, 1_763_300_000_000L + atMinute * minute)
        val result = parser.parse(sms)
        check(result is ParseResult.Payment) { "not a payment: $body -> $result" }
        return ReadPayment(sms, result.payment)
    }

    private val appCopy = read("VM-GPAYIN", "Paid Rs.450.00 to Swiggy via UPI. UPI Ref 632011112222.", 0)
    private val bankCopy = read(
        "VM-HDFCBK",
        "Rs.450.00 debited from A/c XX4521 on 16-11-26 to VPA swiggy@icici (UPI Ref No 632011112222). -HDFC Bank",
        1,
    )

    @Test fun appCopyIsDroppedForTheBankOne() =
        assertEquals(listOf(bankCopy), Duplicates.keepBankCopies(listOf(appCopy, bankCopy)))

    @Test fun orderDoesNotMatter() =
        assertEquals(listOf(bankCopy), Duplicates.keepBankCopies(listOf(bankCopy, appCopy)))

    @Test fun twoBankPaymentsOfTheSameAmountAreBothKept() {
        val first = read("VM-HDFCBK", "Rs.20.00 debited from A/c XX4521 on 16-11-26 to VPA chai@ybl (UPI Ref No 632000000001). -HDFC Bank", 0)
        val second = read("VM-HDFCBK", "Rs.20.00 debited from A/c XX4521 on 16-11-26 to VPA chai@ybl (UPI Ref No 632000000002). -HDFC Bank", 3)
        assertEquals(listOf(first, second), Duplicates.keepBankCopies(listOf(first, second)))
    }

    @Test fun theSameBankReferenceTwiceIsOnePayment() {
        val first = read("VM-HDFCBK", "Rs.20.00 debited from A/c XX4521 on 16-11-26 to VPA chai@ybl (UPI Ref No 632000000001). -HDFC Bank", 0)
        val again = read("JD-HDFCBK-S", "Rs.20.00 debited from A/c XX4521 on 16-11-26 to VPA chai@ybl (UPI Ref No 632000000001). -HDFC Bank", 1)
        assertEquals(listOf(first), Duplicates.keepBankCopies(listOf(first, again)))
    }

    @Test fun differentAmountsAreNotCopies() {
        val other = read("VM-GPAYIN", "Paid Rs.451.00 to Swiggy via UPI. UPI Ref 632099998888.", 0)
        assertEquals(listOf(other, bankCopy), Duplicates.keepBankCopies(listOf(other, bankCopy)))
    }

    @Test fun farApartInTimeAreNotCopies() {
        val later = read("VM-GPAYIN", "Paid Rs.450.00 to Swiggy via UPI.", 120)
        assertEquals(listOf(bankCopy, later), Duplicates.keepBankCopies(listOf(bankCopy, later)))
    }

    @Test fun appCopyWithNoBankSmsIsKept() =
        assertEquals(listOf(appCopy), Duplicates.keepBankCopies(listOf(appCopy)))
}
