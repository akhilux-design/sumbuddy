package com.expenses.splitwise

import com.expenses.splitwise.TestFiles.HEADER2
import com.expenses.splitwise.TestFiles.HEADER3
import com.expenses.splitwise.TestFiles.assertRefused
import com.expenses.splitwise.TestFiles.ok
import com.expenses.splitwise.TestFiles.parse
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/splitwise-import.md 1.2 and 1.3: layout, cells, money, dates and how rows become expenses and payments. */
class SplitwiseCsvTest {
    // ---- The sample group (a spliit #342-style file in rupees) ----

    private val sample = ok(SplitwiseCsv.parse(TestFiles.resource("group-sample.csv")))

    @Test fun samplePeopleAndCounts() {
        assertEquals(listOf("Alice Rao", "Bob Shah", "Carol Das", "Dave Iyer"), sample.people)
        assertEquals(5, sample.expenseCount)
        assertEquals(4, sample.peopleCount)
        assertEquals(1, sample.payments.size)
        assertEquals(8, sample.itemCount)
        assertEquals(1, sample.noEffectRows)
        assertEquals(emptyMap<String, Int>(), sample.leftOutByCurrency)
        assertEquals(0, sample.leftOutCount)
        assertEquals(listOf(45_227L, 70_047L, -62_774L, -52_500L), sample.balancesPaise)
    }

    @Test fun altruistPaymentHasThePayerOutOfTheSplit() {
        val expense = sample.expenses[0]
        assertEquals(2, expense.row)
        assertEquals(LocalDate.of(2025, 5, 1), expense.date)
        assertEquals("Altruist payment", expense.description)
        assertEquals("General", expense.category)
        assertEquals(listOf(SwPart(0, 30_000, mapOf(1 to 10_000L, 2 to 10_000L, 3 to 10_000L))), expense.parts)
    }

    @Test fun onePayerInTheSplitKeepsTheirShare() {
        // Bob paid 1,000 and is +700: his own share is 300.
        assertEquals(
            listOf(SwPart(1, 100_000, mapOf(0 to 20_000L, 1 to 30_000L, 2 to 30_000L, 3 to 20_000L))),
            sample.expenses[1].parts,
        )
        // Uneven paise: Bob paid 8.20 and is +5.47.
        assertEquals(listOf(SwPart(1, 820, mapOf(0 to 273L, 1 to 273L, 2 to 274L))), sample.expenses[4].parts)
        assertEquals("Chai", sample.expenses[4].description)
    }

    @Test fun incomeAndTwoPayersBecomeOnePartPerPayer() {
        // Income: Bob +10 and Carol +10, Alice -20. The Cost (30) is not kept.
        assertEquals(
            listOf(SwPart(1, 1_000, mapOf(0 to 1_000L)), SwPart(2, 1_000, mapOf(0 to 1_000L))),
            sample.expenses[2].parts,
        )
        // Two payers: Alice +375, Bob +75; Carol and Dave -225 each, filled in column order.
        assertEquals(
            listOf(SwPart(0, 37_500, mapOf(2 to 22_500L, 3 to 15_000L)), SwPart(1, 7_500, mapOf(3 to 7_500L))),
            sample.expenses[3].parts,
        )
    }

    @Test fun paymentRowIsASettlementFromThePositivePerson() {
        assertEquals(listOf(SwPayment(6, LocalDate.of(2025, 5, 5), from = 1, to = 2, amountPaise = 1_000)), sample.payments)
    }

    @Test fun selfPaymentAndTotalRowAreNotImported() {
        assertTrue(sample.expenses.none { it.description == "Self-payment" || it.description == "Total balance" })
        assertEquals(listOf(2, 3, 4, 5, 8), sample.expenses.map { it.row })
    }

    @Test fun friendshipExportIsAGroupOfTwo() {
        val file = ok(SplitwiseCsv.parse(TestFiles.resource("friendship.csv")))
        assertEquals(listOf("Meera Pillai", "Kiran Rao"), file.people)
        // Meera paid 600 and is +300: half each.
        assertEquals(listOf(SwPart(0, 60_000, mapOf(0 to 30_000L, 1 to 30_000L))), file.expenses.single().parts)
        assertEquals(SwPayment(3, LocalDate.of(2025, 3, 9), from = 1, to = 0, amountPaise = 30_000), file.payments.single())
        assertEquals(listOf(0L, 0L), file.balancesPaise)
    }

    @Test fun excelSavedFileWithGroupedNumbersAndMonthFirstDates() {
        val file = ok(SplitwiseCsv.parse(TestFiles.resource("excel-saved.csv")))
        assertEquals(LocalDate.of(2025, 3, 31), file.expenses[0].date)
        assertEquals(LocalDate.of(2025, 4, 2), file.expenses[1].date)
        assertEquals(
            listOf(SwPart(0, 3_000_000, mapOf(0 to 1_000_000L, 1 to 1_000_000L, 2 to 1_000_000L))),
            file.expenses[0].parts,
        )
        assertEquals(listOf(SwPart(1, 123_450, mapOf(0 to 41_150L, 1 to 41_150L, 2 to 41_150L))), file.expenses[1].parts)
        assertEquals(listOf(1_958_850L, -917_700L, -1_041_150L), file.balancesPaise)
    }

    // ---- Encoding, line ends, blank records ----

    @Test fun bomCrlfBlankLinesSpaceCellsAndTrailingComma() {
        val text = "﻿" + listOf(
            "Date,Description,Category,Cost,Currency,Asha,Ravi,",
            "",
            "2025-01-05,Groceries,Groceries,500.00,INR,250.00,-250.00,",
            " , , , , , , ",
            "",
            "2025-01-05,Total balance, , ,INR,250.00,-250.00",
            "",
        ).joinToString("\r\n")
        val file = ok(SplitwiseCsv.parse(text.toByteArray(Charsets.UTF_8)))
        assertEquals(listOf("Asha", "Ravi"), file.people)
        assertEquals(listOf(SwPart(0, 50_000, mapOf(0 to 25_000L, 1 to 25_000L))), file.expenses.single().parts)
        assertEquals(3, file.expenses.single().row)
    }

    @Test fun oldMacLineEnds() {
        val text = listOf(HEADER2, "2025-01-05,Tea,General,20.00,INR,10.00,-10.00").joinToString("\r")
        assertEquals(1, ok(SplitwiseCsv.parse(text.toByteArray())).expenseCount)
    }

    @Test fun notUtf8IsNotAnExport() {
        val latin1 = (HEADER2 + "\n2025-01-05,Café,General,20.00,INR,10.00,-10.00").toByteArray(Charsets.ISO_8859_1)
        assertRefused(Refusal.NOT_SPLITWISE, SplitwiseCsv.parse(latin1))
    }

    // ---- Quoted cells ----

    @Test fun quotedDescriptionsWithCommasQuotesAndLineBreaks() {
        val file = ok(
            HEADER2,
            "2025-01-05,\"Dinner, drinks and \"\"dessert\"\"\",Dining out,900.00,INR,450.00,-450.00",
            "2025-01-06,\"Line one",
            "line two\",General,100.00,INR,-50.00,50.00",
            "2025-01-07,  \"  padded  \"  ,General,100.00,INR,-50.00,50.00",
        )
        assertEquals("Dinner, drinks and \"dessert\"", file.expenses[0].description)
        assertEquals("Line one line two", file.expenses[1].description)
        assertEquals("padded", file.expenses[2].description)
        // The quoted line break makes record 3 span two lines; the next record is 4.
        assertEquals(listOf(2, 3, 4), file.expenses.map { it.row })
    }

    @Test fun unterminatedQuoteIsNotAnExport() {
        assertRefused(Refusal.NOT_SPLITWISE, HEADER2, "2025-01-05,\"Dinner,General,900.00,INR,450.00,-450.00")
    }

    @Test fun textAfterAClosingQuoteIsNotAnExport() {
        assertRefused(Refusal.NOT_SPLITWISE, HEADER2, "2025-01-05,\"Dinner\"x,General,900.00,INR,450.00,-450.00")
    }

    @Test fun descriptionsAreCleanedAndCut() {
        val long = "x".repeat(100)
        val file = ok(
            HEADER2,
            "2025-01-05,$long,General,20.00,INR,10.00,-10.00",
            "2025-01-05,,Groceries,20.00,INR,10.00,-10.00",
            "2025-01-05,\"a‮b\u0007c\",General,20.00,INR,10.00,-10.00",
        )
        assertEquals("x".repeat(80), file.expenses[0].description)
        assertEquals("", file.expenses[1].description)
        assertEquals("Groceries", file.expenses[1].category)
        assertEquals("abc", file.expenses[2].description)
    }

    // ---- Header ----

    @Test fun headerIsCaseAndSpaceInsensitive() {
        val file = ok(" DATE , description ,Category,COST,currency, Asha , Ravi ", "2025-01-05,Tea,General,20.00,INR,10.00,-10.00")
        assertEquals(listOf("Asha", "Ravi"), file.people)
    }

    @Test fun translatedHeaderIsRefused() {
        assertRefused(
            Refusal.NOT_SPLITWISE,
            "Datum,Beschreibung,Kategorie,Kosten,Währung,Asha,Ravi",
            "2025-01-05,Essen,Allgemein,20.00,INR,10.00,-10.00",
        )
    }

    @Test fun otherFilesAreRefused() {
        assertRefused(Refusal.NOT_SPLITWISE, SplitwiseCsv.parse("%PDF-1.7\n%âã\n1 0 obj".toByteArray()))
        assertRefused(Refusal.NOT_SPLITWISE, SplitwiseCsv.parse(ByteArray(4_096).also { kotlin.random.Random(7).nextBytes(it) }))
        assertRefused(Refusal.NOT_SPLITWISE, SplitwiseCsv.parse(ByteArray(0)))
        assertRefused(Refusal.NOT_SPLITWISE, SplitwiseCsv.parse("\n\n , ,\n".toByteArray()))
        assertRefused(Refusal.NOT_SPLITWISE, "Name,Amount", "Asha,10")
    }

    @Test fun blankNameBetweenTwoNamesIsRefused() {
        assertRefused(Refusal.NOT_SPLITWISE, "Date,Description,Category,Cost,Currency,Asha,,Ravi")
        assertRefused(Refusal.NOT_SPLITWISE, "Date,Description,Category,Cost,Currency,Asha,\"\u0007\"")
    }

    @Test fun headerWithoutPeopleIsNotAnExport() {
        assertRefused(Refusal.NOT_SPLITWISE, "Date,Description,Category,Cost,Currency", "2025-01-05,Tea,General,20.00,INR")
    }

    @Test fun duplicateNamesAreAllowedAndToldApartByColumn() {
        val file = ok("Date,Description,Category,Cost,Currency,Asha,Asha", "2025-01-05,Tea,General,20.00,INR,-10.00,10.00")
        assertEquals(listOf("Asha", "Asha"), file.people)
        assertEquals(1, file.expenses.single().parts.single().payer)
    }

    @Test fun namesAreCleaned() {
        val long = "N".repeat(70)
        val file = ok("Date,Description,Category,Cost,Currency,\"Asha\nMenon\",$long", "2025-01-05,Tea,General,20.00,INR,-10.00,10.00")
        assertEquals(listOf("Asha Menon", "N".repeat(60)), file.people)
    }

    // ---- Row shape ----

    @Test fun shortRowIsRefused() {
        assertRefused(Refusal.NOT_SPLITWISE, HEADER3, "2025-01-05,Tea,General,20.00,INR,10.00,-10.00")
    }

    @Test fun extraCellsMustBeBlank() {
        ok(HEADER2, "2025-01-05,Tea,General,20.00,INR,10.00,-10.00,, ,")
        assertRefused(Refusal.NOT_SPLITWISE, HEADER2, "2025-01-05,Tea,General,20.00,INR,10.00,-10.00,5.00")
    }

    // ---- Money ----

    @Test fun moneyCells() {
        val file = ok(
            HEADER2,
            "2025-01-05,Grouped,General,\"1,234.50\",INR,\"1,234.50\",\"-1,234.50\"",
            "2025-01-05,Indian,General,\"1,23,456.00\",INR,\"-1,23,456\",\"1,23,456\"",
            "2025-01-05,One decimal,General,98.5,INR,-98.5,98.50",
            "2025-01-05,Signs,General,+10,INR,+10.00,-10",
            "2025-01-05,Minus zero,General,10.00,INR,-0.00,0.00",
        )
        assertEquals(123_450L, file.expenses[0].parts.single().amountPaise)
        assertEquals(12_345_600L, file.expenses[1].parts.single().amountPaise)
        assertEquals(1, file.expenses[1].parts.single().payer)
        assertEquals(9_850L, file.expenses[2].parts.single().amountPaise)
        assertEquals(1_000L, file.expenses[3].parts.single().amountPaise)
        assertEquals(1, file.noEffectRows)
    }

    @Test fun threeDecimalsInRupeesAreRefused() {
        assertRefused(Refusal.NOT_SPLITWISE, HEADER3, "2025-01-05,Pizza,General,100.00,INR,66.667,-33.333,-33.334")
    }

    @Test fun unreadableRupeeCellsAreRefused() {
        for (cell in listOf("\"12,34\"", "₹100.00", "(100.00)", "1e2", "", "abc", ".50", "10.")) {
            assertRefused(Refusal.NOT_SPLITWISE, HEADER2, "2025-01-05,Tea,General,100.00,INR,$cell,-100.00")
        }
        assertRefused(Refusal.NOT_SPLITWISE, HEADER2, "2025-01-05,Tea,General,,INR,100.00,-100.00")
    }

    @Test fun otherCurrencyCellsAreNotParsed() {
        val file = ok(
            HEADER2,
            "2025-01-05,Tea,General,20.00,INR,10.00,-10.00",
            "2025-01-06,Odd,General,1.234,USD,abc,1.0001",
        )
        assertEquals(mapOf("USD" to 1), file.leftOutByCurrency)
    }

    // ---- Dates ----

    @Test fun isoDatesWithAndWithoutTime() {
        val file = ok(
            HEADER2,
            "2025-05-17,A,General,20.00,INR,10.00,-10.00",
            "2025-05-18T10:20:00Z,B,General,20.00,INR,10.00,-10.00",
            "2025-05-19 10:20,C,General,20.00,INR,10.00,-10.00",
        )
        assertEquals(
            listOf(LocalDate.of(2025, 5, 17), LocalDate.of(2025, 5, 18), LocalDate.of(2025, 5, 19)),
            file.expenses.map { it.date },
        )
    }

    @Test fun dayFirstWhenAFirstNumberIsAbove12() {
        val file = ok(
            HEADER2,
            "05/06/2025,A,General,20.00,INR,10.00,-10.00",
            "25/12/2025,B,General,20.00,INR,10.00,-10.00",
        )
        assertEquals(listOf(LocalDate.of(2025, 6, 5), LocalDate.of(2025, 12, 25)), file.expenses.map { it.date })
    }

    @Test fun monthFirstWhenASecondNumberIsAbove12() {
        val file = ok(
            HEADER2,
            "05/06/25,A,General,20.00,INR,10.00,-10.00",
            "12/25/25 0:00,B,General,20.00,INR,10.00,-10.00",
        )
        assertEquals(listOf(LocalDate.of(2025, 5, 6), LocalDate.of(2025, 12, 25)), file.expenses.map { it.date })
    }

    @Test fun dayFirstWhenUnclear() {
        val file = ok(HEADER2, "03/04/2025,A,General,20.00,INR,10.00,-10.00")
        assertEquals(LocalDate.of(2025, 4, 3), file.expenses.single().date)
    }

    @Test fun otherCurrencyRowsCountAsEvidenceForTheOrder() {
        val file = ok(
            HEADER2,
            "6/5/2025,A,General,20.00,INR,10.00,-10.00",
            "6/13/2025,B,General,20.00,USD,10.00,-10.00",
        )
        // The USD row's 13 makes the file month-first, so 6/5 is 5 June, not 6 May.
        assertEquals(LocalDate.of(2025, 6, 5), file.expenses.single().date)
    }

    @Test fun dashedAndDottedDayFirstDates() {
        val file = ok(
            HEADER2,
            "17-05-2025,A,General,20.00,INR,10.00,-10.00",
            "18.05.2025,B,General,20.00,INR,10.00,-10.00",
            "2025/05/19,C,General,20.00,INR,10.00,-10.00",
        )
        assertEquals(
            listOf(LocalDate.of(2025, 5, 17), LocalDate.of(2025, 5, 18), LocalDate.of(2025, 5, 19)),
            file.expenses.map { it.date },
        )
    }

    @Test fun badDatesAreRefused() {
        for (date in listOf("2025-02-30", "May 17 2025", "", "17/05", "2025-13-01", "13/13/2025")) {
            assertRefused(Refusal.NOT_SPLITWISE, HEADER2, "$date,A,General,20.00,INR,10.00,-10.00")
        }
        // Mixed orders: one date says day-first, another cannot be read that way.
        assertRefused(
            Refusal.NOT_SPLITWISE,
            HEADER2,
            "25/12/2025,A,General,20.00,INR,10.00,-10.00",
            "12/25/2025,B,General,20.00,INR,10.00,-10.00",
        )
    }

    @Test fun datesStayAsInTheFile() {
        val file = ok(
            HEADER2,
            "1999-12-31,Old,General,20.00,INR,10.00,-10.00",
            "2099-01-01,Planned,General,20.00,INR,10.00,-10.00",
        )
        assertEquals(listOf(LocalDate.of(1999, 12, 31), LocalDate.of(2099, 1, 1)), file.expenses.map { it.date })
    }

    // ---- Classification (1.3) ----

    @Test fun rowThatDoesNotSumIsRefused() {
        assertRefused(Refusal.DOES_NOT_ADD_UP, HEADER3, "2025-01-05,Pizza,General,100.00,INR,66.67,-33.33,-33.33")
    }

    @Test fun costOfZeroOrLessWithValuesIsRefused() {
        assertRefused(Refusal.DOES_NOT_ADD_UP, HEADER2, "2025-01-05,Tea,General,0.00,INR,10.00,-10.00")
        assertRefused(Refusal.DOES_NOT_ADD_UP, HEADER2, "2025-01-05,Tea,General,-10.00,INR,10.00,-10.00")
    }

    @Test fun allZeroRowsAreLeftOutWhateverTheCost() {
        val file = ok(
            HEADER2,
            "2025-01-05,Mine,General,100.00,INR,0.00,0.00",
            "2025-01-05,Nothing,General,0.00,INR,0.00,-0.00",
            "2025-01-06,Tea,General,20.00,INR,10.00,-10.00",
        )
        assertEquals(2, file.noEffectRows)
        assertEquals(1, file.expenseCount)
    }

    @Test fun paymentThatDoesNotFitBecomesAnExpense() {
        // Category Payment, but the Cost is not the amount moved: rule 5 keeps the balances right.
        val file = ok(HEADER2, "2025-01-05,Odd,Payment,20.00,INR,10.00,-10.00")
        assertTrue(file.payments.isEmpty())
        assertEquals(listOf(SwPart(0, 2_000, mapOf(0 to 1_000L, 1 to 1_000L))), file.expenses.single().parts)
        // Three people moved: rule 6.
        val three = ok(HEADER3, "2025-01-05,Odd,payment,20.00,INR,10.00,10.00,-20.00")
        assertEquals(2, three.expenses.single().parts.size)
    }

    @Test fun paymentCategoryInAnyCase() {
        val file = ok(HEADER2, "2025-01-05,Asha paid Ravi,PAYMENT,500.00,INR,500.00,-500.00")
        assertEquals(SwPayment(2, LocalDate.of(2025, 1, 5), 0, 1, 50_000), file.payments.single())
    }

    @Test fun samePaymentShapeInAnotherCategoryIsAnExpenseForTheOther() {
        val file = ok(HEADER2, "2025-01-05,Asha paid for Ravi,General,500.00,INR,500.00,-500.00")
        assertEquals(listOf(SwPart(0, 50_000, mapOf(1 to 50_000L))), file.expenses.single().parts)
    }

    @Test fun payerValueAboveTheCostTakesTheValue() {
        // Not something Splitwise writes, but the nets must still come out exactly.
        val file = ok(HEADER2, "2025-01-05,Odd,General,50.00,INR,80.00,-80.00")
        assertEquals(listOf(SwPart(0, 8_000, mapOf(1 to 8_000L))), file.expenses.single().parts)
    }

    @Test fun lowerCaseCurrencyIsRupees() {
        assertEquals(1, ok(HEADER2, "2025-01-05,Tea,General,20.00,inr,10.00,-10.00").expenseCount)
    }

    @Test fun blankCurrencyIsNotAnExport() {
        assertRefused(Refusal.NOT_SPLITWISE, HEADER2, "2025-01-05,Tea,General,20.00, ,10.00,-10.00")
    }

    @Test fun fileHashIsHexSha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SplitwiseCsv.fileHash("abc".toByteArray()),
        )
    }
}
