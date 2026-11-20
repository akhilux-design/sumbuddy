package com.expenses.app.ui.screens.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.ExpenseWithAccount
import com.expenses.app.data.model.ReviewItem
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.data.repository.ReviewRepository
import com.expenses.app.groups.data.SplitLabel
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.time.minuteTicks
import com.expenses.app.ui.time.months
import com.expenses.parser.Direction
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * Holds the Activity tab: this month's spending and every added payment (debits and credits) grouped by day, with
 * the open Needs review messages among them under All.
 *
 * "From splits" is the part of the month's spending that comes from split payments: the person's counted share of
 * the month's rows linked to a group expense. A row whose group expense is in [splitLabels] reads as a split ("Food ·
 * your share of ₹450", the group's name under the amount), and the Splits filter lists exactly those rows. Ignored
 * payments are listed only under the Ignored filter.
 *
 * @param expenses the expenses.
 * @param reviews the Needs review messages.
 * @param clock gives today and the time zone that decides days and the month.
 * @param splitLabels what each split payment is part of (`GroupsRepository.observeSplitLabels()`); empty when the
 * person splits nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(
    expenses: ExpenseRepository,
    reviews: ReviewRepository,
    private val clock: Clock,
    splitLabels: Flow<Map<String, SplitLabel>> = flowOf(emptyMap()),
) : ViewModel() {
    private val filter = MutableStateFlow(ActivityFilter.All)

    /** "Now", every minute, so the month and the day labels stay right while the tab stays open. */
    private val now = minuteTicks(clock).shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 1)

    /** The current month with its spending and the part of it from splits, read again when the month changes. */
    private val month: Flow<MonthNumbers> = now.months(clock).flatMapLatest { month ->
        combine(expenses.observeMonthTotal(month), expenses.observeMonth(month)) { spent, added ->
            MonthNumbers(month, spent, added.sumOf { it.expense.splitShare() })
        }
    }

    private val header = combine(now, month) { instant, numbers -> instant to numbers }

    private val lists = combine(
        expenses.observeLatest(LIST_LIMIT),
        expenses.observeIgnored(LIST_LIMIT),
        reviews.observeOpen(),
        splitLabels,
    ) { added, ignored, openReviews, labels -> Lists(added, ignored, openReviews, labels) }

    /** What the tab shows; null until the first read from the database. */
    val state: StateFlow<ActivityUiState?> = combine(header, lists, filter) { (instant, numbers), shown, selected ->
        ActivityUiState(
            month = numbers.month,
            spentPaise = numbers.spentPaise,
            fromSplitsPaise = numbers.fromSplitsPaise,
            content = content(shown, selected, instant.atZone(clock.zone).toLocalDate()),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** Handles the events that change what the tab shows. Navigation events are handled by the caller. */
    fun onEvent(event: ActivityEvent) {
        if (event is ActivityEvent.FilterSelected) filter.value = event.filter
    }

    private fun content(lists: Lists, selected: ActivityFilter, today: LocalDate): ActivityContent {
        if (lists.added.isEmpty() && lists.ignored.isEmpty() && lists.openReviews.isEmpty()) return ActivityContent.Empty
        val listed = if (selected == ActivityFilter.Ignored) lists.ignored else lists.added
        val expenseRows = listed
            .map { it to it.toActivityItem(lists.splitLabels) }
            .filter { (row, item) -> matches(row, item, selected) }
            .map { (row, item) -> item to row.expense.occurredAt }
        val reviewRows = if (selected == ActivityFilter.All) {
            lists.openReviews.map { it.toActivityItem() to it.receivedAt }
        } else {
            emptyList()
        }
        return ActivityContent.Days(
            selectedFilter = selected,
            days = groupByDay(expenseRows + reviewRows, clock.zone, today),
        )
    }

    private fun matches(row: ExpenseWithAccount, item: ActivityItem, filter: ActivityFilter): Boolean = when (filter) {
        ActivityFilter.All, ActivityFilter.Ignored -> true
        ActivityFilter.Me -> row.expense.scope == ExpenseScope.ME
        ActivityFilter.Family -> row.expense.scope == ExpenseScope.FAMILY
        ActivityFilter.Splits -> item.detail is ActivityItemDetail.Split
    }

    /** What a row of the month adds to "From splits": the counted share of a split rupee debit, else nothing. */
    private fun Expense.splitShare(): Long =
        if (groupExpenseId != null && status == ExpenseStatus.ADDED && direction == Direction.DEBIT && currency == Expense.INR) {
            myShareMinor
        } else {
            0
        }

    private fun ReviewItem.toActivityItem(): ActivityItem = ActivityItem(
        id = ActivityItemId.Review(id),
        title = sender,
        icon = AppIcon.Help,
        amountPaise = null,
        detail = ActivityItemDetail.NeedsReview,
    )

    /** The month, what it counts as spent and the part of that from split payments, in paise. */
    private data class MonthNumbers(val month: YearMonth, val spentPaise: Long, val fromSplitsPaise: Long)

    /** Everything the list is made from. */
    private data class Lists(
        val added: List<ExpenseWithAccount>,
        val ignored: List<ExpenseWithAccount>,
        val openReviews: List<ReviewItem>,
        val splitLabels: Map<String, SplitLabel>,
    )

    private companion object {
        /** How many of the newest added payments the list reads. Older ones are in Month view. */
        const val LIST_LIMIT = 500

        /** How long the reads stay active after the screen stops watching, so a rotation does not restart them. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
