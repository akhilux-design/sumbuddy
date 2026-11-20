package com.expenses.app.ui.screens.split

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.category.Category
import com.expenses.app.data.category.CategoryGuesser
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseWithAccount
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.model.payee
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.groups.data.GroupDetail
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.SplitLabel
import com.expenses.app.groups.data.SplitRule
import com.expenses.app.ui.screens.payment.PaymentAccount
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Same split? (design 02-everyday/14-recurring-split-offer) shows. */
sealed interface SameSplitUiState {
    /** The payment is being read. */
    data object Loading : SameSplitUiState

    /** The payment is not here any more; the caller leaves. */
    data object Gone : SameSplitUiState

    /** The choice is stored; the caller leaves. */
    data object Saved : SameSplitUiState

    /**
     * The offer.
     *
     * @param payee who was paid ("Rent").
     * @param groupName the group the rule splits with.
     * @param category the payment's category, for the row's icon.
     * @param amountMinor the payment in paise.
     * @param date the day it was paid.
     * @param today today's date.
     * @param account the account it came from, or null.
     * @param paidWith how it was paid, shown when there is no account.
     * @param everyMonth true for "Yes, every month", false for "Ask me each time".
     * @param saving true while Save stores the choice.
     * @param splitLastMonth true when another payment to this payee, made in the calendar month before this one, is
     * split with the group: the line under the title then reads "You split Rent with Flat 302 last month too.", as
     * drawn. Otherwise it reads "You've split Rent with Flat 302 before." (not designed).
     */
    @Immutable
    data class Shown(
        val payee: String,
        val groupName: String,
        val category: Category,
        val amountMinor: Long,
        val date: LocalDate,
        val today: LocalDate,
        val account: PaymentAccount?,
        val paidWith: PaidWith,
        val everyMonth: Boolean,
        val saving: Boolean,
        val splitLastMonth: Boolean,
    ) : SameSplitUiState
}

/** Everything the person can do on Same split?. */
sealed interface SameSplitEvent {
    /** "Yes, every month" ([everyMonth] true) or "Ask me each time" was tapped. */
    data class Chosen(val everyMonth: Boolean) : SameSplitEvent

    /** "Save". */
    data object Save : SameSplitEvent

    /** Back: leave without changing the rule. Handled by the caller. */
    data object Back : SameSplitEvent
}

/**
 * Same split? for the payment [expenseId]: shown after Split it when the payment was split the way its kept rule says
 * and the rule does not split by itself yet, and from Expense detail's split row when it does (where "Ask me each
 * time" turns it off). "Yes, every month" is chosen at the start, as drawn. Save stores the choice with
 * `GroupsRepository.setSplitRuleAuto`, on this phone only. The line under the title says "last month too" only when a
 * payment to the same payee was split with the group in the calendar month before this payment's own (counted from
 * the payment's date, not from today).
 *
 * @param expenseId the payment.
 * @param groups the rule and the group's name.
 * @param expenses the payment.
 * @param clock today's date and the time zone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SameSplitViewModel(
    private val expenseId: Long,
    private val groups: GroupsRepository,
    private val expenses: ExpenseRepository,
    private val clock: Clock,
) : ViewModel() {
    private data class Local(val everyMonth: Boolean = true, val saving: Boolean = false, val saved: Boolean = false)

    private val local = MutableStateFlow(Local())

    private val row: Flow<ExpenseWithAccount?> = expenses.observe(expenseId)

    private val label: Flow<SplitLabel?> = row.map { it?.expense?.groupExpenseId }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else groups.observeSplitLabel(id) }

    private val rule: Flow<SplitRule?> = row.map { it?.expense?.counterparty }.distinctUntilChanged()
        .flatMapLatest { payee -> if (payee == null) flowOf(null) else groups.splitRuleFor(payee) }

    /** The group the offer names: the one this payment is split with, else the rule's. */
    private val group: Flow<GroupDetail?> = combine(label, rule) { label, rule -> label?.groupId ?: rule?.groupId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else groups.observeGroup(id) }

    /**
     * The person's payments counted in the calendar month before the payment's own (not before today's: a payment can
     * be reviewed a month or more after it was made).
     */
    private val lastMonth: Flow<List<ExpenseWithAccount>> = row
        .map { it?.expense?.occurredAt?.let { at -> YearMonth.from(at.atZone(clock.zone)).minusMonths(1) } }
        .distinctUntilChanged()
        .flatMapLatest { month -> if (month == null) flowOf(emptyList()) else expenses.observeMonth(month) }

    /** The screen's state. */
    val state: StateFlow<SameSplitUiState> = combine(row, label, group, lastMonth, local) { row, label, group, lastMonth, local ->
        val expense = row?.expense
        when {
            local.saved -> SameSplitUiState.Saved
            expense == null -> SameSplitUiState.Gone
            else -> SameSplitUiState.Shown(
                payee = expense.payee.orEmpty(),
                groupName = label?.groupName ?: group?.name.orEmpty(),
                category = expense.category,
                amountMinor = expense.amountMinor,
                date = expense.occurredAt.atZone(clock.zone).toLocalDate(),
                today = LocalDate.now(clock),
                account = PaymentAccount.from(row.account),
                paidWith = expense.paidWith,
                everyMonth = local.everyMonth,
                saving = local.saving,
                splitLastMonth = splitLastMonth(expense, group, lastMonth),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SameSplitUiState.Loading)

    /** Handles what the person did. Back is handled by the caller and ignored here. */
    fun onEvent(event: SameSplitEvent) {
        when (event) {
            is SameSplitEvent.Chosen -> local.update { it.copy(everyMonth = event.everyMonth) }
            SameSplitEvent.Save -> save()
            SameSplitEvent.Back -> Unit
        }
    }

    /**
     * True when another payment to the same payee as [expense] is among [lastMonth] (the payments of the calendar
     * month before [expense]'s own) and is split with [group]. Only then is "last month too" true: an earlier split
     * from the payment's own month, or from months before, is not one.
     */
    private fun splitLastMonth(expense: Expense, group: GroupDetail?, lastMonth: List<ExpenseWithAccount>): Boolean {
        val payee = CategoryGuesser.merchantKey(expense.counterparty.orEmpty()).takeIf { it.isNotEmpty() } ?: return false
        val inGroup = group?.expenses.orEmpty().mapTo(HashSet()) { it.expense.id }
        return lastMonth.any { other ->
            val split = other.expense.groupExpenseId
            other.expense.id != expense.id && split != null && split in inGroup &&
                CategoryGuesser.merchantKey(other.expense.counterparty.orEmpty()) == payee
        }
    }

    private fun save() {
        if (local.value.saving || local.value.saved) return
        val everyMonth = local.value.everyMonth
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            // Without a rule (forgotten meanwhile) there is nothing to change; Save just leaves.
            expenses.get(expenseId)?.counterparty?.let { groups.setSplitRuleAuto(it, everyMonth) }
            local.update { it.copy(saving = false, saved = true) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
