package com.expenses.app.ui.screens.settle

import org.junit.Assert.assertEquals
import org.junit.Test

/** The `upi://pay` link of Pay by UPI, built as the friends' web page builds it (docs/server.md 7). */
class UpiPayLinkTest {
    @Test fun aGroupPaymentAsTheDesignShowsIt() {
        assertEquals("upi://pay?pa=rahul@okhdfc&pn=Rahul&am=1200.00&cu=INR&tn=Flat%20302", UpiPayLink.of("rahul@okhdfc", "Rahul", 120_000, "Flat 302"))
    }

    @Test fun aDirectSplitHasNoNote() {
        assertEquals("upi://pay?pa=rahul@okhdfc&pn=Rahul&am=150.50&cu=INR", UpiPayLink.of(" rahul@okhdfc ", "Rahul", 15_050, null))
    }

    @Test fun valuesAreEncodedAsEncodeUriComponentDoesExceptTheAt() {
        assertEquals(
            "upi://pay?pa=r.k-1@ok%26x&pn=Ravi%20%26%20Co%20%E2%82%B9&am=0.05&cu=INR&tn=Goa%20trip%20(Dec)%3F",
            UpiPayLink.of("r.k-1@ok&x", "Ravi & Co ₹", 5, "Goa trip (Dec)?"),
        )
    }

    @Test fun rupeesHaveTwoDecimals() {
        assertEquals("1200.00", UpiPayLink.rupees(120_000))
        assertEquals("0.05", UpiPayLink.rupees(5))
        assertEquals("10.10", UpiPayLink.rupees(1_010))
    }
}
