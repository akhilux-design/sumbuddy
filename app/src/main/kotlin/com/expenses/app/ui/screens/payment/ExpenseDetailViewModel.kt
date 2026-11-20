package com.expenses.app.ui.screens.payment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.payee
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.GroupExpenseWithShares
import com.expenses.app.groups.data.GroupText
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.SplitLabel
import com.expenses.app.groups.data.SplitRule
import com.expenses.app.ui.state.keepForm
import java.time.Clock
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Expense detail for [expenseId]: shows the expense, lets the person change its category, "Paid to", note and scope
 * (and the amount of an expense typed in by hand), and delete it from its month. In edit mode every change, the
 * category included, is kept in the form until Save changes; Cancel and Back drop them all. Deleting marks it IGNORED, so the
 * same SMS is never offered again and the month total drops at once.
 *
 * A split payment (design 02-everyday/47; docs/server.md 9.4) shows its group and the person's share in place of Just
 * me or Family, and has no "Split this expense". Its category and "Paid to" (what it was for) are the group
 * expense's, so changing them edits the group expense, which then updates this row; the note stays the person's own.
 * Its amount and scope cannot be changed here. Delete asks "Delete Swiggy from Flat 302?" and deletes the group
 * expense for everyone first, then the person's own payment as usual (a friend's expense, mirrored here, simply goes).
 *
 * @param expenseId the expense shown.
 * @param expenses where the expense is read and changed.
 * @param groups the split this expense is part of, its rule, and the group expense edits and deletes go through.
 * @param clock for today's date and the time zone.
 * @param savedState where an unsaved edit is kept while Android stops the app in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseDetailViewModel(
    private val expenseId: Long,
    private val expenses: ExpenseRepository,
    private val groups: GroupsRepository,
    private val clock: Clock,
    savedState: SavedStateHandle,
) : ViewModel() {
    /** What the screen adds to the stored expense. */
    private data class Local(
        val edit: ExpenseEdit? = null,
        val confirmingDelete: Boolean = false,
        val removed: Boolean = false,
    )

    /** The split of a linked row as stored now, and its merchant's rule. */
    private data class Linked(val label: SplitLabel, val rule: SplitRule?)

    private val local = MutableStateFlow(Local(edit = restoreEdit(savedState)))

    init {
        savedState.keepForm(viewModelScope, local) { current ->
            val edit = current.edit
            set(EDITING_KEY, edit != null)
            if (edit != null) {
                set(AMOUNT_KEY, edit.amountText)
                set(PAYEE_KEY, edit.payee)
                set(NOTE_KEY, edit.note)
                set(SCOPE_KEY, edit.scope.name)
                set(CATEGORY_KEY, edit.category.name)
            }
        }
    }

    private val row = expenses.observe(expenseId)

    /** The split of this row, while it is linked to a group expense that is on this phone. */
    private val linked: Flow<Linked?> = row
        .map { it?.expense?.let { e -> Triple(e.groupExpenseId, e.counterparty, e.source) } }
        .distinctUntilChanged()
        .flatMapLatest { key ->
            val groupExpenseId = key?.first ?: return@flatMapLatest flowOf(null)
            // A friend's expense mirrored here is never one of the person's own merchants.
            val rule = if (key.third == ExpenseSource.GROUP) flowOf(null) else groups.splitRuleFor(key.second)
            combine(groups.observeSplitLabel(groupExpenseId), rule) { label, kept -> label?.let { Linked(it, kept) } }
        }

    /** The screen's state. */
    val state: StateFlow<ExpenseDetailUiState> = combine(row, linked, local) { row, linked, local ->
        val expense = row?.expense
        when {
            local.removed -> ExpenseDetailUiState.Removed
            expense == null -> ExpenseDetailUiState.Gone
            else -> ExpenseDetailUiState.Shown(
                details = ExpenseDetails(
                    source = expense.source,
                    status = expense.status,
                    direction = expense.direction,
                    merchant = expense.payee,
                    amountMinor = expense.amountMinor,
                    currency = expense.currency,
                    category = expense.category,
                    scope = expense.scope,
                    note = expense.note,
                    time = PaymentTime.of(expense.occurredAt, clock),
                    account = PaymentAccount.from(row.account),
                    paidWith = expense.paidWith,
                    originalMessage = expense.originalMessage,
                    month = YearMonth.from(expense.occurredAt.atZone(clock.zone)),
                    linked = expense.groupExpenseId != null,
                    inFamilyMonth = expense.familyExpenseId != null,
                ),
                edit = local.edit,
                confirmingDelete = local.confirmingDelete,
                split = linked?.let { splitOf(it) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ExpenseDetailUiState.Loading)

    /**
     * Handles what the person did. ChangeCategory, Split, OpenSplitRule, Review and Back are handled by the caller and
     * ignored here.
     */
    fun onEvent(event: ExpenseDetailEvent) {
        when (event) {
            ExpenseDetailEvent.Edit -> startEdit()
            ExpenseDetailEvent.CancelEdit -> local.update { it.copy(edit = null) }
            is ExpenseDetailEvent.AmountChanged -> updateEdit { it.copy(amountText = event.text, amountError = false) }
            is ExpenseDetailEvent.PayeeChanged -> updateEdit { it.copy(payee = event.text) }
            is ExpenseDetailEvent.NoteChanged -> updateEdit { it.copy(note = event.text) }
            is ExpenseDetailEvent.ScopeChosen -> updateEdit { it.copy(scope = event.scope) }
            is ExpenseDetailEvent.CategoryChosen -> if (local.value.edit != null) {
                updateEdit { it.copy(category = event.category) }
            } else {
                viewModelScope.launch { setCategory(event.category) }
            }
            ExpenseDetailEvent.SaveEdit -> saveEdit()
            ExpenseDetailEvent.Delete -> local.update { it.copy(confirmingDelete = true) }
            ExpenseDetailEvent.DismissDelete -> local.update { it.copy(confirmingDelete = false) }
            ExpenseDetailEvent.ConfirmDelete -> delete()
            ExpenseDetailEvent.CountAgain -> countAgain()
            ExpenseDetailEvent.ChangeCategory, ExpenseDetailEvent.Split, ExpenseDetailEvent.OpenSplitRule,
            ExpenseDetailEvent.Review, ExpenseDetailEvent.Back,
            -> Unit
        }
    }

    private fun splitOf(linked: Linked): ExpenseSplit {
        val label = linked.label
        return ExpenseSplit(
            groupName = label.groupName,
            method = label.method,
            myShareMinor = label.myShareMinor,
            splitsItself = linked.rule?.let { it.auto && it.groupId == label.groupId } == true,
            paidByName = label.paidByName.takeIf { !label.paidByMe },
        )
    }

    private fun updateEdit(change: (ExpenseEdit) -> ExpenseEdit) {
        local.update { current -> current.edit?.let { current.copy(edit = change(it)) } ?: current }
    }

    private fun startEdit() {
        val details = (state.value as? ExpenseDetailUiState.Shown)?.details ?: return
        local.update {
            it.copy(
                edit = ExpenseEdit(
                    // A split's amount is the group's: it is not changed here.
                    amountText = if (details.source == ExpenseSource.MANUAL && !details.linked) amountFieldText(details.amountMinor) else null,
                    amountError = false,
                    payee = details.merchant.orEmpty(),
                    note = details.note.orEmpty(),
                    scope = details.scope,
                    category = details.category,
                    saving = false,
                ),
            )
        }
    }

    private fun saveEdit() {
        val edit = local.value.edit ?: return
        if (edit.saving) return
        val typedAmount = edit.amountText?.let(::parseAmountMinor)
        if (edit.amountText != null && typedAmount == null) {
            updateEdit { it.copy(amountError = true, refusedSubmits = it.refusedSubmits + 1) }
            return
        }
        updateEdit { it.copy(saving = true) }
        viewModelScope.launch {
            val expense = expenses.get(expenseId)
            val stored = expense?.groupExpenseId?.let { storedSplit(it) }
            when {
                expense == null -> Unit
                stored != null -> saveSplitEdit(expense, stored, edit)
                else -> {
                    val amount = typedAmount ?: expense.amountMinor
                    // A share equal to the whole amount means the expense is not split, so it follows the new amount.
                    val share = if (expense.myShareMinor == expense.amountMinor) amount else expense.myShareMinor
                    expenses.update(
                        expense.copy(
                            amountMinor = amount,
                            myShareMinor = share,
                            counterparty = edit.payee.trim().ifEmpty { null },
                            note = edit.note.trim().ifEmpty { null },
                            scope = edit.scope,
                            category = edit.category,
                        ),
                    )
                }
            }
            local.update { it.copy(edit = null) }
        }
    }

    /**
     * Saves an edit of a split row: the category and what it was for go to the group expense (and come back to this
     * row from it); the note, and for a payment of the person's own the "Paid to" they typed, stay on this row.
     */
    private suspend fun saveSplitEdit(expense: Expense, stored: GroupExpenseWithShares, edit: ExpenseEdit) {
        val group = stored.expense
        val payee = edit.payee.trim()
        val payeeChanged = payee != expense.payee.orEmpty()
        // A UPI ID or a phone number typed as the payee never goes to the group: the description stays as it was.
        val description = if (payeeChanged && payee.isNotEmpty()) GroupText.descriptionFor(payee, group.description) else group.description
        if (description != group.description || edit.category != group.category) {
            try {
                groups.updateGroupExpense(group.id, draftOf(stored).copy(description = description, category = edit.category))
            } catch (_: IllegalArgumentException) {
                // Deleted meanwhile: only this row's own fields are saved.
            }
        }
        // The group expense put its fields on this row; read it again before adding the person's own.
        val now = expenses.get(expenseId) ?: return
        val own = now.copy(
            note = edit.note.trim().ifEmpty { null },
            counterparty = if (payeeChanged && now.source == ExpenseSource.SMS) payee.ifEmpty { null } else now.counterparty,
        )
        if (own != now) expenses.update(own)
    }

    /** Changes the category at once (Choose category outside an edit); a split's goes through its group expense. */
    private suspend fun setCategory(category: Category) {
        val stored = expenses.get(expenseId)?.groupExpenseId?.let { storedSplit(it) }
        if (stored == null) {
            expenses.setCategory(expenseId, category)
            return
        }
        if (stored.expense.category == category) return
        try {
            groups.updateGroupExpense(stored.expense.id, draftOf(stored).copy(category = category))
        } catch (_: IllegalArgumentException) {
            expenses.setCategory(expenseId, category)
        }
    }

    /** The group expense [groupExpenseId] with its shares, or null when it is not on this phone. */
    private suspend fun storedSplit(groupExpenseId: String): GroupExpenseWithShares? {
        val label = groups.observeSplitLabel(groupExpenseId).first() ?: return null
        return groups.observeGroup(label.groupId).first()?.expenses?.firstOrNull { it.expense.id == groupExpenseId }
    }

    /** [stored] as it is, as the draft an edit of it starts from. */
    private fun draftOf(stored: GroupExpenseWithShares): GroupExpenseDraft = GroupExpenseDraft(
        description = stored.expense.description,
        category = stored.expense.category,
        amountMinor = stored.expense.amountMinor,
        occurredOn = stored.expense.occurredOn,
        paidByMemberId = stored.expense.paidByMemberId,
        method = stored.expense.splitMethod,
        inputs = stored.shares.associate { it.memberId to it.input },
        auto = stored.expense.auto,
    )

    /** The edit that was open when Android stopped the app, or null. */
    private fun restoreEdit(savedState: SavedStateHandle): ExpenseEdit? {
        if (savedState.get<Boolean>(EDITING_KEY) != true) return null
        val scope = savedState.get<String>(SCOPE_KEY)?.let(ExpenseScope::valueOf) ?: return null
        val category = savedState.get<String>(CATEGORY_KEY)?.let(Category::valueOf) ?: return null
        return ExpenseEdit(
            amountText = savedState[AMOUNT_KEY],
            amountError = false,
            payee = savedState[PAYEE_KEY] ?: "",
            note = savedState[NOTE_KEY] ?: "",
            scope = scope,
            category = category,
            saving = false,
        )
    }

    private fun delete() {
        local.update { it.copy(confirmingDelete = false) }
        viewModelScope.launch {
            // A split goes for everyone first: deleting only this row would leave friends owing for it, and a
            // friend's expense mirrored here would come back with the next sync (docs/server.md 9.4).
            val groupExpenseId = expenses.get(expenseId)?.groupExpenseId
            if (groupExpenseId != null && groups.observeSplitLabel(groupExpenseId).first() != null) {
                groups.deleteGroupExpense(groupExpenseId)
            }
            // The person's own payment then stops counting; a mirror is already gone with its group expense.
            if (expenses.get(expenseId) != null) expenses.ignore(expenseId)
            local.update { it.copy(removed = true) }
        }
    }

    private fun countAgain() {
        viewModelScope.launch {
            val expense = expenses.get(expenseId) ?: return@launch
            expenses.add(id = expenseId, category = expense.category, scope = expense.scope, note = expense.note)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val EDITING_KEY = "editing"
        const val AMOUNT_KEY = "amount"
        const val PAYEE_KEY = "payee"
        const val NOTE_KEY = "note"
        const val SCOPE_KEY = "scope"
        const val CATEGORY_KEY = "category"
    }
}
