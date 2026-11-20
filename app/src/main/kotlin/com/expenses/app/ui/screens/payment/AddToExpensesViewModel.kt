package com.expenses.app.ui.screens.payment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.payee
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.ui.state.keepForm
import java.time.Clock
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Add to expenses for the detected payment [expenseId]. The category is stored as soon as it is chosen (here or on
 * Choose category), so both screens show the same one; the other fields are kept here until Add.
 *
 * "Split it instead" opens Split it for the payment (the caller navigates); this screen stays under it, and leaves
 * with Split. Sorted.'s Done once the payment is split.
 *
 * @param expenseId the DETECTED payment.
 * @param expenses where the payment is read and added.
 * @param clock for today's date and the time zone.
 * @param savedState where the typed fields are kept while Android stops the app in the background.
 */
class AddToExpensesViewModel(
    private val expenseId: Long,
    private val expenses: ExpenseRepository,
    private val clock: Clock,
    savedState: SavedStateHandle,
) : ViewModel() {
    /** What the person changed so far. Null fields still show the stored value. */
    private data class Edits(
        val payee: String? = null,
        val scope: ExpenseScope? = null,
        val note: String? = null,
        val alwaysThisCategory: Boolean = false,
        val saving: Boolean = false,
        val added: Boolean = false,
    )

    private val edits = MutableStateFlow(
        Edits(
            payee = savedState[PAYEE_KEY],
            scope = savedState.get<String>(SCOPE_KEY)?.let(ExpenseScope::valueOf),
            note = savedState[NOTE_KEY],
            alwaysThisCategory = savedState[ALWAYS_KEY] ?: false,
        ),
    )

    init {
        savedState.keepForm(viewModelScope, edits) {
            set(PAYEE_KEY, it.payee)
            set(SCOPE_KEY, it.scope?.name)
            set(NOTE_KEY, it.note)
            set(ALWAYS_KEY, it.alwaysThisCategory)
        }
    }

    /** Keeps a category change and Add in the order they were tapped. */
    private val writes = Mutex()

    /** The screen's state. */
    val state: StateFlow<AddToExpensesUiState> = combine(expenses.observe(expenseId), edits) { row, edits ->
        val expense = row?.expense
        when {
            // While saving, the row turns ADDED a moment before the save finishes: that is this screen's own Add.
            edits.added || (edits.saving && expense?.status == ExpenseStatus.ADDED) ->
                AddToExpensesUiState.Added(expenseId)
            expense == null -> AddToExpensesUiState.Gone
            expense.status != ExpenseStatus.DETECTED ->
                AddToExpensesUiState.AlreadySorted(expenseId, added = expense.status == ExpenseStatus.ADDED)
            else -> AddToExpensesUiState.Form(
                amountMinor = expense.amountMinor,
                time = PaymentTime.of(expense.occurredAt, clock),
                account = PaymentAccount.from(row.account),
                paidWith = expense.paidWith,
                payee = edits.payee ?: expense.payee.orEmpty(),
                category = expense.category,
                scope = edits.scope ?: expense.scope,
                note = edits.note ?: expense.note.orEmpty(),
                alwaysThisCategory = edits.alwaysThisCategory,
                month = YearMonth.from(expense.occurredAt.atZone(clock.zone)),
                saving = edits.saving,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AddToExpensesUiState.Loading)

    /** Handles what the person did. Navigation events (Split among them) are handled by the caller and ignored here. */
    fun onEvent(event: AddToExpensesEvent) {
        when (event) {
            is AddToExpensesEvent.PayeeChanged -> edits.update { it.copy(payee = event.text) }
            is AddToExpensesEvent.CategoryChosen -> viewModelScope.launch {
                writes.withLock { expenses.setCategory(expenseId, event.category) }
            }
            is AddToExpensesEvent.ScopeChosen -> edits.update { it.copy(scope = event.scope) }
            is AddToExpensesEvent.NoteChanged -> edits.update { it.copy(note = event.text) }
            is AddToExpensesEvent.AlwaysThisCategoryChanged -> edits.update { it.copy(alwaysThisCategory = event.on) }
            AddToExpensesEvent.Add -> add()
            AddToExpensesEvent.Split, AddToExpensesEvent.MoreCategories, AddToExpensesEvent.SeeExpense, AddToExpensesEvent.Back -> Unit
        }
    }

    private fun add() {
        if (state.value !is AddToExpensesUiState.Form || edits.value.saving) return
        edits.update { it.copy(saving = true) }
        viewModelScope.launch {
            writes.withLock { addNow() }
        }
    }

    private suspend fun addNow() {
        val expense = expenses.get(expenseId)
        if (expense == null || expense.status != ExpenseStatus.DETECTED) {
            edits.update { it.copy(saving = false) }
            return
        }
        val choices = edits.value
        val payee = (choices.payee ?: expense.counterparty)?.trim()?.ifEmpty { null }
        if (payee != expense.counterparty) expenses.update(expense.copy(counterparty = payee))
        expenses.add(
            id = expenseId,
            category = expense.category,
            scope = choices.scope ?: expense.scope,
            note = (choices.note ?: expense.note)?.trim()?.ifEmpty { null },
            alwaysThisCategory = choices.alwaysThisCategory && payee != null,
        )
        edits.update { it.copy(saving = false, added = true) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val PAYEE_KEY = "payee"
        const val SCOPE_KEY = "scope"
        const val NOTE_KEY = "note"
        const val ALWAYS_KEY = "always"
    }
}
