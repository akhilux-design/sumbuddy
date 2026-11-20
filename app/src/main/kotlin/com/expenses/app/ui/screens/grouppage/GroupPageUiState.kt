package com.expenses.app.ui.screens.grouppage

import androidx.compose.runtime.Immutable
import com.expenses.app.data.category.Category
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.ui.components.AvatarColor
import com.expenses.splits.SplitMethod
import java.time.LocalDate
import java.time.YearMonth

/**
 * Everything the Group page shows (design 02-everyday/24-group-page and its states 22, 23, 40 to 43). A direct split
 * with one friend is the same page with the friend's name as its title.
 */
@Immutable
sealed interface GroupPageUiState {
    /**
     * The group's data is not complete on this phone yet (22's grey placeholders), or the group is not on this phone
     * yet and one sync was asked for (a Join group, an old notification).
     *
     * @param title the group's name, or empty while the group is not here.
     */
    data class Loading(val title: String) : GroupPageUiState

    /**
     * 23 "Could not load this group": the last pull failed, or the group is not here and the phone is offline.
     *
     * @param title the group's name, or empty while the group is not here.
     */
    data class CouldNotLoad(val title: String) : GroupPageUiState

    /** The group is no longer on this phone (left, removed, undone, deleted, signed out): the page leaves. */
    data object Gone : GroupPageUiState

    /**
     * The page.
     *
     * @param title the group's name; for a direct split, the friend's name.
     * @param isDirect true for a direct split with one friend (Group settings' row says less).
     * @param today today's date ("Today" on a row).
     * @param card the Balance Card.
     * @param notice the banner an online-only action left at the top of the body, or null.
     * @param joins the "{name} joined · Not {name}?" banners the owner can still undo (40), newest first.
     * @param undone the friends whose join was undone from this page (41), in the order undone. They stay until the
     * person leaves the page for another screen.
     * @param balances the other active members with the balance between them and the person.
     * @param notSaved the refused expense changes (43), newest first.
     * @param expenses this month's expenses, oldest first (by date, then by last change), as 24 and 42 draw them; an
     * expense with a refused change is left out (its "Not saved" row stands for it).
     * @param footer what the footer offers, or null for no footer (nobody owes anybody).
     * @param busy true while an online-only action waits for the server: taps on its buttons are ignored.
     */
    @Immutable
    data class Shown(
        val title: String,
        val isDirect: Boolean,
        val today: LocalDate,
        val card: GroupCard,
        val notice: GroupNotice? = null,
        val joins: List<JoinBanner> = emptyList(),
        val undone: List<String> = emptyList(),
        val balances: List<MemberBalanceRow> = emptyList(),
        val notSaved: List<NotSavedRow> = emptyList(),
        val expenses: List<MonthExpenseRow> = emptyList(),
        val footer: GroupFooter? = null,
        val busy: Boolean = false,
    ) : GroupPageUiState
}

/**
 * The Balance Card: "FLAT 302 OWES YOU ₹2,350" (Positive), "YOU OWE GOA TRIP ₹800" (Negative, 42), or all settled
 * (Neutral, not designed), with "3 people · 12 expenses in Nov".
 *
 * @param name the group's name as the label writes it.
 * @param netMinor the person's net in the group: above 0 the group owes them.
 * @param people active members, the person included.
 * @param expensesThisMonth expenses dated this month.
 * @param month this month.
 */
@Immutable
data class GroupCard(val name: String, val netMinor: Long, val people: Int, val expensesThisMonth: Int, val month: YearMonth)

/**
 * A banner that an online-only action left at the top of the body (docs/groups-build-plan.md section 1). None of
 * these is designed for this page.
 */
@Immutable
sealed interface GroupNotice {
    /** "You're offline" / "Check your connection and try again. Nothing is lost." */
    data object Offline : GroupNotice

    /** "Could not do that now" / "Try again in a moment." */
    data object Failed : GroupNotice

    /**
     * Remind sent a push: "Reminder sent" / "Sent to Rahul and Priya.".
     *
     * @param names the people reminded.
     */
    data class Reminded(val names: List<String>) : GroupNotice

    /** Remind reached nobody (reminded recently, or nothing owed now): "No reminder sent". */
    data object NotReminded : GroupNotice
}

/**
 * The owner's "{joiner} joined" / "Not {place}?" banner with Undo (40).
 *
 * @param memberId the place that was taken.
 * @param joiner the name of the app user who joined.
 * @param placeName the friend's name the place had before; the joiner's name when unknown.
 */
@Immutable
data class JoinBanner(val memberId: String, val joiner: String, val placeName: String)

/** How a member stands with the person (docs/server.md 6.4). */
enum class BalanceKind {
    /** They owe the person: "owes you +₹1,200". */
    OwesYou,

    /** The person owes them: "you owe -₹500". */
    YouOwe,

    /** No transfer and their net is 0: "all settled ₹0". */
    AllSettled,

    /** No transfer with the person, but their net is not 0: "settles with others ₹0" (42). */
    SettlesWithOthers,
}

/**
 * One member under Balances.
 *
 * @param memberId the member.
 * @param name their name.
 * @param kind how they stand with the person.
 * @param amountMinor the transfer between them, as a positive amount (0 for the two settled kinds).
 * @param avatarColor the colour behind their initial.
 * @param target what a tap opens (not designed): Settle up when the person owes them, Mark as paid when they owe the
 * person, nothing at 0.
 */
@Immutable
data class MemberBalanceRow(
    val memberId: String,
    val name: String,
    val kind: BalanceKind,
    val amountMinor: Long,
    val avatarColor: AvatarColor,
    val target: SettleTarget?,
)

/** A screen of the settle-up area opened for one member of this group. */
@Immutable
sealed interface SettleTarget {
    /** The member the screen is for. */
    val memberId: String

    /** Settle up: the person owes [memberId]. */
    data class SettleUp(override val memberId: String) : SettleTarget

    /** Mark as paid: [memberId] owes the person and paid them another way. */
    data class MarkAsPaid(override val memberId: String) : SettleTarget
}

/** What a "This month" row says after the date. */
@Immutable
sealed interface RowMethod {
    /** How it is divided: "equally", "amounts", "%", "shares". */
    data class Split(val method: SplitMethod) : RowMethod

    /** A rule made it: "splits itself". */
    data object Auto : RowMethod
}

/**
 * One of this month's expenses: "Swiggy", "Today · equally", "₹450", "You paid".
 *
 * @param id the group expense.
 * @param description what it was for.
 * @param category its category (the 3D icon).
 * @param date its date ("Today" when it is today).
 * @param method what follows the date.
 * @param amountMinor the whole amount.
 * @param paidByMe true when the person paid.
 * @param payerName who paid, when not the person.
 * @param opensDetail true when the person has a row of their own for it (they paid, or have a share), so a tap opens
 * Expense detail (not designed); false: not tappable.
 */
@Immutable
data class MonthExpenseRow(
    val id: String,
    val description: String,
    val category: Category,
    val date: LocalDate,
    val method: RowMethod,
    val amountMinor: Long,
    val paidByMe: Boolean,
    val payerName: String,
    val opensDetail: Boolean,
)

/**
 * Why a refused change cannot be tried again. Its "Not saved" row then has no Try again, and its note says this
 * instead, so a row never shows neither (not designed; 43 and 90 draw the rows that can be tried again).
 */
enum class NoRetryReason {
    /**
     * It names someone, as the payer or with a share, who has left the group since: the server would refuse it again.
     * "Someone in this expense has left the group. Add it again without them."
     */
    SomeoneLeft,

    /** Anything else that would be refused again as it is, or a change that cannot be read: "This cannot be tried again." */
    Other,
}

/**
 * A refused expense change (43): an Expense Row Kind=Review with Meta "Not saved", the note and Try again / Dismiss.
 *
 * @param id the refused change.
 * @param op what was refused: a create ("Not added to Flat 302."), an edit or a delete.
 * @param description what it was for.
 * @param category its category.
 * @param date its date.
 * @param method what follows the date.
 * @param amountMinor the whole amount.
 * @param noRetry null when the change can be tried again (Try again shows); else why it cannot: Try again is hidden
 * and the note gives the reason.
 */
@Immutable
data class NotSavedRow(
    val id: Long,
    val op: OutboxOp,
    val description: String,
    val category: Category,
    val date: LocalDate,
    val method: RowMethod,
    val amountMinor: Long,
    val noRetry: NoRetryReason? = null,
) {
    /** True when Try again shows. */
    val canRetry: Boolean get() = noRetry == null
}

/**
 * The footer (24, 42): "Settle up" for [settleUp], and "Remind" when someone owes the person.
 *
 * @param settleUp Settle up with the person the user owes most, or Mark as paid for the person who owes the user
 * most when the user owes nobody.
 * @param canRemind true when someone owes the person (42 has no Remind).
 */
@Immutable
data class GroupFooter(val settleUp: SettleTarget, val canRemind: Boolean)

/** Something the person did on the Group page. */
sealed interface GroupPageEvent {
    /** The back button. */
    data object Back : GroupPageEvent

    /** "Add" in the top bar. */
    data object Add : GroupPageEvent

    /** "Try again" on 23. */
    data object Reload : GroupPageEvent

    /** "Undo" on the joined banner of [memberId]. */
    data class UndoJoin(val memberId: String) : GroupPageEvent

    /** A Balance Row, or the footer's Settle up, that opens [target]. */
    data class Settle(val target: SettleTarget) : GroupPageEvent

    /** "Remind" in the footer. */
    data object Remind : GroupPageEvent

    /** A "This month" row. */
    data class OpenExpense(val groupExpenseId: String) : GroupPageEvent

    /** "Try again" on a "Not saved" row. */
    data class RetryChange(val id: Long) : GroupPageEvent

    /** "Dismiss" on a "Not saved" row. */
    data class DismissChange(val id: Long) : GroupPageEvent

    /** The "Group settings" row at the end of the body. */
    data object OpenSettings : GroupPageEvent

    /**
     * The person left the page for another screen of the app: Back, a screen opened from it, another tab. Not sent for
     * a theme change, a rotation, the screen going off or another app in front, where the page is still the screen the
     * person comes back to.
     */
    data object Left : GroupPageEvent
}

/** A screen the Group page's ViewModel opens once. */
sealed interface GroupPageEffect {
    /** Remind on WhatsApp for [memberId], a friend without the app. */
    data class RemindOnWhatsApp(val memberId: String) : GroupPageEffect

    /** Expense detail of the person's own row [expenseId]. */
    data class ExpenseDetail(val expenseId: Long) : GroupPageEffect

    /** Split it for the person's payment [expenseId] (Try again on a refused split of it). */
    data class SplitPayment(val expenseId: Long) : GroupPageEffect

    /** Sign in: the session ended. */
    data object SignIn : GroupPageEffect
}
