package com.expenses.app.ui.screens.payment

import androidx.compose.runtime.Immutable
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.parser.Direction
import com.expenses.splits.SplitMethod
import java.time.YearMonth

/** What Expense detail (design 02-everyday/08-expense-detail, 47-expense-detail-delete-split) shows. */
sealed interface ExpenseDetailUiState {
    /** The expense is being read. */
    data object Loading : ExpenseDetailUiState

    /** No expense has this id. */
    data object Gone : ExpenseDetailUiState

    /** The person deleted the expense from the month; the caller goes back. */
    data object Removed : ExpenseDetailUiState

    /**
     * The expense, and the edit form while the person edits it.
     *
     * @param details the stored expense.
     * @param edit the edit form, or null when not editing.
     * @param confirmingDelete true while "Delete this expense?" (or, for a split, "Delete Swiggy from Flat 302?") is
     * asked.
     * @param split the group expense this row is part of, or null when it is not split.
     */
    @Immutable
    data class Shown(
        val details: ExpenseDetails,
        val edit: ExpenseEdit?,
        val confirmingDelete: Boolean,
        val split: ExpenseSplit? = null,
    ) : ExpenseDetailUiState {
        /** True when "Split this expense" shows: a counted rupee debit that is not part of a split. */
        val canSplit: Boolean
            get() = details.status == ExpenseStatus.ADDED && details.direction == Direction.DEBIT &&
                details.currency == Expense.INR && !details.linked
    }
}

/**
 * The split a payment is part of, as Expense detail's split row says it (design 02-everyday/47): "Flat 302" / "Split
 * equally · your share ₹150".
 *
 * @param groupName the group's name; for a split with one friend, the friend's name.
 * @param method how it is divided.
 * @param myShareMinor the person's share in paise, the part that counts in the month.
 * @param splitsItself true when payments to this merchant split themselves with this group ("· splits itself"); a
 * tap on the row then opens Same split?, where it can be turned off (not designed).
 * @param paidByName who paid when it was not the person (a friend's expense), else null.
 */
@Immutable
data class ExpenseSplit(
    val groupName: String,
    val method: SplitMethod,
    val myShareMinor: Long,
    val splitsItself: Boolean,
    val paidByName: String?,
)

/**
 * One stored expense as Expense detail shows it.
 *
 * @param source read from SMS or typed in by hand. Only a typed-in amount can be changed.
 * @param status ADDED (counted), IGNORED (not counted) or DETECTED (still waiting for a decision).
 * @param direction DEBIT for money out, CREDIT for money in, which never counts.
 * @param merchant who was paid, or who paid for a credit, or null.
 * @param amountMinor the whole amount, in minor units of [currency].
 * @param currency ISO code; only "INR" is shown with the rupee sign.
 * @param category its category.
 * @param scope Just me or Family.
 * @param note the person's note, or null.
 * @param time when it was paid.
 * @param account the account it came from, or null.
 * @param paidWith how it was paid, shown when there is no account.
 * @param originalMessage the SMS text it was read from, shown on this phone only, or null.
 * @param month the month it counts in.
 * @param linked true when it is part of a split (it has a group expense), even one whose group is no longer on this
 * phone: it cannot be split again.
 * @param inFamilyMonth true while it is shared in the family month (it has a family expense): the delete question says
 * it leaves the family month too (06-family/26).
 */
@Immutable
data class ExpenseDetails(
    val source: ExpenseSource,
    val status: ExpenseStatus,
    val direction: Direction,
    val merchant: String?,
    val amountMinor: Long,
    val currency: String,
    val category: Category,
    val scope: ExpenseScope,
    val note: String?,
    val time: PaymentTime,
    val account: PaymentAccount?,
    val paidWith: PaidWith,
    val originalMessage: String?,
    val month: YearMonth,
    val linked: Boolean = false,
    val inFamilyMonth: Boolean = false,
)

/**
 * The edit form of Expense detail.
 *
 * @param amountText the amount field as typed, or null when the amount came from SMS, or the expense is split (the
 * amount is the group's), and cannot be changed here.
 * @param amountError true when the typed amount is not a valid amount; the field says so.
 * @param payee the "Paid to" field as typed.
 * @param note the note field as typed.
 * @param scope Just me or Family; not shown for a split.
 * @param category the category picked in the form; stored only by Save changes.
 * @param saving true while the changes are stored.
 * @param refusedSubmits how many times Save changes was refused for the amount in this edit; each one shakes the amount
 * and plays the reject haptic.
 */
@Immutable
data class ExpenseEdit(
    val amountText: String?,
    val amountError: Boolean,
    val payee: String,
    val note: String,
    val scope: ExpenseScope,
    val category: Category,
    val saving: Boolean,
    val refusedSubmits: Int = 0,
)

/** Everything the person can do on Expense detail. */
sealed interface ExpenseDetailEvent {
    /** "Edit" in the top bar. */
    data object Edit : ExpenseDetailEvent

    /** "Cancel" in the top bar, the back arrow or the system Back while editing: the changes are dropped. */
    data object CancelEdit : ExpenseDetailEvent

    /** The amount field changed to [text]. */
    data class AmountChanged(val text: String) : ExpenseDetailEvent

    /** The "Paid to" field changed to [text]. */
    data class PayeeChanged(val text: String) : ExpenseDetailEvent

    /** The note field changed to [text]. */
    data class NoteChanged(val text: String) : ExpenseDetailEvent

    /** Just me or Family was tapped. */
    data class ScopeChosen(val scope: ExpenseScope) : ExpenseDetailEvent

    /** "Save changes". */
    data object SaveEdit : ExpenseDetailEvent

    /** A category was picked while editing (a chip, or a row on Choose category); stored by Save changes. */
    data class CategoryChosen(val category: Category) : ExpenseDetailEvent

    /** The category row or "More" was tapped: open Choose category. */
    data object ChangeCategory : ExpenseDetailEvent

    /** "Split this expense": open Split it. Handled by the caller. */
    data object Split : ExpenseDetailEvent

    /** The split row of a split that splits itself: open Same split?, where it can be turned off. Handled by the caller. */
    data object OpenSplitRule : ExpenseDetailEvent

    /** "Delete expense": ask first. */
    data object Delete : ExpenseDetailEvent

    /**
     * "Delete" in the question: the expense stops counting in its month. A split is deleted for everyone in the group
     * first ("Delete for everyone").
     */
    data object ConfirmDelete : ExpenseDetailEvent

    /** "Keep it" in the question, or the question was dismissed. */
    data object DismissDelete : ExpenseDetailEvent

    /** "Count it again" on a deleted expense. */
    data object CountAgain : ExpenseDetailEvent

    /** "Add or ignore it" on a payment still waiting for a decision: open Add to expenses. */
    data object Review : ExpenseDetailEvent

    /** The back button. */
    data object Back : ExpenseDetailEvent
}
