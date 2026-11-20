package com.expenses.app.ui.screens.payment

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.model.payee
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.ui.format.PayeeWording
import com.expenses.app.ui.format.payeeWording
import java.time.Clock
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What Expense added (design 02-everyday/07-expense-added) shows. */
sealed interface ExpenseAddedUiState {
    /** The expense is being read. */
    data object Loading : ExpenseAddedUiState

    /** No expense has this id. */
    data object Gone : ExpenseAddedUiState

    /**
     * Undo was done; the caller leaves the screen.
     *
     * @param expenseId the expense.
     * @param backToForm true for a payment from SMS, which waits for a decision again: the caller reopens Add to
     * expenses. False for an expense typed by hand, which was removed: the caller goes back.
     */
    data class Undone(val expenseId: Long, val backToForm: Boolean) : ExpenseAddedUiState

    /**
     * The expense that was added and the month's new total.
     *
     * @param expenseId the expense.
     * @param merchant who was paid, or null.
     * @param payeeWording how the amount and [merchant] read together ("at", "to" or "for").
     * @param amountMinor the amount, in paise.
     * @param category its category.
     * @param account the account it came from, or null.
     * @param paidWith how it was paid, shown when there is no account.
     * @param scope Just me or Family.
     * @param month the month it counts in.
     * @param monthTotalMinor what is spent in that month so far, this expense included, in paise.
     */
    @Immutable
    data class Shown(
        val expenseId: Long,
        val merchant: String?,
        val payeeWording: PayeeWording = PayeeWording.AT,
        val amountMinor: Long,
        val category: Category,
        val account: PaymentAccount?,
        val paidWith: PaidWith,
        val scope: ExpenseScope,
        val month: YearMonth,
        val monthTotalMinor: Long,
    ) : ExpenseAddedUiState
}

/** Everything the person can do on Expense added. */
sealed interface ExpenseAddedEvent {
    /** "Done": back to Home. */
    data object Done : ExpenseAddedEvent

    /** "Undo": take the Add back. */
    data object Undo : ExpenseAddedEvent

    /** The expense row was tapped: open Expense detail. */
    data object OpenExpense : ExpenseAddedEvent
}

/**
 * Expense added for [expenseId]: the success screen after Add to expenses or Add expense.
 *
 * @param expenseId the expense just added.
 * @param expenses where the expense and the month total are read.
 * @param clock for the time zone of the month.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseAddedViewModel(
    private val expenseId: Long,
    private val expenses: ExpenseRepository,
    private val clock: Clock,
) : ViewModel() {
    private val undone = MutableStateFlow<ExpenseAddedUiState.Undone?>(null)

    /** True while Undo writes; the expense may vanish first, and the screen keeps showing it until Undo is done. */
    private val undoing = MutableStateFlow(false)

    /** The expense as last shown, kept for the moment [undoing] is true. */
    private var lastShown: ExpenseAddedUiState.Shown? = null

    private val shown = expenses.observe(expenseId).flatMapLatest { row ->
        val expense = row?.expense
        if (expense == null) {
            flowOf(ExpenseAddedUiState.Gone)
        } else {
            val month = YearMonth.from(expense.occurredAt.atZone(clock.zone))
            expenses.observeMonthTotal(month).map { total ->
                ExpenseAddedUiState.Shown(
                    expenseId = expense.id,
                    merchant = expense.payee,
                    payeeWording = expense.payeeWording(),
                    amountMinor = expense.amountMinor,
                    category = expense.category,
                    account = PaymentAccount.from(row.account),
                    paidWith = expense.paidWith,
                    scope = expense.scope,
                    month = month,
                    monthTotalMinor = total,
                )
            }
        }
    }

    /** The screen's state. */
    val state: StateFlow<ExpenseAddedUiState> = combine(shown, undone, undoing) { shown, undone, undoing ->
        when {
            undone != null -> undone
            undoing && shown !is ExpenseAddedUiState.Shown -> lastShown ?: shown
            else -> shown.also { if (it is ExpenseAddedUiState.Shown) lastShown = it }
        }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ExpenseAddedUiState.Loading)

    /** Handles what the person did. Done and OpenExpense are handled by the caller and ignored here. */
    fun onEvent(event: ExpenseAddedEvent) {
        when (event) {
            ExpenseAddedEvent.Undo -> undo()
            ExpenseAddedEvent.Done, ExpenseAddedEvent.OpenExpense -> Unit
        }
    }

    private fun undo() {
        if (undone.value != null || undoing.value) return
        undoing.value = true
        viewModelScope.launch {
            val expense = expenses.get(expenseId)
            if (expense == null) {
                undoing.value = false
                return@launch
            }
            val fromSms = expense.source == ExpenseSource.SMS
            when {
                fromSms && expense.status == ExpenseStatus.ADDED -> expenses.undoAdd(expenseId)
                !fromSms -> expenses.delete(expenseId)
            }
            undone.value = ExpenseAddedUiState.Undone(expenseId, backToForm = fromSms)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
