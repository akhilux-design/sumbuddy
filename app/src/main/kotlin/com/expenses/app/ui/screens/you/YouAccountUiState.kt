package com.expenses.app.ui.screens.you

import androidx.compose.runtime.Immutable
import com.expenses.app.ui.screens.signin.ActionProblem

/** The confirm dialog Account shows over itself. */
enum class YouAccountDialog {
    /** "Delete your account and all data? This cannot be undone." (04-you/03). */
    DELETE,

    /** "{n} changes are not sent yet" after Sign out could not send them (04-you/08). */
    UNSENT_CHANGES,
}

/** What Account is doing while the person waits. */
enum class YouAccountWork {
    /** Saving the profile. Back waits for it. */
    SAVING,

    /** Signing out (sending what waits first, up to 10 s). Back waits for it. */
    SIGNING_OUT,

    /** Deleting the account. Back waits for it. */
    DELETING,
}

/**
 * What Account shows (design 04-you/02-account; 03, 07, 08).
 *
 * @param name the Name field.
 * @param phone the "Phone number (optional)" field.
 * @param upiId the "UPI ID (optional)" field.
 * @param nameInvalid true shows the Name field in its error state ("That does not look right."): it is empty, or the
 * server refused it.
 * @param phoneInvalid true shows the phone field in its error state ("That does not look right.").
 * @param upiInvalid true shows the UPI ID field in its error state ("That does not look like a UPI ID.", 07).
 * @param problem the banner at the top of the body when saving or deleting did not happen, or null.
 * @param problemCount how many times a save or delete was refused with [problem], so each refused tap plays
 * `haptic/reject`, also when the banner was already there.
 * @param dialog the confirm dialog over the screen, or null.
 * @param unsentCount how many changes wait to be sent, for "{n} changes are not sent yet".
 * @param working what runs now, or null; taps wait for it.
 */
@Immutable
data class YouAccountUiState(
    val name: String = "",
    val phone: String = "",
    val upiId: String = "",
    val nameInvalid: Boolean = false,
    val phoneInvalid: Boolean = false,
    val upiInvalid: Boolean = false,
    val problem: ActionProblem? = null,
    val problemCount: Int = 0,
    val dialog: YouAccountDialog? = null,
    val unsentCount: Int = 0,
    val working: YouAccountWork? = null,
) {
    /** True while a save, sign-out or delete runs. */
    val busy: Boolean get() = working != null
}

/** What the person does on Account. */
sealed interface YouAccountEvent {
    /** The Name field changed. */
    data class NameChanged(val name: String) : YouAccountEvent

    /** The phone field changed. */
    data class PhoneChanged(val phone: String) : YouAccountEvent

    /** The UPI ID field changed. */
    data class UpiIdChanged(val upiId: String) : YouAccountEvent

    /** Save, or the keyboard's Done key in the UPI ID field. */
    data object Save : YouAccountEvent

    /** The "Sign out" row. */
    data object SignOut : YouAccountEvent

    /** "Sign out anyway" in the unsent changes dialog. */
    data object SignOutAnyway : YouAccountEvent

    /** The "Delete account and data" row: ask first. */
    data object Delete : YouAccountEvent

    /** "Delete everything" in the delete dialog. */
    data object ConfirmDelete : YouAccountEvent

    /** "Keep my account", "Stay signed in", Back or a tap outside a dialog. */
    data object DismissDialog : YouAccountEvent

    /** The top bar's back button. */
    data object Back : YouAccountEvent
}

/** What Account asks its route to do. */
sealed interface YouAccountEffect {
    /** Saved, signed out or deleted: back to You. */
    data object Leave : YouAccountEffect

    /** The session ended on the server: open Sign in, and come back here after it. */
    data object SignIn : YouAccountEffect
}
