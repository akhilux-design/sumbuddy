package com.expenses.app.ui.screens.you

import androidx.compose.runtime.Immutable
import com.expenses.app.ui.screens.onboarding.AccountItem
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Accounts and cards (design 04-you/01-accounts-and-cards): every account and card found in SMS, each with a
 * switch for tracking it, and the one being renamed.
 *
 * @param accounts the accounts, oldest first.
 * @param renaming the account being renamed with the name typed so far, or null when none is.
 */
@Immutable
data class AccountsUiState(
    val accounts: List<AccountItem>,
    val renaming: RenameDraft?,
)

/**
 * A name being typed for an account.
 *
 * @param accountId the account's id.
 * @param text the name typed so far; saving it blank goes back to the bank's name.
 */
@Immutable
data class RenameDraft(val accountId: Long, val text: String)

/** Everything the person can do on Accounts and cards. */
sealed interface AccountsEvent {
    /** The back button. */
    data object Back : AccountsEvent

    /**
     * An account's switch was flipped.
     *
     * @param accountId the account.
     * @param tracked true to track its payments, false to stop.
     */
    data class SetTracked(val accountId: Long, val tracked: Boolean) : AccountsEvent

    /**
     * An account row was tapped: rename it.
     *
     * @param accountId the account.
     */
    data class StartRename(val accountId: Long) : AccountsEvent

    /**
     * The name field changed.
     *
     * @param text the new text.
     */
    data class RenameChanged(val text: String) : AccountsEvent

    /** "Save" under the name field. */
    data object SaveRename : AccountsEvent

    /** "Cancel" under the name field. */
    data object CancelRename : AccountsEvent
}

/**
 * What we've read (design 04-you/04-what-we-ve-read): proof that nothing left the phone, the latest bank messages
 * used and how many other messages were skipped.
 *
 * @param sentOffPhone how many messages were sent off the phone. Always 0.
 * @param bankMessagesRead how many bank messages were used: payments, card bills and messages to review.
 * @param latestUsed the newest payments read from SMS, newest first, at most three.
 * @param today the current day, for the "Today" and "Yesterday" labels.
 * @param skipped how many messages were not about money (OTPs, promotions, chats) and were not kept.
 */
@Immutable
data class WhatWeReadUiState(
    val sentOffPhone: Int,
    val bankMessagesRead: Int,
    val latestUsed: List<UsedMessage>,
    val today: LocalDate,
    val skipped: Int,
)

/**
 * One bank message that was used, shown by the payment it reported.
 *
 * @param expenseId the payment's expense id; tapping the row opens it.
 * @param title who sent it: the bank or card issuer, for example "HDFC Bank", or null when unknown; the row then
 * says "Bank".
 * @param kind what sort of alert it was.
 * @param receivedAt when the message arrived, in the phone's time zone.
 * @param amountMinor the amount it reported, in paise.
 */
@Immutable
data class UsedMessage(
    val expenseId: Long,
    val title: String?,
    val kind: UsedMessageKind,
    val receivedAt: LocalDateTime,
    val amountMinor: Long,
)

/** What sort of bank alert a used message was. */
enum class UsedMessageKind {
    /** Money out of a bank account. */
    Debit,

    /** A card payment. */
    Card,

    /** Money in: a refund, cashback or money received. */
    Credit,
}

/** Everything the person can do on What we've read. */
sealed interface WhatWeReadEvent {
    /** The back button. */
    data object Back : WhatWeReadEvent

    /**
     * A used message was tapped: open its payment.
     *
     * @param expenseId the payment's expense id.
     */
    data class OpenPayment(val expenseId: Long) : WhatWeReadEvent

    /** "See the code" in the footer: open the public repository in the browser. */
    data object OpenSourceCode : WhatWeReadEvent
}

/**
 * Tracking start date (design 04-you/05-tracking-start-date): the current start and the earlier starts to choose
 * from. Moving the start earlier reads only the extra period; payments already listed are not added twice.
 *
 * @param today the current day; with no start yet, the latest day "Pick a date" offers.
 * @param currentStart the first day whose SMS are read now, or null when SMS were never read.
 * @param thisMonth the first day of this month, the start "This month" stands for.
 * @param lastThreeMonths the first day of the month two months back, the start "Last 3 months" stands for.
 * @param pickedDate the day chosen with "Pick a date", or null before one was picked.
 * @param selected the choice the person made, or null before they made one.
 * @param smsTrackingOn false in manual mode: nothing can be read, so the button is off and a note says why.
 * @param scan how reading the extra period is going.
 * @param pickingDate true while the system date picker is open.
 */
@Immutable
data class TrackingStartUiState(
    val today: LocalDate,
    val currentStart: LocalDate?,
    val thisMonth: LocalDate,
    val lastThreeMonths: LocalDate,
    val pickedDate: LocalDate?,
    val selected: StartChoice?,
    val smsTrackingOn: Boolean,
    val scan: OlderScanStatus,
    val pickingDate: Boolean = false,
) {
    /** The start the selected choice stands for, or null when nothing is selected or no date was picked. */
    val selectedStart: LocalDate?
        get() = when (selected) {
            StartChoice.ThisMonth -> thisMonth
            StartChoice.LastThreeMonths -> lastThreeMonths
            StartChoice.PickDate -> pickedDate
            null -> null
        }

    /** True when "Add older payments" can be tapped: an earlier start is chosen and nothing is being read. */
    val canAdd: Boolean
        get() {
            val start = selectedStart ?: return false
            return smsTrackingOn && isEarlier(start) && scan !is OlderScanStatus.Running
        }

    /** The latest day "Pick a date" offers: the day before the current start, or today without one. */
    val latestPickable: LocalDate
        get() = currentStart?.minusDays(1) ?: today

    /** True when [date] is before the current start, so choosing it brings in older payments. */
    fun isEarlier(date: LocalDate): Boolean = currentStart == null || date < currentStart
}

/** The three choices on Tracking start date. */
enum class StartChoice {
    /** From the first day of this month. */
    ThisMonth,

    /** From the first day of the month two months back. */
    LastThreeMonths,

    /** From a day the person picks. */
    PickDate,
}

/** How reading the extra period is going. */
@Immutable
sealed interface OlderScanStatus {
    /** Nothing is being read. */
    data object Idle : OlderScanStatus

    /**
     * Messages are being read.
     *
     * @param read how many were read so far.
     * @param total how many there are in the period.
     */
    data class Running(val read: Int, val total: Int) : OlderScanStatus

    /**
     * Reading finished.
     *
     * @param found how many payments were found; they wait on Home to be added.
     */
    data class Done(val found: Int) : OlderScanStatus

    /** Reading stopped before the end. The start date went back to what it was, so the person can try again. */
    data object Failed : OlderScanStatus
}

/** Everything the person can do on Tracking start date. */
sealed interface TrackingStartEvent {
    /** The back button. */
    data object Back : TrackingStartEvent

    /**
     * "This month" or "Last 3 months" was tapped.
     *
     * @param choice the choice.
     */
    data class Select(val choice: StartChoice) : TrackingStartEvent

    /** "Pick a date" was tapped: the system date picker opens. */
    data object PickDate : TrackingStartEvent

    /**
     * A day was chosen in the date picker.
     *
     * @param date the day.
     */
    data class DatePicked(val date: LocalDate) : TrackingStartEvent

    /** The date picker closed, with or without a pick. */
    data object DatePickerClosed : TrackingStartEvent

    /** "Add older payments". */
    data object AddOlderPayments : TrackingStartEvent
}
