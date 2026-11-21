package com.expenses.app.ui.screens.cardbill

import androidx.compose.runtime.Immutable
import com.expenses.app.cardbill.BillState
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.screens.grouppage.GroupNotice
import java.time.LocalDate

/** What the Card bill view shows (design 03-getting-paid-back/04, 20 to 26; docs/card-bills.md 2.3). */
@Immutable
sealed interface CardBillUiState {
    /** Before the first read: the canvas only (the data is on the phone, so this lasts a moment). */
    data object Loading : CardBillUiState

    /** The bill is gone (deleted, its card switched off or gone, no card): the view leaves for Home (D23). */
    data object Gone : CardBillUiState

    /**
     * The bill.
     *
     * @param cardName the card's name ("HDFC Millennia"), or null for "Credit card".
     * @param last4 the card's last digits, or null to leave "•• 2217" out.
     * @param status where the bill stands today: the line over the amount.
     * @param totalMinor the bill, in paise.
     * @param body what follows the card; see [CardBillBody].
     * @param notice the result banner of Remind everyone at the top of the body, or null.
     * @param busy true while Remind everyone runs: its button is disabled.
     */
    data class Shown(
        val cardName: String?,
        val last4: String?,
        val status: BillState,
        val totalMinor: Long,
        val body: CardBillBody,
        val notice: GroupNotice? = null,
        val busy: Boolean = false,
    ) : CardBillUiState {
        /** True when friends owe part of the bill: the footer offers Remind everyone. */
        val canRemind: Boolean get() = body is CardBillBody.FriendsOwe
    }
}

/** What follows the card in the Card bill view. */
@Immutable
sealed interface CardBillBody {
    /**
     * Friends owe part of the bill (04, 22, 23): the insight card and WHO OWES WHAT.
     *
     * @param friendsMinor what friends owe of it, in paise (the heading; never cut, D11).
     * @param totalMinor the bill, in paise: the bar is [friendsMinor] of it, at most full (D12).
     * @param oneFriend the friend's first name when only one friend owes part: "Rahul owes you ₹4,100 of this".
     * @param caption the line under the bar.
     * @param rows one row per friend, the largest first.
     */
    data class FriendsOwe(
        val friendsMinor: Long,
        val totalMinor: Long,
        val oneFriend: String?,
        val caption: CardBillCaption,
        val rows: List<CardBillRow>,
    ) : CardBillBody

    /** Signed in and nobody owes part of it (20): "Nobody owes you for this bill". */
    data object NobodyOwes : CardBillBody

    /** Signed out (21): You's "Sign in with Google" card. */
    data object SignedOut : CardBillBody
}

/** The line under the bar. */
@Immutable
sealed interface CardBillCaption {
    /** The person's own part. */
    val ownMinor: Long

    /** "Get it back before 21 Nov and you only pay your own ₹16,100." */
    data class GetBackBy(val dueDate: LocalDate, override val ownMinor: Long) : CardBillCaption

    /** No due date: "Get it back before it is due and you only pay your own ₹16,100." */
    data class GetBackNoDate(override val ownMinor: Long) : CardBillCaption

    /** Past the due date (22): "Your own part of it is ₹16,100." */
    data class OwnIs(override val ownMinor: Long) : CardBillCaption

    /** Paid (23): "Your own part of it was ₹16,100." */
    data class OwnWas(override val ownMinor: Long) : CardBillCaption
}

/**
 * One friend under WHO OWES WHAT: a Balance Row "Rahul" / "Goa trip · 3 expenses" / "+₹4,100".
 *
 * @param key the friend across groups.
 * @param name the friend's first name (whole where two clash).
 * @param initial the letter in the avatar.
 * @param avatar the avatar's colour.
 * @param owedMinor what they owe of this bill, in paise.
 * @param groupName the group's name when it is all in one named group; null for a direct split or several groups.
 * @param groupCount in how many groups they owe part of it.
 * @param expenseCount how many of the bill's split payments they have a share of.
 * @param groupId the group a tap opens: the one where their part is largest (D20).
 */
@Immutable
data class CardBillRow(
    val key: String,
    val name: String,
    val initial: String,
    val avatar: AvatarColor,
    val owedMinor: Long,
    val groupName: String?,
    val groupCount: Int,
    val expenseCount: Int,
    val groupId: String,
)

/** What the person can do on the Card bill view. */
sealed interface CardBillEvent {
    /** Back, on the top bar or the system's. */
    data object Back : CardBillEvent

    /** Remind everyone: a push to friends on the app, then WhatsApp for the friend without it who owes most (D19). */
    data object RemindEveryone : CardBillEvent

    /** A friend's row: their Group page (D20). */
    data class OpenGroup(val groupId: String) : CardBillEvent

    /** The "Sign in with Google" card (signed out). */
    data object SignIn : CardBillEvent
}

/** Screens the view opens by itself, once each. */
sealed interface CardBillEffect {
    /** Remind on WhatsApp for the friend [memberId] of [groupId]. */
    data class RemindOnWhatsApp(val groupId: String, val memberId: String) : CardBillEffect

    /** The session ended: Sign in. */
    data object SignIn : CardBillEffect
}
