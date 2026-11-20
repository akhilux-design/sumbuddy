package com.expenses.app.ui.screens.payment

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.groups.data.GroupDetail
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.ui.format.shortName
import com.expenses.app.ui.screens.split.TypedExpense
import com.expenses.app.ui.state.keepForm
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What Add expense (design 02-everyday/15-add-expense) shows: an expense typed in by hand, the person's own, or, with
 * "Split this" on, one to split on the next step.
 *
 * @param amountText the amount as typed, digits with an optional point.
 * @param amountError true after Add with an amount that is empty, zero or not a number; the screen says so.
 * @param refusedSubmits how many times Add (or Next) was refused for the amount; each one shakes the amount and plays
 * the reject haptic. A count, not a flag: a second refused tap does not change [amountError] but must shake again.
 * @param whatFor the "What was it for?" field as typed.
 * @param category the chosen category; guessed from [whatFor] until the person picks one.
 * @param paidWith Cash, UPI or Card.
 * @param date the day it was paid.
 * @param today today's date, the last day the date picker allows.
 * @param scope Just me or Family.
 * @param pickingDate true while the system date picker is open.
 * @param saving true while the expense is stored; Add ignores more taps.
 * @param addedId the id of the stored expense once it is added; the caller then opens Expense added.
 * @param splitThis true while "Split this" is on: the footer reads "Next: choose split".
 * @param payers the "Paid by" chips after "You": the active members of the group the expense is for, once a group is
 * known (opened from a Group page) and "Split this" is on; empty otherwise (not designed: Paid by then shows only
 * You).
 * @param paidBy who paid: one of [payers], by member id, or null for the person.
 */
@Immutable
data class AddExpenseUiState(
    val amountText: String,
    val amountError: Boolean,
    val whatFor: String,
    val category: Category,
    val paidWith: PaidWith,
    val date: LocalDate,
    val today: LocalDate,
    val scope: ExpenseScope,
    val pickingDate: Boolean,
    val saving: Boolean,
    val addedId: Long?,
    val splitThis: Boolean = false,
    val payers: List<Payer> = emptyList(),
    val paidBy: String? = null,
    val refusedSubmits: Int = 0,
) {
    /** The month the expense counts in, for "Add to November". */
    val month: YearMonth get() = YearMonth.from(date)

    /** True when someone else paid: "Paid with" is then not asked (not designed). */
    val someoneElsePaid: Boolean get() = paidBy != null

    /** Making the first state. */
    companion object {
        /** The empty form for [today]: no amount, Other, Cash, today, Just me. */
        fun empty(today: LocalDate): AddExpenseUiState = AddExpenseUiState(
            amountText = "",
            amountError = false,
            whatFor = "",
            category = Category.OTHER,
            paidWith = PaidWith.CASH,
            date = today,
            today = today,
            scope = ExpenseScope.ME,
            pickingDate = false,
            saving = false,
            addedId = null,
        )
    }
}

/**
 * One "Paid by" chip after "You": a member of the group the expense is for.
 *
 * @param memberId their member id in the group.
 * @param name their name.
 */
@Immutable
data class Payer(val memberId: String, val name: String)

/** Everything the person can do on Add expense. */
sealed interface AddExpenseEvent {
    /** The amount field changed to [text]. */
    data class AmountChanged(val text: String) : AddExpenseEvent

    /** "What was it for?" changed to [text]. */
    data class WhatForChanged(val text: String) : AddExpenseEvent

    /** A category chip was tapped. */
    data class CategoryChosen(val category: Category) : AddExpenseEvent

    /** A "Paid by" chip was tapped: [memberId], or null for "You". */
    data class PaidByChosen(val memberId: String?) : AddExpenseEvent

    /** Cash, UPI or Card was tapped. */
    data class PaidWithChosen(val paidWith: PaidWith) : AddExpenseEvent

    /** Just me or Family was tapped. */
    data class ScopeChosen(val scope: ExpenseScope) : AddExpenseEvent

    /** The date row was tapped: open the system date picker. */
    data object PickDate : AddExpenseEvent

    /** [date] was picked in the date picker. */
    data class DatePicked(val date: LocalDate) : AddExpenseEvent

    /** The date picker closed. */
    data object DatePickerClosed : AddExpenseEvent

    /** "Split this" was switched to [on]. */
    data class SplitThisChanged(val on: Boolean) : AddExpenseEvent

    /** "Add to <month>" was tapped. */
    data object Add : AddExpenseEvent

    /**
     * "Next: choose split" was tapped: the caller asks [AddExpenseViewModel.splitDraft] and opens Split it with it.
     */
    data object Next : AddExpenseEvent

    /** The back button was tapped. */
    data object Back : AddExpenseEvent
}

/** The ways of paying offered on Add expense, in the design's order. */
internal val ManualPaidWith: List<PaidWith> = listOf(PaidWith.CASH, PaidWith.UPI, PaidWith.CARD)

/** The longest amount text kept: "1000000000.00" and a little room for commas. */
private const val MAX_AMOUNT_TEXT = 16

/**
 * Add expense: stores an expense the person typed in by hand. It counts at once (status ADDED, source MANUAL). With
 * "Split this" on, nothing is stored here: "Next: choose split" hands the typed expense to Split it
 * ([splitDraft], [com.expenses.app.ui.nav.SplitTyped]), which makes the group expense.
 *
 * Paid by is You until a group is known. Opened from a Group page ([groupId]) the form starts with "Split this" on and
 * lists that group's people after You; turning "Split this" off puts Paid by back to You. From Home or Activity the
 * payer is You (not designed: the group is chosen on the next step, so its people are not known here).
 *
 * @param groupId the group the expense is for (a Group page's "Add"), or null.
 * @param expenses where the expense is stored and the category is guessed.
 * @param groups the group's people for "Paid by".
 * @param clock for today's date, the time of day and the time zone.
 * @param savedState where the form is kept while Android stops the app in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseViewModel(
    private val groupId: String?,
    private val expenses: ExpenseRepository,
    groups: GroupsRepository,
    private val clock: Clock,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val form = MutableStateFlow(restore(savedState, LocalDate.now(clock)))

    private val group: Flow<GroupDetail?> = if (groupId == null) flowOf(null) else groups.observeGroup(groupId)

    /** The screen's state. */
    val state: StateFlow<AddExpenseUiState> = combine(form, group) { form, group -> withPayers(form, group) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), form.value)

    /** True once the person picked a category; the guess from "What was it for?" then stops. */
    private var categoryChosenByHand = savedState[BY_HAND_KEY] ?: false

    init {
        savedState.keepForm(viewModelScope, form) {
            set(AMOUNT_KEY, it.amountText)
            set(WHAT_FOR_KEY, it.whatFor)
            set(CATEGORY_KEY, it.category.name)
            set(PAID_WITH_KEY, it.paidWith.name)
            set(DAY_KEY, it.date.toEpochDay())
            set(SCOPE_KEY, it.scope.name)
            set(PICKING_KEY, it.pickingDate)
            set(BY_HAND_KEY, categoryChosenByHand)
            set(SPLIT_KEY, it.splitThis)
            set(PAID_BY_KEY, it.paidBy)
        }
    }

    private var guess: Job? = null

    /** Handles what the person did. Next and Back are handled by the caller and ignored here. */
    fun onEvent(event: AddExpenseEvent) {
        when (event) {
            is AddExpenseEvent.AmountChanged -> form.update {
                it.copy(amountText = cleanAmountText(event.text), amountError = false)
            }
            is AddExpenseEvent.WhatForChanged -> whatForChanged(event.text)
            is AddExpenseEvent.CategoryChosen -> {
                categoryChosenByHand = true
                guess?.cancel()
                form.update { it.copy(category = event.category) }
            }
            is AddExpenseEvent.PaidByChosen -> form.update { f -> f.copy(paidBy = if (f.splitThis) event.memberId else null) }
            is AddExpenseEvent.PaidWithChosen -> form.update { it.copy(paidWith = event.paidWith) }
            is AddExpenseEvent.ScopeChosen -> form.update { it.copy(scope = event.scope) }
            AddExpenseEvent.PickDate -> form.update { it.copy(pickingDate = true) }
            is AddExpenseEvent.DatePicked -> form.update {
                it.copy(date = minOf(event.date, it.today), pickingDate = false)
            }
            AddExpenseEvent.DatePickerClosed -> form.update { it.copy(pickingDate = false) }
            // Without a split nobody else can have paid: Paid by goes back to You.
            is AddExpenseEvent.SplitThisChanged -> form.update {
                it.copy(splitThis = event.on, paidBy = if (event.on) it.paidBy else null)
            }
            AddExpenseEvent.Add -> add()
            AddExpenseEvent.Next, AddExpenseEvent.Back -> Unit
        }
    }

    /**
     * The typed expense for Split it ("Next: choose split"), or null when the amount is not right (the screen then
     * says so) or "Split this" is off.
     */
    fun splitDraft(): TypedExpense? {
        val current = form.value
        if (!current.splitThis || current.saving) return null
        val amount = parseAmountMinor(current.amountText)
        if (amount == null) {
            form.update { it.copy(amountError = true, refusedSubmits = it.refusedSubmits + 1) }
            return null
        }
        // Only someone the "Paid by" chips show: a member who left meanwhile is no longer offered.
        val paidBy = current.paidBy?.takeIf { id -> state.value.payers.any { it.memberId == id } }
        return TypedExpense(
            amountMinor = amount,
            whatFor = current.whatFor.trim(),
            category = current.category,
            epochDay = current.date.toEpochDay(),
            paidByMemberId = paidBy,
            paidWith = current.paidWith,
            groupId = groupId,
        )
    }

    /** [form] with the group's people as "Paid by" chips, while "Split this" is on. */
    private fun withPayers(form: AddExpenseUiState, group: GroupDetail?): AddExpenseUiState {
        if (!form.splitThis || group == null) return form.copy(payers = emptyList(), paidBy = null)
        // First names, as 15 draws the chips; whole names where two clash.
        val everyone = group.members.map { it.member.name }
        val payers = group.members
            .filter { !it.isMe && !it.member.removed }
            .map { Payer(it.member.id, shortName(it.member.name, everyone)) }
        return form.copy(payers = payers, paidBy = form.paidBy?.takeIf { id -> payers.any { it.memberId == id } })
    }

    private fun whatForChanged(text: String) {
        form.update { it.copy(whatFor = text) }
        if (categoryChosenByHand) return
        guess?.cancel()
        guess = viewModelScope.launch {
            val category = if (text.isBlank()) Category.OTHER else expenses.guessCategory(text)
            form.update { if (it.whatFor == text) it.copy(category = category) else it }
        }
    }

    private fun add() {
        val current = form.value
        if (current.saving || current.addedId != null || current.splitThis) return
        val amount = parseAmountMinor(current.amountText)
        if (amount == null) {
            form.update { it.copy(amountError = true, refusedSubmits = it.refusedSubmits + 1) }
            return
        }
        guess?.cancel()
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val occurredAt = current.date.atTime(LocalTime.now(clock)).atZone(clock.zone).toInstant()
            val id = expenses.addManual(
                amountMinor = amount,
                counterparty = current.whatFor,
                category = current.category,
                occurredAt = occurredAt,
                paidWith = current.paidWith,
                scope = current.scope,
            )
            form.update { it.copy(saving = false, addedId = id) }
        }
    }

    /** The form as it was kept in [savedState], or the empty form for [today] ("Split this" on for a group). */
    private fun restore(savedState: SavedStateHandle, today: LocalDate): AddExpenseUiState {
        val empty = AddExpenseUiState.empty(today)
        return empty.copy(
            amountText = savedState[AMOUNT_KEY] ?: empty.amountText,
            whatFor = savedState[WHAT_FOR_KEY] ?: empty.whatFor,
            category = savedState.get<String>(CATEGORY_KEY)?.let(Category::valueOf) ?: empty.category,
            paidWith = savedState.get<String>(PAID_WITH_KEY)?.let(PaidWith::valueOf) ?: empty.paidWith,
            date = savedState.get<Long>(DAY_KEY)?.let(LocalDate::ofEpochDay)?.let { minOf(it, today) } ?: empty.date,
            scope = savedState.get<String>(SCOPE_KEY)?.let(ExpenseScope::valueOf) ?: empty.scope,
            pickingDate = savedState[PICKING_KEY] ?: false,
            splitThis = savedState[SPLIT_KEY] ?: (groupId != null),
            paidBy = savedState[PAID_BY_KEY],
        )
    }

    /** Keeps only digits, commas and one decimal point, so a stray character cannot end up in the amount. */
    private fun cleanAmountText(text: String): String {
        val kept = text.filter { it.isDigit() || it == '.' || it == ',' }
        val point = kept.indexOf('.')
        val onePoint = if (point < 0) kept else kept.substring(0, point + 1) + kept.substring(point + 1).replace(".", "")
        return onePoint.take(MAX_AMOUNT_TEXT)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val AMOUNT_KEY = "amount"
        const val WHAT_FOR_KEY = "what_for"
        const val CATEGORY_KEY = "category"
        const val PAID_WITH_KEY = "paid_with"
        const val DAY_KEY = "day"
        const val SCOPE_KEY = "scope"
        const val PICKING_KEY = "picking"
        const val BY_HAND_KEY = "category_by_hand"
        const val SPLIT_KEY = "split_this"
        const val PAID_BY_KEY = "paid_by"
    }
}
