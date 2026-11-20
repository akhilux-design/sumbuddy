package com.expenses.app.ui.screens.family

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.groups.data.FamilyMonth
import com.expenses.app.groups.data.FamilyRepository
import com.expenses.app.ui.screens.activity.activityDateOf
import com.expenses.app.ui.screens.month.MonthCategory
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What Month view (Family) shows: the month, or that the family is no longer on this phone. */
sealed interface FamilyMonthScreenState {
    /** Being read. */
    data object Loading : FamilyMonthScreenState

    /** The family left this phone: the screen leaves. */
    data object Gone : FamilyMonthScreenState

    /** The month. */
    data class Shown(val month: FamilyMonthUiState) : FamilyMonthScreenState
}

/**
 * Month view (Family) (design 06-family/27; docs/family.md 1.5): every family expense of one month, at its full amount
 * and by its India date, next to the month before; the person moves to the month before or after. The month shown
 * survives the process being recreated.
 *
 * @param savedState keeps the month shown.
 * @param firstMonth the month the screen was opened for.
 * @param family the family on this phone.
 * @param clock gives the current month and today (India time in the app).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FamilyMonthViewModel(
    private val savedState: SavedStateHandle,
    firstMonth: YearMonth,
    family: FamilyRepository,
    private val clock: Clock,
) : ViewModel() {
    private val shownMonth = savedState.getStateFlow(KEY_MONTH, firstMonth.toString()).map(YearMonth::parse)

    /** What the screen shows. */
    val state: StateFlow<FamilyMonthScreenState> = shownMonth.flatMapLatest { month ->
        family.observeMonth(month).map { data -> if (data == null) FamilyMonthScreenState.Gone else FamilyMonthScreenState.Shown(uiState(data)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), FamilyMonthScreenState.Loading)

    /** Handles the month buttons. Back and row taps are the caller's. */
    fun onEvent(event: FamilyMonthEvent) {
        val current = YearMonth.parse(savedState.get<String>(KEY_MONTH) ?: return)
        when (event) {
            FamilyMonthEvent.PreviousMonth -> savedState[KEY_MONTH] = current.minusMonths(1).toString()
            FamilyMonthEvent.NextMonth -> if (current < YearMonth.now(clock)) savedState[KEY_MONTH] = current.plusMonths(1).toString()
            FamilyMonthEvent.Back, is FamilyMonthEvent.RowTapped -> Unit
        }
    }

    private fun uiState(data: FamilyMonth): FamilyMonthUiState {
        val today = LocalDate.now(clock)
        return FamilyMonthUiState(
            month = data.month,
            familyName = data.familyName,
            totalMinor = data.totalMinor,
            lastMonthMinor = data.lastMonthTotalMinor,
            people = data.people,
            categories = data.categories.map { it.toMonthCategory(data.totalMinor) },
            days = data.expenses
                .groupBy { it.occurredOn }
                .toSortedMap(compareByDescending { it })
                .map { (date, rows) -> FamilyDay(activityDateOf(date, today), rows) },
            canGoNext = data.month < YearMonth.now(clock),
        )
    }

    private fun CategoryTotal.toMonthCategory(total: Long): MonthCategory = MonthCategory(
        category = category,
        count = count,
        totalMinor = totalMinor,
        percent = if (total > 0) (totalMinor * PERCENT / total.toDouble()).roundToInt() else 0,
    )

    private companion object {
        const val KEY_MONTH = "family_month_view_month"
        const val PERCENT = 100.0
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
