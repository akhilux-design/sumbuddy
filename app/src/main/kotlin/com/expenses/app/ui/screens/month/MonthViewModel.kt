package com.expenses.app.ui.screens.month

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.groups.data.SplitLabel
import com.expenses.app.ui.screens.activity.groupByDay
import com.expenses.app.ui.screens.activity.toActivityItem
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Holds Month view: the shown month's spending, last month's, the totals per category and the payments by day. The
 * person moves to the month before or after; the month shown survives the process being recreated.
 *
 * @param savedState keeps the month shown.
 * @param firstMonth the month the screen was opened for.
 * @param expenses the expenses.
 * @param clock gives the current month and the time zone that decides days.
 * @param splitLabels what each split payment is part of (`GroupsRepository.observeSplitLabels()`), so split rows read
 * as they do on Activity ("Travel · your share of ₹558", the group's name).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonthViewModel(
    private val savedState: SavedStateHandle,
    firstMonth: YearMonth,
    expenses: ExpenseRepository,
    private val clock: Clock,
    splitLabels: Flow<Map<String, SplitLabel>> = flowOf(emptyMap()),
) : ViewModel() {
    private val shownMonth = savedState.getStateFlow(KEY_MONTH, firstMonth.toString()).map(YearMonth::parse)

    /** What the screen shows; null until the first read from the database. */
    val state: StateFlow<MonthUiState?> = shownMonth.flatMapLatest { month ->
        combine(
            expenses.observeMonthTotal(month),
            expenses.observeMonthTotal(month.minusMonths(1)),
            expenses.observeCategoryTotals(month),
            expenses.observeMonth(month),
            splitLabels,
        ) { spent, lastMonth, categories, added, labels ->
            MonthUiState(
                month = month,
                spentMinor = spent,
                lastMonthMinor = lastMonth,
                categories = categories.map { it.toMonthCategory(spent) },
                days = groupByDay(
                    items = added.map { it.toActivityItem(labels) to it.expense.occurredAt },
                    zone = clock.zone,
                    today = LocalDate.now(clock),
                ),
                canGoNext = month < YearMonth.now(clock),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** Handles the month buttons. Back and row taps are handled by the caller. */
    fun onEvent(event: MonthEvent) {
        val current = YearMonth.parse(savedState.get<String>(KEY_MONTH) ?: return)
        when (event) {
            MonthEvent.PreviousMonth -> savedState[KEY_MONTH] = current.minusMonths(1).toString()
            MonthEvent.NextMonth -> if (current < YearMonth.now(clock)) {
                savedState[KEY_MONTH] = current.plusMonths(1).toString()
            }
            MonthEvent.Back, is MonthEvent.ItemClicked -> Unit
        }
    }

    private fun CategoryTotal.toMonthCategory(spent: Long): MonthCategory = MonthCategory(
        category = category,
        count = count,
        totalMinor = totalMinor,
        percent = if (spent > 0) (totalMinor * PERCENT / spent.toDouble()).roundToInt() else 0,
    )

    private companion object {
        const val KEY_MONTH = "month_view_month"
        const val PERCENT = 100.0
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
