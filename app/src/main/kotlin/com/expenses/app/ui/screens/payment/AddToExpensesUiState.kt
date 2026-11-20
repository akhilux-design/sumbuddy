package com.expenses.app.ui.screens.payment

import androidx.compose.runtime.Immutable
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.PaidWith
import java.time.YearMonth

/** What Add to expenses (design 02-everyday/05-add-to-expenses) shows. */
sealed interface AddToExpensesUiState {
    /** The payment is being read. */
    data object Loading : AddToExpensesUiState

    /** No payment has this id any more. */
    data object Gone : AddToExpensesUiState

    /**
     * The payment was already added or ignored, for example from the notification.
     *
     * @param expenseId the payment's id.
     * @param added true when it is in the expenses, false when it was ignored.
     */
    data class AlreadySorted(val expenseId: Long, val added: Boolean) : AddToExpensesUiState

    /**
     * The person just added the payment; the screen moves on to Expense added.
     *
     * @param expenseId the payment's id.
     */
    data class Added(val expenseId: Long) : AddToExpensesUiState

    /**
     * The form for a payment waiting for a decision.
     *
     * @param amountMinor the amount paid, in paise.
     * @param time when it was paid.
     * @param account the account it came from, or null.
     * @param paidWith how it was paid, shown when there is no account.
     * @param payee the "Paid to" field as typed; filled with the merchant from the SMS.
     * @param category the chosen category; the guess at first.
     * @param scope Just me or Family.
     * @param note the note field as typed.
     * @param alwaysThisCategory whether "Do this every time" is on.
     * @param month the month the payment counts in, for "Add to November".
     * @param saving true while the payment is being added; the button ignores more taps.
     */
    @Immutable
    data class Form(
        val amountMinor: Long,
        val time: PaymentTime,
        val account: PaymentAccount?,
        val paidWith: PaidWith,
        val payee: String,
        val category: Category,
        val scope: ExpenseScope,
        val note: String,
        val alwaysThisCategory: Boolean,
        val month: YearMonth,
        val saving: Boolean,
    ) : AddToExpensesUiState
}

/** Everything the person can do on Add to expenses. */
sealed interface AddToExpensesEvent {
    /** The "Paid to" field changed to [text]. */
    data class PayeeChanged(val text: String) : AddToExpensesEvent

    /** The category chip [category] was tapped. */
    data class CategoryChosen(val category: Category) : AddToExpensesEvent

    /** "More" was tapped: open Choose category. */
    data object MoreCategories : AddToExpensesEvent

    /** Just me or Family was tapped. */
    data class ScopeChosen(val scope: ExpenseScope) : AddToExpensesEvent

    /** The note field changed to [text]. */
    data class NoteChanged(val text: String) : AddToExpensesEvent

    /** "Do this every time" was switched to [on]. */
    data class AlwaysThisCategoryChanged(val on: Boolean) : AddToExpensesEvent

    /** "Add to <month>" was tapped. */
    data object Add : AddToExpensesEvent

    /** "Split it instead" was tapped: open Split it for the payment. Handled by the caller. */
    data object Split : AddToExpensesEvent

    /** "See expense" on an already added payment was tapped. */
    data object SeeExpense : AddToExpensesEvent

    /** The back button was tapped. */
    data object Back : AddToExpensesEvent
}
