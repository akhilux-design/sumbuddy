package com.expenses.app.ui.screens.payment

import android.database.sqlite.SQLiteConstraintException
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.model.ReviewItem
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.data.repository.ReviewRepository
import com.expenses.app.data.repository.TransactionRunner
import com.expenses.app.ui.state.keepForm
import com.expenses.parser.Direction
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Needs review (design 02-everyday/19-needs-review) shows. */
sealed interface NeedsReviewUiState {
    /** The messages are being read. */
    data object Loading : NeedsReviewUiState

    /**
     * The open messages, newest first, with the form of the one being checked.
     *
     * @param items every message that still needs a decision; empty when all are sorted.
     * @param form the form of the open message, or null when there is none.
     * @param today today's date, to write dates as "14 Nov".
     * @param added what was added last, shown as a short confirmation, or null.
     */
    @Immutable
    data class Shown(
        val items: List<ReviewEntry>,
        val form: ReviewForm?,
        val today: LocalDate,
        val added: ReviewAdded?,
    ) : NeedsReviewUiState
}

/**
 * One message the parser could not read for sure. Its text stays on this phone and is cleared once it is sorted.
 *
 * @param id the review item's id.
 * @param sender who sent it, for example "VM-HDFCBK".
 * @param body the message text.
 * @param date the day it arrived.
 * @param amountMinor a rupee amount found in the text, in paise, or null when there is none.
 */
@Immutable
data class ReviewEntry(
    val id: Long,
    val sender: String,
    val body: String,
    val date: LocalDate,
    val amountMinor: Long?,
)

/**
 * The form of the message being checked.
 *
 * @param itemId the review item it belongs to.
 * @param amountText the amount field as typed; filled with the amount found in the text.
 * @param payee the "Paid to" field as typed.
 * @param amountError true after Save with an amount that is empty, zero or not a number.
 * @param saving true while the expense is stored; the buttons ignore more taps.
 * @param refusedSubmits how many times Save was refused for the amount on this screen; each one shakes the amount
 * and plays the reject haptic. It only grows, so switching messages never looks like a new refusal.
 */
@Immutable
data class ReviewForm(
    val itemId: Long,
    val amountText: String,
    val payee: String,
    val amountError: Boolean,
    val saving: Boolean,
    val refusedSubmits: Int = 0,
)

/** What saving a checked message did. */
enum class ReviewOutcome {
    /** A payment out was added to the month. */
    Spend,

    /** Money in was listed; it never counts as spending. */
    MoneyIn,

    /** The same payment was already stored from another SMS, so nothing was added again. */
    AlreadyCounted,
}

/**
 * What was just saved from a message, for the line "Added ₹1,200 to November.".
 *
 * @param amountMinor the amount, in paise.
 * @param month the month it falls in.
 * @param outcome whether it was added as spending, listed as money in, or already stored.
 */
@Immutable
data class ReviewAdded(val amountMinor: Long, val month: YearMonth, val outcome: ReviewOutcome = ReviewOutcome.Spend)

/** Everything the person can do on Needs review. */
sealed interface NeedsReviewEvent {
    /** A folded message was tapped: open its form. */
    data class Open(val itemId: Long) : NeedsReviewEvent

    /** The amount field changed to [text]. */
    data class AmountChanged(val text: String) : NeedsReviewEvent

    /** The "Paid to" field changed to [text]. */
    data class PayeeChanged(val text: String) : NeedsReviewEvent

    /** "Save": add the open message as an expense. */
    data object Save : NeedsReviewEvent

    /** "Not a spend": the open message is not an expense. */
    data object NotASpend : NeedsReviewEvent

    /** The back button. */
    data object Back : NeedsReviewEvent
}

/**
 * Needs review: messages that look like payments but could not be read for sure. The person types the amount to
 * add one as an expense (it counts at once, with the message kept as its original message), or marks it as not a
 * spend. Either way the item is resolved and its text cleared from the review list.
 *
 * @param reviews the open messages.
 * @param expenses where an added message is stored and its category guessed.
 * @param transactions keeps storing the expense and resolving the message together.
 * @param clock for today's date and the time zone.
 * @param savedState where the open form is kept while Android stops the app in the background.
 */
class NeedsReviewViewModel(
    private val reviews: ReviewRepository,
    private val expenses: ExpenseRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    savedState: SavedStateHandle,
) : ViewModel() {
    /** What the screen adds to the stored messages. */
    private data class Local(
        val openId: Long? = null,
        val amountText: String? = null,
        val payee: String = "",
        val amountError: Boolean = false,
        val saving: Boolean = false,
        val added: ReviewAdded? = null,
        val refusedSubmits: Int = 0,
    )

    private val local = MutableStateFlow(
        Local(
            openId = savedState[OPEN_KEY],
            amountText = savedState[AMOUNT_KEY],
            payee = savedState[PAYEE_KEY] ?: "",
        ),
    )

    init {
        savedState.keepForm(viewModelScope, local) {
            set(OPEN_KEY, it.openId)
            set(AMOUNT_KEY, it.amountText)
            set(PAYEE_KEY, it.payee)
        }
    }

    /** The messages as last read, to find the open one when saving. */
    private var lastItems: List<ReviewItem> = emptyList()

    /** The screen's state. */
    val state: StateFlow<NeedsReviewUiState> = combine(reviews.observeOpen(), local) { items, local ->
        lastItems = items
        val entries = items.map { it.toEntry() }
        val open = entries.firstOrNull { it.id == local.openId } ?: entries.firstOrNull()
        NeedsReviewUiState.Shown(
            items = entries,
            form = open?.let { entry ->
                val same = entry.id == local.openId
                ReviewForm(
                    itemId = entry.id,
                    amountText = local.amountText.takeIf { same } ?: entry.amountMinor?.let(::amountFieldText).orEmpty(),
                    payee = if (same) local.payee else "",
                    amountError = same && local.amountError,
                    saving = same && local.saving,
                    refusedSubmits = local.refusedSubmits,
                )
            },
            today = LocalDate.now(clock),
            added = local.added,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), NeedsReviewUiState.Loading)

    /** Handles what the person did. Back is handled by the caller and ignored here. */
    fun onEvent(event: NeedsReviewEvent) {
        when (event) {
            is NeedsReviewEvent.Open -> local.update { if (it.saving) it else it.copy(openId = event.itemId).cleared() }
            is NeedsReviewEvent.AmountChanged -> editOpen { it.copy(amountText = event.text, amountError = false) }
            is NeedsReviewEvent.PayeeChanged -> editOpen { it.copy(payee = event.text) }
            NeedsReviewEvent.Save -> save()
            NeedsReviewEvent.NotASpend -> notASpend()
            NeedsReviewEvent.Back -> Unit
        }
    }

    /** Changes the open form, first pinning which message it belongs to (the first one when none was tapped). */
    private fun editOpen(change: (Local) -> Local) {
        val form = (state.value as? NeedsReviewUiState.Shown)?.form ?: return
        local.update { current ->
            val pinned = if (current.openId == form.itemId) {
                current
            } else {
                current.copy(openId = form.itemId).cleared().copy(amountText = form.amountText)
            }
            change(pinned)
        }
    }

    private fun save() {
        val form = (state.value as? NeedsReviewUiState.Shown)?.form ?: return
        if (form.saving) return
        val item = lastItems.firstOrNull { it.id == form.itemId } ?: return
        val amount = parseAmountMinor(form.amountText)
        if (amount == null) {
            editOpen { it.copy(amountError = true, refusedSubmits = it.refusedSubmits + 1) }
            return
        }
        editOpen { it.copy(saving = true) }
        viewModelScope.launch {
            val month = YearMonth.from(item.receivedAt.atZone(clock.zone))
            val direction = if (isMoneyInMessage(item.body)) Direction.CREDIT else Direction.DEBIT
            // The same rule as for SMS read automatically: a payment already stored from another message within 15
            // minutes, with the same amount and direction, is that payment, so it is not counted twice.
            val copies = expenses.findPossibleCopies(direction, amount, Expense.INR, item.receivedAt, referenceId = null)
            if (copies.any { it.source == ExpenseSource.SMS }) {
                reviews.resolve(item.id)
                local.update { Local(added = ReviewAdded(amount, month, ReviewOutcome.AlreadyCounted)) }
                return@launch
            }
            val payee = form.payee.trim().ifEmpty { null }
            val category = expenses.guessCategory(payee)
            val expense = Expense(
                amountMinor = amount,
                direction = direction,
                counterparty = payee,
                category = category,
                occurredAt = item.receivedAt,
                source = ExpenseSource.SMS,
                status = ExpenseStatus.ADDED,
                paidWith = PaidWith.OTHER,
                smsId = item.smsId,
                smsSender = item.sender,
                smsReceivedAt = item.receivedAt,
                originalMessage = item.body,
            )
            try {
                transactions.run {
                    expenses.insert(expense)
                    reviews.resolve(item.id)
                }
            } catch (alreadyStored: SQLiteConstraintException) {
                // This message is already an expense (added another way), so only the review item is closed.
                reviews.resolve(item.id)
            }
            val outcome = if (direction == Direction.CREDIT) ReviewOutcome.MoneyIn else ReviewOutcome.Spend
            local.update { Local(added = ReviewAdded(amount, month, outcome)) }
        }
    }

    private fun notASpend() {
        val form = (state.value as? NeedsReviewUiState.Shown)?.form ?: return
        if (form.saving) return
        viewModelScope.launch {
            reviews.resolve(form.itemId)
            local.update { Local() }
        }
    }

    private fun Local.cleared(): Local = copy(amountText = null, payee = "", amountError = false, added = null)

    private fun ReviewItem.toEntry(): ReviewEntry = ReviewEntry(
        id = id,
        sender = sender,
        body = body,
        date = receivedAt.atZone(clock.zone).toLocalDate(),
        amountMinor = amountInMessage(body),
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val OPEN_KEY = "open_id"
        const val AMOUNT_KEY = "amount"
        const val PAYEE_KEY = "payee"
    }
}
