package com.expenses.splitwise

import com.expenses.splitwise.TestFiles.HEADER2
import com.expenses.splitwise.TestFiles.HEADER3
import com.expenses.splitwise.TestFiles.assertRefused
import com.expenses.splitwise.TestFiles.ok
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/splitwise-import.md 1.4: currencies, the total row, and the limits of one import. */
class SplitwiseLimitsTest {
    // ---- Currencies ----

    @Test fun otherCurrenciesAreCountedAndLeftOut() {
        val file = ok(SplitwiseCsv.parse(TestFiles.resource("two-currencies.csv")))
        assertEquals(mapOf("USD" to 2, "EUR" to 1), file.leftOutByCurrency)
        assertEquals(3, file.leftOutCount)
        assertEquals(1, file.expenseCount)
        assertEquals(listOf(600_000L, -300_000L, -300_000L), file.balancesPaise)
    }

    @Test fun onlyOtherCurrenciesIsNothingInRupees() {
        assertRefused(
            Refusal.NOTHING_IN_RUPEES,
            HEADER2,
            "2025-01-05,Tea,General,20.00,USD,10.00,-10.00",
            "2025-01-05,Total balance, , ,USD,10.00,-10.00",
        )
    }

    @Test fun emptyExportIsNothingInRupees() {
        assertRefused(Refusal.NOTHING_IN_RUPEES, HEADER2)
        assertRefused(Refusal.NOTHING_IN_RUPEES, HEADER2, "2025-01-05,Total balance, , ,INR,0.00,0.00")
        assertRefused(Refusal.NOTHING_IN_RUPEES, HEADER2, "2025-01-05,Total balance, , ,INR,5.00,-5.00")
    }

    @Test fun onlyAllZeroRupeeRowsIsNothingInRupees() {
        assertRefused(Refusal.NOTHING_IN_RUPEES, HEADER2, "2025-01-05,Mine,General,100.00,INR,0.00,0.00")
    }

    // ---- The total row ----

    @Test fun rupeeTotalThatDisagreesIsRefused() {
        assertRefused(
            Refusal.DOES_NOT_ADD_UP,
            HEADER2,
            "2025-01-05,Tea,General,20.00,INR,10.00,-10.00",
            "2025-01-05,Total balance, , ,INR,10.01,-10.01",
        )
    }

    @Test fun rupeeTotalThatAgreesIsAccepted() {
        ok(
            HEADER2,
            "2025-01-05,Tea,General,20.00,INR,10.00,-10.00",
            "2025-01-06,Lunch,General,300.00,INR,-150.00,150.00",
            "2025-01-06,Total balance,,,INR,-140.00,140.00",
        )
    }

    @Test fun totalCheckIsSkippedWithSeveralCurrencies() {
        ok(
            HEADER2,
            "2025-01-05,Tea,General,20.00,INR,10.00,-10.00",
            "2025-01-05,Taxi,Taxi,20.00,USD,10.00,-10.00",
            "2025-01-05,Total balance, , ,INR,99.00,-99.00",
        )
    }

    @Test fun totalCheckIsSkippedWithTwoTotalRows() {
        ok(
            HEADER2,
            "2025-01-05,Tea,General,20.00,INR,10.00,-10.00",
            "2025-01-05,Total balance, , ,INR,99.00,-99.00",
            "2025-01-05,Total balance, , ,INR,10.00,-10.00",
        )
    }

    @Test fun totalRowWithACostIsAnOrdinaryRow() {
        val file = ok(HEADER2, "2025-01-05,Total balance,General,20.00,INR,10.00,-10.00")
        assertEquals("Total balance", file.expenses.single().description)
    }

    // ---- People ----

    @Test fun onePersonIsRefused() {
        assertRefused(
            Refusal.ONE_PERSON,
            "Date,Description,Category,Cost,Currency,Asha",
            "2025-01-05,Tea,General,20.00,INR,0.00",
        )
    }

    @Test fun fiftyPeopleAreAccepted() {
        val file = ok(header(50), row(50))
        assertEquals(50, file.peopleCount)
    }

    @Test fun fiftyOnePeopleAreRefused() {
        assertRefused(Refusal.TOO_MANY_PEOPLE, header(51), row(51))
    }

    // ---- Rows and items ----

    @Test fun fiveThousandRowsAreAccepted() {
        val file = ok(HEADER2, *Array(5_000) { "2025-01-05,Tea $it,General,1.00,INR,1.00,-1.00" })
        assertEquals(5_000, file.itemCount)
        assertEquals(listOf(500_000L, -500_000L), file.balancesPaise)
    }

    @Test fun fiveThousandAndOneRowsAreRefused() {
        assertRefused(Refusal.TOO_MANY_ROWS, HEADER2, *Array(5_001) { "2025-01-05,Tea $it,General,1.00,INR,1.00,-1.00" })
    }

    @Test fun rowsThatSplitIntoMoreThanFiveThousandItemsAreRefused() {
        // 2,500 rows with two payers are 5,000 items; 2,501 are 5,002.
        val twoPayers = "2025-01-05,Income,General,2.00,INR,1.00,1.00,-2.00"
        assertEquals(5_000, ok(HEADER3, *Array(2_500) { twoPayers }).itemCount)
        assertRefused(Refusal.TOO_MANY_ROWS, HEADER3, *Array(2_501) { twoPayers })
        assertRefused(Refusal.TOO_MANY_ROWS, HEADER3, *Array(5_000) { twoPayers })
    }

    @Test fun otherCurrencyAndAllZeroRowsDoNotCount() {
        val rows = Array(5_000) { "2025-01-05,Tea,General,1.00,INR,1.00,-1.00" } +
            Array(100) { "2025-01-05,Mine,General,1.00,INR,0.00,0.00" } +
            Array(100) { "2025-01-05,Taxi,Taxi,1.00,USD,1.00,-1.00" }
        assertEquals(5_000, ok(HEADER2, *rows).itemCount)
    }

    @Test fun refusalAfterTheRowLimitStillWins() {
        val rows = Array(5_001) { "2025-01-05,Tea,General,1.00,INR,1.00,-1.00" } +
            "2025-01-05,Bad,General,1.00,INR,1.00,-2.00"
        assertRefused(Refusal.DOES_NOT_ADD_UP, HEADER2, *rows)
    }

    // ---- File size ----

    @Test fun truncatedFileIsTooLarge() {
        assertRefused(Refusal.TOO_LARGE_FILE, SplitwiseCsv.parse(TestFiles.resource("friendship.csv"), truncated = true))
    }

    @Test fun moreThanFiveMegabytesIsTooLarge() {
        assertRefused(Refusal.TOO_LARGE_FILE, SplitwiseCsv.parse(ByteArray(SplitwiseCsv.MAX_BYTES + 1) { 'a'.code.toByte() }))
    }

    @Test fun exactlyFiveMegabytesIsRead() {
        val head = "$HEADER2\n2025-01-05,\""
        val tail = "\",General,20.00,INR,10.00,-10.00\n"
        val padding = "x".repeat(SplitwiseCsv.MAX_BYTES - head.length - tail.length)
        val bytes = (head + padding + tail).toByteArray()
        assertEquals(SplitwiseCsv.MAX_BYTES, bytes.size)
        assertEquals("x".repeat(80), ok(SplitwiseCsv.parse(bytes)).expenses.single().description)
    }

    @Test fun aLineOfMillionsOfCommasIsNotAnExport() {
        assertRefused(Refusal.NOT_SPLITWISE, SplitwiseCsv.parse((HEADER2 + "\n" + ",".repeat(1_000_000) + "x").toByteArray()))
    }

    // ---- Amounts ----

    @Test fun oneCroreIsTheLargestAmount() {
        val file = ok(HEADER2, "2025-01-05,Flat,Rent,10000000.00,INR,\"1,00,00,000.00\",-10000000")
        assertEquals(1_000_000_000L, file.expenses.single().parts.single().amountPaise)
        assertRefused(Refusal.TOO_LARGE_AMOUNT, HEADER2, "2025-01-05,Flat,Rent,10000000.01,INR,10000000.01,-10000000.01")
    }

    @Test fun costOverOneCroreWithOnePayerIsTooLarge() {
        // The payer's value is under ₹1 crore, but they paid the whole Cost.
        assertRefused(Refusal.TOO_LARGE_AMOUNT, HEADER3, "2025-01-05,Flat,Rent,15000000.00,INR,10000000.00,-5000000.00,-5000000.00")
    }

    @Test fun paymentOverOneCroreIsTooLarge() {
        assertRefused(Refusal.TOO_LARGE_AMOUNT, HEADER2, "2025-01-05,Asha paid Ravi,Payment,10000000.01,INR,10000000.01,-10000000.01")
    }

    @Test fun severalPayersUnderOneCroreEachAreAccepted() {
        val file = ok(HEADER3, "2025-01-05,Land,General,15000000.00,INR,7500000.00,7500000.00,-15000000.00")
        assertEquals(listOf(750_000_000L, 750_000_000L), file.expenses.single().parts.map { it.amountPaise })
    }

    @Test fun absurdDigitsAreTooLarge() {
        assertRefused(Refusal.TOO_LARGE_AMOUNT, HEADER2, "2025-01-05,Odd,General,1.00,INR,12345678901234567890.00,-1.00")
    }

    private fun header(people: Int): String =
        "Date,Description,Category,Cost,Currency," + (1..people).joinToString(",") { "Person $it" }

    /** Person 1 paid 1 rupee per other person, for the others. */
    private fun row(people: Int): String =
        "2025-01-05,Dinner,Dining out,${people - 1}.00,INR,${people - 1}.00," + List(people - 1) { "-1.00" }.joinToString(",")
}
