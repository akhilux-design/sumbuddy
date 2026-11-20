package com.expenses.app.ui.screens.family

import androidx.compose.runtime.Immutable
import com.expenses.app.groups.data.FamilyPersonTotal
import com.expenses.app.groups.data.FamilyRow
import com.expenses.app.ui.screens.activity.ActivityDate
import com.expenses.app.ui.screens.month.MonthCategory
import com.expenses.app.ui.screens.signin.ActionProblem
import java.time.YearMonth

// What the family screens show and what the person does on them (docs/family.md 8.4). Join Family's states are part
// of Join group's (`JoinGroupUiState.ReadyFamily`, `JoinGroupUiState.AlreadyInFamily`).

// ---- Set up Family (06-family/00, 09 to 12) ----

/**
 * Set up Family.
 *
 * @param name the text in "Family name" ("Home" at first).
 * @param nameError true when the name is blank once cleaned: "That does not look right." under the field (10), and
 * "Invite partner" is off.
 * @param earlierCount how many of this phone's Family expenses setting up shares; above 0 the fifth fact "Earlier
 * Family expenses" shows (09).
 * @param busy true while "Invite partner" runs (the family is set up, then the link is made): taps and Back wait.
 * @param problem the banner at the top of the body when it did not happen: offline (11) or refused (12); null for none.
 * @param problemCount how many times [problem] was shown, so each refused tap plays `haptic/reject`.
 */
@Immutable
data class SetUpFamilyUiState(
    val name: String,
    val nameError: Boolean = false,
    val earlierCount: Int = 0,
    val busy: Boolean = false,
    val problem: ActionProblem? = null,
    val problemCount: Int = 0,
)

/** What the person does on Set up Family. */
sealed interface SetUpFamilyEvent {
    /** Typed in "Family name". */
    data class NameChanged(val text: String) : SetUpFamilyEvent

    /** "Invite partner". */
    data object Invite : SetUpFamilyEvent

    /** The back button, or Back: ignored while "Invite partner" runs. */
    data object Back : SetUpFamilyEvent
}

/** What Set up Family asks its route to do, once each. */
sealed interface SetUpFamilyEffect {
    /** Open the system share sheet with [invite]'s message. [Done] follows. */
    data class Share(val invite: FamilyInvite) : SetUpFamilyEffect

    /** The family is set up and the link was handed to the share sheet: Home on the Family side (waiting). */
    data object Done : SetUpFamilyEffect

    /** A family is already on this phone or in the account (made on another phone): Home on the Family side. */
    data object OpenFamily : SetUpFamilyEffect
}

// ---- Family settings (06-family/17 to 24) ----

/** What Family settings shows. */
@Immutable
sealed interface FamilySettingsUiState {
    /** The family is being read. */
    data object Loading : FamilySettingsUiState

    /** The family is no longer on this phone (removed, or a sync took it away): the screen leaves. */
    data object Gone : FamilySettingsUiState

    /** The person left the family with Leave Family: Home opens on the Family side (now with no family). */
    data object Left : FamilySettingsUiState

    /**
     * Family settings.
     *
     * @param familyName the family's stored name, as the leave row, the dialogs and the sheet write it.
     * @param nameField the text in "Family name".
     * @param nameError true when the name left blank is not saved ("That does not look right.").
     * @param me the person's whole name ("Arjun Mehta" / "You").
     * @param partner the second row under People.
     * @param busy true while an online-only action waits for the server: further taps are ignored.
     * @param problem the banner at the top of the body when an online-only action did not happen (23, 24), or null.
     * @param problemCount how many times [problem] was shown, so each refused tap plays `haptic/reject`.
     * @param confirm the confirm dialog open (21, 22), or null.
     * @param inviteSheet true while the invite sheet is open (20).
     */
    @Immutable
    data class Shown(
        val familyName: String,
        val nameField: String,
        val nameError: Boolean = false,
        val me: String,
        val partner: FamilyPartnerRow,
        val busy: Boolean = false,
        val problem: ActionProblem? = null,
        val problemCount: Int = 0,
        val confirm: FamilyConfirm? = null,
        val inviteSheet: Boolean = false,
    ) : FamilySettingsUiState
}

/** The partner's row under People. */
@Immutable
sealed interface FamilyPartnerRow {
    /**
     * The partner is in (17): Icon/user "Meera Iyer" / "Partner".
     *
     * @param memberId their member.
     * @param name their whole name.
     * @param firstName their first name, as the remove confirm writes it ("Remove Meera from Home?").
     * @param canRemove true for the owner: the row opens the remove confirm (22). Otherwise it is not tappable.
     */
    data class Joined(val memberId: String, val name: String, val firstName: String, val canRemove: Boolean) : FamilyPartnerRow

    /** An invite is out (18): Icon/user-plus "Your partner" / "Invite sent · not joined yet"; the row opens the sheet (20). */
    data object Invited : FamilyPartnerRow

    /** Nobody and no invite (19): Icon/plus "Invite partner"; the row adds an invite, makes the link and shares it. */
    data object None : FamilyPartnerRow
}

/** A confirm dialog of Family settings. */
@Immutable
sealed interface FamilyConfirm {
    /** "Leave Home?" (21). */
    data object Leave : FamilyConfirm

    /**
     * "Remove Meera from Home?" (22), the owner only.
     *
     * @param memberId the partner.
     * @param firstName their first name.
     */
    data class Remove(val memberId: String, val firstName: String) : FamilyConfirm
}

/** What the person does on Family settings. */
sealed interface FamilySettingsEvent {
    /** The back button. */
    data object Back : FamilySettingsEvent

    /** Typed in "Family name". */
    data class NameChanged(val text: String) : FamilySettingsEvent

    /** Left "Family name" (focus moved away, Done on the keyboard): a changed name is saved. */
    data object NameDone : FamilySettingsEvent

    /** The partner's row: remove confirm (owner), the invite sheet, or Invite partner. */
    data object PartnerTapped : FamilySettingsEvent

    /** "Send link again" on the invite sheet. */
    data object SendLinkAgain : FamilySettingsEvent

    /** "Cancel invite" on the invite sheet: the link stops working. */
    data object CancelInvite : FamilySettingsEvent

    /** "Close", Back or the scrim on the invite sheet. */
    data object CloseSheet : FamilySettingsEvent

    /** "Leave Family". */
    data object Leave : FamilySettingsEvent

    /** The confirm dialog's first choice ("Leave Family", "Remove"). */
    data object Confirm : FamilySettingsEvent

    /** The confirm dialog's second choice, Back or the scrim ("Stay", "Keep"). */
    data object CloseConfirm : FamilySettingsEvent
}

/** What Family settings asks its route to do, once each. */
sealed interface FamilySettingsEffect {
    /** Open the system share sheet with [invite]'s message. */
    data class Share(val invite: FamilyInvite) : FamilySettingsEffect
}

// ---- Month view (Family) (06-family/27) ----

/**
 * Month view (Family): the family's month next to the month before, by person, by category, and every family expense
 * by day.
 *
 * @param month the month shown; the title reads "Home · November 2026".
 * @param familyName the family's name.
 * @param totalMinor what the family spent in [month] ("Family spent").
 * @param lastMonthMinor what it spent the month before ("Last month").
 * @param people the person first, then the partner, each with their part.
 * @param categories every category of the month, largest first, with its share.
 * @param days the month's family expenses, newest day first.
 * @param canGoNext false for the current month.
 */
@Immutable
data class FamilyMonthUiState(
    val month: YearMonth,
    val familyName: String,
    val totalMinor: Long,
    val lastMonthMinor: Long,
    val people: List<FamilyPersonTotal>,
    val categories: List<MonthCategory>,
    val days: List<FamilyDay>,
    val canGoNext: Boolean,
)

/**
 * One day of Month view (Family).
 *
 * @param date the heading: Today, Yesterday or the date.
 * @param rows the family expenses of that day.
 */
@Immutable
data class FamilyDay(val date: ActivityDate, val rows: List<FamilyRow>)

/** What the person does on Month view (Family). */
sealed interface FamilyMonthEvent {
    /** The back button. */
    data object Back : FamilyMonthEvent

    /** "Previous month". */
    data object PreviousMonth : FamilyMonthEvent

    /** "Next month": only before the current month. */
    data object NextMonth : FamilyMonthEvent

    /** One of the person's own rows: open Expense detail of [expenseId]. The partner's rows are not tappable. */
    data class RowTapped(val expenseId: Long) : FamilyMonthEvent
}
