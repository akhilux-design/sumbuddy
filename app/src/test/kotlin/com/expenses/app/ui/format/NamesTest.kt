package com.expenses.app.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

/** First names in sentences and rows, whole names where two people share one (design 24, 32, 40, 03/00 to 05). */
class NamesTest {
    @Test fun aSentenceUsesTheFirstName() {
        assertEquals("Rahul", shortName("Rahul Menon", listOf("Arjun Mehta", "Rahul Menon", "Priya Nair")))
        assertEquals("Priya", shortName("  Priya   Nair ", emptyList()))
        assertEquals("Kiran", shortName("Kiran", listOf("Kiran", "Arjun")))
    }

    @Test fun twoPeopleWithOneFirstNameKeepTheirWholeNames() {
        val group = listOf("Arjun Mehta", "Rahul Kumar", "Rahul Verma")
        assertEquals("Rahul Kumar", shortName("Rahul Kumar", group))
        assertEquals("Rahul Verma", shortName("Rahul Verma", group))
        assertEquals("Arjun", shortName("Arjun Mehta", group))
        // Case does not make two first names different.
        assertEquals("Rahul Kumar", shortName("Rahul Kumar", listOf("rahul s")))
    }

    @Test fun thePersonThemselvesInTheListIsNoClash() {
        // Join group's member names are first names only, the inviter's among them.
        assertEquals("Rahul", shortName("Rahul Menon", listOf("Rahul", "Priya")))
        assertEquals("Rahul Menon", shortName("Rahul Menon", listOf("Rahul", "Rahul", "Priya")))
    }

    @Test fun aFormerMemberStaysWhole() {
        assertEquals("Former member", shortName("Former member", listOf("Arjun Mehta")))
    }

    @Test fun aBlankNameStaysAsItIs() {
        assertEquals("", shortName("  ", listOf("Arjun")))
    }
}
