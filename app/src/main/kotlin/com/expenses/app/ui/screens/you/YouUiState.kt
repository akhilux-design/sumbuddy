package com.expenses.app.ui.screens.you

import androidx.compose.runtime.Immutable
import com.expenses.app.ui.components.AvatarColor
import java.time.LocalDate

/**
 * Everything the You tab shows: who the person is and whether they are signed in, how tracking is set up, what has
 * been read on this phone, and the family. MORE always holds Import from Splitwise; Plus and "See the code" are left
 * out (docs/product.md).
 *
 * @param profile the person's name and avatar at the top.
 * @param signedIn true when this phone is signed in: the line under the name reads "Signed in with Google" and opens
 * Account (04-you/00). False: it reads "Not signed in" and the "Sign in with Google" card follows (04-you/06).
 * @param smsTrackingOn whether payments are read from bank SMS. Shown by the switch on the "SMS auto-tracking" row.
 * @param trackedAccountCount how many accounts and cards are being tracked, for example 4.
 * @param remindersOn whether the app sends reminders before a card bill is due.
 * @param reminderDaysBefore how many days before the due date the reminders go out, for example 3.
 * @param trackingStartDate the first day whose SMS are read, or null when SMS were never read.
 * @param messagesRead how many bank messages have been used on this phone, for example 214.
 * @param messagesSent how many of them were sent off this phone. Always 0; shown so the person can see it.
 * @param family the family on this phone, for the row under FAMILY (docs/family.md 1.1).
 */
@Immutable
data class YouUiState(
    val profile: YouProfile,
    val signedIn: Boolean,
    val smsTrackingOn: Boolean,
    val trackedAccountCount: Int,
    val remindersOn: Boolean,
    val reminderDaysBefore: Int,
    val trackingStartDate: LocalDate?,
    val messagesRead: Int,
    val messagesSent: Int,
    val family: YouFamily = YouFamily.None,
)

/** The family as the FAMILY row on You says it. */
@Immutable
sealed interface YouFamily {
    /** No family on this phone: "Share a monthly total with your partner"; the row opens Set up Family. */
    data object None : YouFamily

    /** An invite is out: "Home · waiting for your partner"; the row opens Family settings. */
    data class Waiting(val name: String) : YouFamily

    /** Nobody else and no invite: "Home · just you". */
    data class Alone(val name: String) : YouFamily

    /** The person and the partner: "Home · Arjun and Meera". */
    data class Together(val name: String, val me: String, val partner: String) : YouFamily
}

/**
 * The person shown at the top of the You tab.
 *
 * @param name the name as the person entered it, for example "Arjun", or null when they gave none.
 * @param avatarColor the colour of the avatar circle.
 */
@Immutable
data class YouProfile(
    val name: String?,
    val avatarColor: AvatarColor,
)

/** Everything the person can tap on the You tab. */
sealed interface YouEvent {
    /** The name at the top was tapped while signed in: open Account. */
    data object OpenAccount : YouEvent

    /** The "Sign in with Google" card was tapped (signed out): open Sign in. */
    data object SignIn : YouEvent

    /** The "SMS auto-tracking" row was tapped (not its switch): open Accounts and cards. */
    data object OpenAccountsAndCards : YouEvent

    /**
     * The switch on the "SMS auto-tracking" row was flipped.
     *
     * @param on the new state: true to read bank SMS (which may first ask for SMS access), false to stop.
     */
    data class SetSmsTracking(val on: Boolean) : YouEvent

    /**
     * The "Automatic reminders" row was tapped, which flips its switch.
     *
     * @param on the new state: true to send reminders before card bills are due.
     */
    data class SetReminders(val on: Boolean) : YouEvent

    /** The "Tracking start date" row was tapped: open Tracking start date. */
    data object OpenTrackingStartDate : YouEvent

    /** The "What we've read" row was tapped: open What we've read. */
    data object OpenWhatWeRead : YouEvent

    /** The "Import from Splitwise" row under MORE was tapped: open Import from Splitwise (Sign in first if needed). */
    data object OpenImport : YouEvent

    /** The "Family" row: Set up Family without a family, Family settings with one (Sign in first if needed). */
    data object OpenFamily : YouEvent
}
