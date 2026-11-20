package com.expenses.app.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which payee names are UPI IDs ([isUpiIdText]) and where they may break ([upiIdLineBreaks]). */
class UpiIdTextTest {
    @Test fun aUpiIdIsOneWordWithAnAtInside() {
        assertTrue(isUpiIdText("rahul.kumar@okaxis"))
        assertTrue(isUpiIdText("9000000002@ybl"))
        assertTrue(isUpiIdText(" uberindia.rides@hdfcbank "))
    }

    @Test fun namesAndBrokenIdsAreNot() {
        assertFalse(isUpiIdText("Swiggy"))
        assertFalse(isUpiIdText("Ramesh Kirana Store"))
        assertFalse(isUpiIdText("Pay to rahul@okaxis"))
        assertFalse(isUpiIdText("@okaxis"))
        assertFalse(isUpiIdText("rahul@"))
        assertFalse(isUpiIdText(""))
    }

    @Test fun aUpiIdMayBreakOnlyAfterItsAt() {
        // A word joiner (no break) between the characters, a zero width space (a break) after the "@".
        assertEquals("r⁠.⁠k⁠@​o⁠k", upiIdLineBreaks(" r.k@ok "))
        val shown = upiIdLineBreaks("rahul.kumar@okaxis")
        assertEquals("rahul.kumar@okaxis", shown.replace("⁠", "").replace("​", ""))
        assertEquals(listOf("rahul.kumar@", "okaxis"), shown.replace("⁠", "").split("​"))
    }

    @Test fun aSentenceIsReadWithoutTheMarks() {
        val sentence = "${upiIdLineBreaks("rahul.kumar@okaxis")} is always Food"
        assertEquals("rahul.kumar@okaxis is always Food", withoutUpiIdLineBreaks(sentence))
        // Text without marks is not changed.
        assertEquals("Swiggy is always Food", withoutUpiIdLineBreaks("Swiggy is always Food"))
    }

    @Test fun otherTextIsLeftAsItIs() {
        assertEquals("Ramesh Kirana Store", upiIdLineBreaks("Ramesh Kirana Store"))
        assertEquals("Pay to rahul@okaxis", upiIdLineBreaks("Pay to rahul@okaxis"))
    }
}
