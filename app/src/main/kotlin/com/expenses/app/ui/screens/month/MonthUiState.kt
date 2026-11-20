package com.expenses.app.ui.screens.month

import androidx.compose.runtime.Immutable
import com.expenses.app.data.category.Category
import com.expenses.app.ui.screens.activity.ActivityDay
import com.expenses.app.ui.screens.activity.ActivityItemId
import java.time.YearMonth

/**
 * Everything Month view shows (design 02-everyday/18-month-view): what was spent in one month next to the month
 * before, the spending per category, and the month's payments by day.
 *
 * @param month the month shown; the title reads "November 2026".
 * @param spentMinor what the person spent in [month], in paise (the month rule: added debits, their share).
 * @param lastMonthMinor what they spent in the month before, in paise.
 * @param categories the spending per category, largest first. Empty when nothing was spent.
 * @param days the month's added payments, debits and credits, newest day first.
 * @param canGoNext false for the current month: there is no later month to show yet.
 */
@Immutable
data class MonthUiState(
    val month: YearMonth,
    val spentMinor: Long,
    val lastMonthMinor: Long,
    val categories: List<MonthCategory>,
    val days: List<ActivityDay>,
    val canGoNext: Boolean,
)

/**
 * One category row: "Food · 14 expenses · ₹4,260 · 23%".
 *
 * @param category the category.
 * @param count how many counted expenses it has.
 * @param totalMinor what they add up to, in paise.
 * @param percent its share of the month's spending, rounded to a whole percent.
 */
@Immutable
data class MonthCategory(
    val category: Category,
    val count: Int,
    val totalMinor: Long,
    val percent: Int,
)

/** Everything the person can tap on Month view. */
sealed interface MonthEvent {
    /** The back button. */
    data object Back : MonthEvent

    /** "Previous month": show the month before. */
    data object PreviousMonth : MonthEvent

    /** "Next month": show the month after. Only offered before the current month. */
    data object NextMonth : MonthEvent

    /**
     * A payment row.
     *
     * @param id what the row stands for.
     */
    data class ItemClicked(val id: ActivityItemId) : MonthEvent
}
