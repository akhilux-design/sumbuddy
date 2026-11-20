package com.expenses.app.ui.screens.review

import androidx.compose.runtime.Immutable
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.data.model.PaidWith
import com.expenses.app.ui.screens.payment.PaymentAccount
import com.expenses.app.ui.screens.payment.PaymentTime
import java.time.YearMonth

/** What Review payments (design 02-everyday/34 to 38) shows. */
sealed interface ReviewPaymentsUiState {
    /** The waiting payments are being read. */
    data object Loading : ReviewPaymentsUiState

    /**
     * A payment waits on top of the stack (design 34 to 37).
     *
     * @param card the payment on top, the oldest still waiting.
     * @param cardsBehind how many plain cards to draw behind it, 0 to 2.
     * @param position the place of [card] among every payment of this run, from 1: "3" in "3 of 14".
     * @param total every payment of this run, decided and split ones included: "14" in "3 of 14".
     * @param canUndo true when there is a decision to take back.
     * @param addAll what the Add all sheet shows while it is open, or null when it is closed.
     */
    @Immutable
    data class Reviewing(
        val card: ReviewCard,
        val cardsBehind: Int,
        val position: Int,
        val total: Int,
        val canUndo: Boolean,
        val addAll: AddAllSummary?,
    ) : ReviewPaymentsUiState

    /**
     * Nothing waits any more (design 38, "All sorted").
     *
     * @param addedCount payments added in this run, the ones split from it included.
     * @param addedMinor what they add to the person's spending (their share), in paise.
     * @param ignoredCount payments ignored in this run.
     * @param month the current month, for "is in your November expenses" and "November so far".
     * @param addedAllInMonth true when every payment added falls in [month]; otherwise the line does not name it.
     * @param monthTotalMinor what is spent in [month] so far, in paise.
     * @param canUndo true when there is an Add or Ignore to take back. False when the run's only payments were split
     * (Undo on "Split. Sorted." takes a split back, not this screen), so Undo is hidden as on the stack.
     */
    @Immutable
    data class Sorted(
        val addedCount: Int,
        val addedMinor: Long,
        val ignoredCount: Int,
        val month: YearMonth,
        val addedAllInMonth: Boolean,
        val monthTotalMinor: Long,
        val canUndo: Boolean = true,
    ) : ReviewPaymentsUiState {
        /** Every payment decided in this review. */
        val sortedCount: Int get() = addedCount + ignoredCount
    }

    /** Nothing waits and nothing was decided here (for example everything was handled elsewhere): the caller leaves. */
    data object NothingWaiting : ReviewPaymentsUiState
}

/**
 * One waiting payment as the Swipe Card shows it.
 *
 * @param id the expense id.
 * @param amountMinor the amount paid, in paise.
 * @param payee who was paid, or their UPI ID when the SMS gives no name, or null when it names neither.
 * @param time when it was paid.
 * @param account the account or card it was paid from, or null.
 * @param paidWith how it was paid, named when the SMS names no account.
 * @param category the suggested category, or the one the person chose.
 */
@Immutable
data class ReviewCard(
    val id: Long,
    val amountMinor: Long,
    val payee: String?,
    val time: PaymentTime,
    val account: PaymentAccount?,
    val paidWith: PaidWith,
    val category: Category,
)

/**
 * What the Add all sheet shows.
 *
 * @param count how many payments it adds.
 * @param totalMinor their amounts together, in paise.
 * @param byCategory per category in Choose category order, how many payments and how much.
 */
@Immutable
data class AddAllSummary(val count: Int, val totalMinor: Long, val byCategory: List<CategoryTotal>)

/** Which way a payment was decided. */
enum class ReviewDecision {
    /** Swiped right or "Add": counted in the person's spending. */
    Add,

    /** Swiped left or "Ignore": not counted. */
    Ignore,
}

/** Everything the person can do on Review payments. */
sealed interface ReviewPaymentsEvent {
    /** The payment [paymentId] was swiped, or its Add or Ignore button or TalkBack action was used. */
    data class Decide(val paymentId: Long, val decision: ReviewDecision) : ReviewPaymentsEvent

    /** "Undo": takes back the last decision (after Add all, every payment it added). */
    data object Undo : ReviewPaymentsEvent

    /** "Add all" in the top bar: opens the sheet. */
    data object OpenAddAll : ReviewPaymentsEvent

    /** "Add all N" on the sheet. */
    data object ConfirmAddAll : ReviewPaymentsEvent

    /** "Cancel" on the sheet, a tap on the scrim, or Back while it is open. */
    data object CancelAddAll : ReviewPaymentsEvent

    /** The category chip of [paymentId]: opens Choose category. Handled by the caller. */
    data class ChangeCategory(val paymentId: Long) : ReviewPaymentsEvent

    /**
     * "Split it instead" on [paymentId]: the payment becomes one of the run's splits (counted as added once it is
     * split), and the caller opens Split it.
     */
    data class Split(val paymentId: Long) : ReviewPaymentsEvent

    /** The back button. Handled by the caller. */
    data object Back : ReviewPaymentsEvent

    /** "Done" on All sorted, or Back there: the run ends, and the caller goes back to Home. */
    data object Done : ReviewPaymentsEvent
}
