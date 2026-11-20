package com.expenses.app.ui.screens.month

import com.expenses.app.data.category.Category
import java.time.YearMonth

/** Sample Month view states with the numbers of design 02-everyday/18-month-view. */
object MonthSamples {
    /** November 2026 as the design draws it: five categories, the payments list left out. */
    val default: MonthUiState = MonthUiState(
        month = YearMonth.of(2026, 11),
        spentMinor = 1_824_000,
        lastMonthMinor = 2_190_000,
        categories = listOf(
            MonthCategory(Category.RENT_AND_BILLS, count = 2, totalMinor = 800_000, percent = 44),
            MonthCategory(Category.FOOD, count = 14, totalMinor = 426_000, percent = 23),
            MonthCategory(Category.GROCERIES, count = 9, totalMinor = 312_000, percent = 17),
            MonthCategory(Category.TRAVEL, count = 11, totalMinor = 186_000, percent = 10),
            MonthCategory(Category.OTHER, count = 6, totalMinor = 100_000, percent = 6),
        ),
        days = emptyList(),
        canGoNext = false,
    )

    /** A month with nothing added. */
    val empty: MonthUiState = MonthUiState(
        month = YearMonth.of(2026, 9),
        spentMinor = 0,
        lastMonthMinor = 0,
        categories = emptyList(),
        days = emptyList(),
        canGoNext = true,
    )
}
