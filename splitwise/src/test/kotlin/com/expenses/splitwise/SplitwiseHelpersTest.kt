package com.expenses.splitwise

import com.expenses.splitwise.TestFiles.HEADER2
import com.expenses.splitwise.TestFiles.ok
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Group name (1.5), "which one is you" (1.6), categories (decision 16), item order and parts (3.2, 5.2). */
class SplitwiseHelpersTest {
    // ---- Group name from the file name ----

    @Test fun groupNameFromFileName() {
        assertEquals("Flat 302", GroupNameFromFile.of("flat-302-export.csv"))
        assertEquals("Flat 302", GroupNameFromFile.of("flat-302_2026-11-18_export (1).csv"))
        assertEquals("Goa Trip", GroupNameFromFile.of("Goa Trip_export.csv"))
        assertEquals("Goa Trip", GroupNameFromFile.of("Goa Trip_export(2).CSV"))
        assertEquals("Flatmates 2025", GroupNameFromFile.of("Flatmates 2025.csv"))
        assertEquals("Ooty Weekend", GroupNameFromFile.of("ooty_weekend_2025-06-01.csv"))
        // A name with a capital keeps its case as typed.
        assertEquals("McLeod trip", GroupNameFromFile.of("McLeod-trip-export.csv"))
    }

    @Test fun genericFileNamesGiveNoName() {
        for (name in listOf("export.csv", "Export (1).csv", "splitwise.csv", "Splitwise Export 2026-11-18.csv",
            "expenses.csv", "Download (1)", "file.csv", ".csv", "", null)) {
            assertEquals("for $name", "", GroupNameFromFile.of(name))
        }
    }

    @Test fun longGroupNamesAreCut() {
        val name = GroupNameFromFile.of("a-very-long-group-name-for-the-whole-extended-family-export.csv")
        assertEquals(40, name.length)
        assertEquals("A Very Long Group Name For The Whole Ext", name)
    }

    // ---- Which one is you ----

    private val people = listOf("Arjun Mehta", "Priya Nair", "Kiran Rao", "Priya  Shah")

    @Test fun exactNameIsPreselected() {
        assertEquals(1, SplitwisePeople.preselect(people, "  priya   NAIR "))
    }

    @Test fun uniqueFirstNameIsPreselected() {
        assertEquals(0, SplitwisePeople.preselect(people, "Arjun"))
        assertEquals(2, SplitwisePeople.preselect(people, "Kiran Kumar"))
    }

    @Test fun noOrSeveralMatchesSelectNobody() {
        assertNull(SplitwisePeople.preselect(people, "Priya"))
        assertNull(SplitwisePeople.preselect(people, "Meera Pillai"))
        assertNull(SplitwisePeople.preselect(people, " "))
        assertNull(SplitwisePeople.preselect(people, null))
        assertNull(SplitwisePeople.preselect(listOf("Asha", "Asha"), "Asha"))
    }

    // ---- Text ----

    @Test fun cleanMatchesTheAppsRulesAndKeepsWordsApart() {
        assertEquals("Dinner at Toit", SplitwiseText.clean(" Dinner\r\nat\tToit ", 80))
        assertEquals("ab", SplitwiseText.clean("a⁦b\u0000", 80))
        assertEquals("abc", SplitwiseText.clean("abc   def", 4))
        assertEquals("ab", SplitwiseText.clean("ab😀", 3))
        assertEquals("", SplitwiseText.clean(null, 10))
    }

    // ---- Categories ----

    @Test fun everySplitwiseCategoryInTheTable() {
        val table = mapOf(
            SwCategory.GROCERIES to listOf("Groceries"),
            SwCategory.FOOD to listOf("Dining out", "Liquor", "Food and drink", "Food and drink - Other"),
            SwCategory.TRAVEL to listOf(
                "Taxi", "Bus/train", "Plane", "Car", "Gas/fuel", "Parking", "Hotel", "Bicycle", "Transportation",
                "Transportation - Other",
            ),
            SwCategory.RENT_AND_BILLS to listOf(
                "Rent", "Mortgage", "Electricity", "Heat/gas", "Water", "TV/Phone/Internet", "Trash", "Cleaning",
                "Maintenance", "Services", "Insurance", "Utilities", "Utilities - Other", "Home - Other",
            ),
            SwCategory.SHOPPING to listOf("Clothing", "Electronics", "Furniture", "Gifts", "Household supplies"),
            SwCategory.FUN to listOf("Games", "Movies", "Music", "Sports", "Entertainment", "Entertainment - Other"),
            SwCategory.OTHER to listOf("Childcare", "Education", "Medical expenses", "Taxes", "Pets", "Life", "Life - Other"),
        )
        for ((category, names) in table) {
            for (name in names) assertEquals(name, category, SplitwiseCategories.map(name))
        }
    }

    @Test fun categoriesIgnoreCaseAndSpacing() {
        assertEquals(SwCategory.RENT_AND_BILLS, SplitwiseCategories.map("  tv / phone / internet "))
        assertEquals(SwCategory.FOOD, SplitwiseCategories.map("DINING  OUT"))
        assertEquals(SwCategory.RENT_AND_BILLS, SplitwiseCategories.map("home-other"))
    }

    @Test fun generalPaymentBlankAndUnknownAreGuessedByTheApp() {
        for (raw in listOf("General", "Payment", "", " ", null, "Other", "Home", "Lifestyle", "Splurge")) {
            assertNull("for $raw", SplitwiseCategories.map(raw))
        }
    }

    // ---- Items and parts ----

    @Test fun itemsAreInDateThenRowThenPartOrderWithDatesClamped() {
        val today = LocalDate.of(2026, 11, 19)
        val file = ok(
            "Date,Description,Category,Cost,Currency,Asha,Ravi,Neel",
            "2027-01-02,Planned later,General,30.00,INR,30.00,-15.00,-15.00", // row 2, clamped to today
            "2026-12-01,Planned,General,20.00,INR,10.00,10.00,-20.00", // row 3, two parts, clamped to today
            "1999-05-01,Old,General,10.00,INR,10.00,-10.00,0.00", // row 4, clamped to 2000-01-01
            "2025-03-01,Paid back,Payment,5.00,INR,0.00,5.00,-5.00", // row 5
        )
        val items = ImportParts.items(file, today)
        assertEquals(file.itemCount, items.size)
        assertEquals(listOf(4, 5, 2, 3, 3), items.map { it.row })
        assertEquals(
            listOf(ImportParts.FIRST_DATE, LocalDate.of(2025, 3, 1), today, today, today),
            items.map { it.occurredOn },
        )
        val parts = items.filterIsInstance<SwItem.Expense>().filter { it.row == 3 }
        assertEquals(listOf(0, 1), parts.map { it.partIndex })
        assertEquals(listOf(0, 1), parts.map { it.part.payer })
        assertTrue(items[1] is SwItem.Payment)
        // The file keeps the dates as written.
        assertEquals(LocalDate.of(2027, 1, 2), file.expenses[0].date)
    }

    @Test fun clampDate() {
        val today = LocalDate.of(2026, 11, 19)
        assertEquals(today, ImportParts.clampDate(LocalDate.of(2026, 11, 20), today))
        assertEquals(today, ImportParts.clampDate(today, today))
        assertEquals(LocalDate.of(2000, 1, 1), ImportParts.clampDate(LocalDate.of(1999, 12, 31), today))
        assertEquals(LocalDate.of(2000, 1, 1), ImportParts.clampDate(LocalDate.of(2000, 1, 1), today))
    }

    @Test fun chunksHoldAtMostFiftyItems() {
        val parts = ImportParts.chunk((1..120).toList()) { 10 }
        assertEquals(listOf(50, 50, 20), parts.map { it.size })
        assertEquals((1..120).toList(), parts.flatten())
    }

    @Test fun chunksStayUnderThirtyThousandBytes() {
        // 12 + 29 × 1,000 + 28 commas = 29,040 bytes; a 30th item would make 30,041.
        val parts = ImportParts.chunk((1..60).toList()) { 1_000 }
        assertEquals(listOf(29, 29, 2), parts.map { it.size })
        // Exactly 30,000: 12 + 2 × 14,993 + 1 comma.
        assertEquals(listOf(2, 1), ImportParts.chunk(listOf(1, 2, 3)) { 14_993 }.map { it.size })
        // An item bigger than a body on its own still goes, alone.
        assertEquals(listOf(1, 1, 1), ImportParts.chunk(listOf(1, 2, 3)) { if (it == 2) 40_000 else 10 }.map { it.size })
        assertEquals(emptyList<List<Int>>(), ImportParts.chunk(emptyList<Int>()) { 1 })
    }

    @Test fun sampleGroupInParts() {
        val file = ok(SplitwiseCsv.parse(TestFiles.resource("group-sample.csv")))
        val items = ImportParts.items(file, LocalDate.of(2026, 11, 19))
        assertEquals(listOf(2, 3, 4, 4, 5, 5, 6, 8), items.map { it.row })
        assertEquals(listOf(items), ImportParts.chunk(items) { 300 })
    }

    @Test fun fileTypesHoldNoSurprises() {
        val file = ok(HEADER2, "2025-01-05,Tea,General,20.00,INR,10.00,-10.00")
        assertEquals(SwItem.Expense(file.expenses[0], 0, LocalDate.of(2025, 1, 5)), ImportParts.items(file, LocalDate.of(2026, 1, 1)).single())
    }
}
