package com.expenses.app.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test fun wholeRupees() = assertEquals("₹450", formatRupees(45_000))
    @Test fun thousands() = assertEquals("₹18,240", formatRupees(1_824_000))
    @Test fun lakhs() = assertEquals("₹1,23,456.78", formatRupees(12_345_678))
    @Test fun crores() = assertEquals("₹1,00,00,000", formatRupees(1_000_000_000))
    @Test fun zero() = assertEquals("₹0", formatRupees(0))
    @Test fun paiseOnly() = assertEquals("₹0.05", formatRupees(5))
    @Test fun negative() = assertEquals("-₹150", formatRupees(-15_000))
    @Test fun forcedPaise() = assertEquals("₹450.00", formatRupees(45_000, alwaysShowPaise = true))
    @Test fun countWithThousands() = assertEquals("1,902", formatCount(1_902))
    @Test fun countWithLakhs() = assertEquals("1,23,456", formatCount(123_456))
    @Test fun smallCount() = assertEquals("7", formatCount(7))
}
