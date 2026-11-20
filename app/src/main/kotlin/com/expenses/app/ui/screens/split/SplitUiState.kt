package com.expenses.app.ui.screens.split

import androidx.compose.runtime.Immutable
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.screens.payment.PaymentAccount
import com.expenses.splits.SplitMethod
import java.time.LocalDate
import java.time.YearMonth

/** What Split it (design 02-everyday/09-split-details, 10, 11) is for: a payment of the person's, or one typed in. */
sealed interface SplitSource {
    /** A payment read from SMS or added by hand ([com.expenses.app.ui.nav.SplitPayment]). */
    data class Payment(val expenseId: Long) : SplitSource

    /**
     * An expense typed in on Add expense ([com.expenses.app.ui.nav.SplitTyped]); null when the route's draft could
     * not be read.
     */
    data class Typed(val draft: TypedExpense?) : SplitSource
}

/** Why Split it has nothing to split (not designed). */
enum class CannotSplitReason {
    /** The payment is not on this phone any more. */
    GONE,

    /** The payment is already part of a split. */
    ALREADY_SPLIT,

    /** Money in, or a payment in another currency: groups hold rupee spending only. */
    NOT_SPLITTABLE,
}

/** What Split it shows. */
sealed interface SplitUiState {
    /** The payment and the person's groups are being read. */
    data object Loading : SplitUiState

    /** Nothing can be split here; see [reason]. */
    data class CannotSplit(val reason: CannotSplitReason) : SplitUiState

    /**
     * The split being set up.
     *
     * @param header the payment as its Expense Row shows it.
     * @param groups the "With" chips: the person's groups, and the one chosen when it is a direct split.
     * @param chosenGroupId the chosen group, or null before one is chosen.
     * @param chosenGroupName its name ("Flat 302"; a direct split's friend), for "Do this every time".
     * @param canChangeGroup false when someone else paid a typed expense: the group is theirs, so no other chip and
     * no "+ New" (not designed).
     * @param method Equally, Amounts, % or Shares.
     * @param people the chosen group's people, the person first.
     * @param note the line under the people, or null before a group is chosen.
     * @param month the month the person's share counts in.
     * @param rememberRule "Do this every time": null hides the row (a typed expense, a payment that names no
     * merchant, or no group yet).
     * @param inputs what the split sends when "Split ₹450" is tapped; null keeps the button off.
     * @param saving true while the split is stored; the button ignores more taps.
     */
    @Immutable
    data class Form(
        val header: SplitHeader,
        val groups: List<GroupChip>,
        val chosenGroupId: String?,
        val chosenGroupName: String,
        val canChangeGroup: Boolean,
        val method: SplitMethod,
        val people: List<SplitPerson>,
        val note: SplitNote?,
        val month: YearMonth,
        val rememberRule: Boolean?,
        val inputs: Map<String, Long?>?,
        val saving: Boolean,
    ) : SplitUiState {
        /** True when "Split ₹450" can be tapped. */
        val canSplit: Boolean get() = inputs != null && chosenGroupId != null && people.size > 1 && !saving
    }
}

/**
 * The payment at the top of Split it, as an Expense Row Kind=Shared: "Swiggy" / "Today · HDFC card ••4521" / "₹450" /
 * "You paid".
 *
 * @param title the payee or what it was for; null when nothing names it ("Unknown merchant").
 * @param category its category, for the icon.
 * @param amountMinor the whole amount in paise.
 * @param date the day it was paid.
 * @param today today's date, for "Today" and "Yesterday".
 * @param account the account it came from, or null.
 * @param paidWith how it was paid, shown when there is no account.
 * @param payerName who paid when it was not the person (a typed expense), else null for "You paid".
 * @param typed true for an expense typed in: with no "what for", the row reads the category's name.
 */
@Immutable
data class SplitHeader(
    val title: String?,
    val category: Category,
    val amountMinor: Long,
    val date: LocalDate,
    val today: LocalDate,
    val account: PaymentAccount?,
    val paidWith: PaidWith,
    val payerName: String?,
    val typed: Boolean,
)

/**
 * One "With" chip.
 *
 * @param id the group.
 * @param name its name; for a direct split, the friend's name.
 */
@Immutable
data class GroupChip(val id: String, val name: String)

/**
 * One person under "How", as a Balance Row.
 *
 * @param memberId their member id in the chosen group.
 * @param name their name ("You" is written by the screen for the person).
 * @param initial the letter in the avatar; for the person, the first letter of their own name.
 * @param isMe true for the person.
 * @param isPayer true for whoever paid.
 * @param shareMinor their part in paise as the typed parts give it.
 * @param input the typed part (rupees, percent or shares); empty for Equally.
 * @param avatar the colour of the avatar.
 */
@Immutable
data class SplitPerson(
    val memberId: String,
    val name: String,
    val initial: String,
    val isMe: Boolean,
    val isPayer: Boolean,
    val shareMinor: Long,
    val input: String,
    val avatar: AvatarColor,
)

/** Everything the person can do on Split it. */
sealed interface SplitEvent {
    /** A group's "With" chip was tapped. */
    data class GroupPicked(val groupId: String) : SplitEvent

    /** "+ New": open Choose people. Handled by the caller. */
    data object NewGroup : SplitEvent

    /** Choose people handed back [groupId]. */
    data class GroupChosen(val groupId: String) : SplitEvent

    /** Equally, Amounts, % or Shares was tapped. */
    data class MethodPicked(val method: SplitMethod) : SplitEvent

    /** The part of [memberId] was typed as [text]. */
    data class PartChanged(val memberId: String, val text: String) : SplitEvent

    /** "Do this every time" was switched to [on]. */
    data class RememberChanged(val on: Boolean) : SplitEvent

    /** "Split ₹450". */
    data object Split : SplitEvent

    /** The back button. Handled by the caller. */
    data object Back : SplitEvent
}

/**
 * The split is made; the caller moves on.
 *
 * @param groupExpenseId the group expense just made.
 * @param sameSplitOffer true when Same split? (02-everyday/14) shows in place of Split. Sorted.: the payment was
 * split the way its kept rule says, and that rule does not split by itself yet.
 * @param expenseId the payment, for Same split?; null for a typed expense.
 * @param priorStatus the payment's status before the split, for Undo on Split. Sorted.; null for a typed expense.
 */
data class SplitDone(val groupExpenseId: String, val sameSplitOffer: Boolean, val expenseId: Long?, val priorStatus: ExpenseStatus? = null)

/** The first letter of [name] in capitals, for an avatar; "?" for a name with no letter. */
internal fun initialOf(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
