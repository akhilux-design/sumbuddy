package com.expenses.app.ui.screens.signin

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.expenses.app.R
import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.data.GroupText
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppInputState
import com.expenses.app.ui.components.Banner
import com.expenses.app.ui.components.BannerTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.shakeOn
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalAppHaptics

// The parts "Your name (after sign-in)" (01-onboarding/03) and Account (04-you/02, 07) share: the UPI ID field with its
// note, the phone number as people write it, and what a refused save means for the form.

/** Indian numbers as the design writes them: "+91" and ten digits. */
private val IndianMobile = Regex("^\\+91([0-9]{5})([0-9]{5})$")

/** Characters a UPI ID field keeps: the longest UPI ID, with room for spaces typed at the ends (they are trimmed). */
private const val MAX_UPI_FIELD = GroupText.MAX_UPI_ID + 8

/** Characters a phone field keeps. */
internal const val MAX_PHONE_FIELD: Int = 20

/**
 * A stored phone number as the design writes it in a field: "+919876543210" becomes "+91 98765 43210". Other numbers
 * stay as stored, and none is "". The spaces do not matter when it is saved again ([GroupText.normalizePhone]).
 */
internal fun displayPhone(stored: String?): String {
    val phone = stored.orEmpty()
    val match = IndianMobile.matchEntire(phone) ?: return phone
    return "+91 ${match.groupValues[1]} ${match.groupValues[2]}"
}

/** What typed into a UPI ID field is kept: at most [MAX_UPI_FIELD] characters. */
internal fun upiFieldText(typed: String): String = typed.take(MAX_UPI_FIELD)

/** Which field of the profile form the server refused, by the `field` of its `400 bad_request`. */
internal enum class ProfileField {
    NAME,
    PHONE,
    UPI_ID,
}

/** The field a refused save names (`name`, `phone` or `upiId`), or null when the error is not about one field. */
internal fun ApiError.profileField(): ProfileField? {
    if (status != BAD_REQUEST) return null
    return when (field?.removePrefix("$.")) {
        "name" -> ProfileField.NAME
        "phone" -> ProfileField.PHONE
        "upiId" -> ProfileField.UPI_ID
        else -> null
    }
}

private const val BAD_REQUEST = 400

/**
 * How many times a save of the profile form was refused, per field, for the error shake (`design/spec/motion.md`,
 * "Error shake"): pass each count to that field's `Modifier.shakeOn`, which shakes it and plays `haptic/reject`. Counts,
 * not flags: a second refused save does not change the error, but it must shake again. They only grow.
 *
 * @param name refusals of the name field.
 * @param phone refusals of the phone field.
 * @param upiId refusals of the UPI ID field.
 */
@Immutable
data class ProfileRefusals(val name: Int = 0, val phone: Int = 0, val upiId: Int = 0) {
    /**
     * One more refusal, counted on the first refused field in the form's order (name, phone, UPI ID): one refused save
     * shakes one field and plays one reject, even when more than one field is wrong. Nothing refused, nothing changes.
     */
    fun after(name: Boolean = false, phone: Boolean = false, upiId: Boolean = false): ProfileRefusals = when {
        name -> copy(name = this.name + 1)
        phone -> copy(phone = this.phone + 1)
        upiId -> copy(upiId = this.upiId + 1)
        else -> this
    }

    /** One more refusal of [field], which the server named. */
    internal fun after(field: ProfileField): ProfileRefusals = when (field) {
        ProfileField.NAME -> after(name = true)
        ProfileField.PHONE -> after(phone = true)
        ProfileField.UPI_ID -> after(upiId = true)
    }
}

/** Why an online action did not happen, shown in a banner at the top of the screen's body (plan section 1). */
enum class ActionProblem {
    /** No connection, before or during the request: "You're offline". */
    OFFLINE,

    /** The server refused it for a reason the screen does not name: "Could not do that now". */
    FAILED,
}

/**
 * `haptic/reject` each time a tap's online action is refused with an [ActionProblem] banner (`design/spec/motion.md`,
 * Haptics: "server refused, offline"): a second refused tap plays it again although the banner stays the same. Not for
 * a banner already there when the screen is rebuilt. Call it from the route or screen that shows the banner, with the
 * problem it shows and how many times one was shown ([count], from the screen's state; it changes with [problem]).
 */
@Composable
internal fun ActionProblemHaptic(problem: ActionProblem?, count: Int) {
    val haptics = LocalAppHaptics.current
    var heard by rememberSaveable { mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (count == heard) return@LaunchedEffect
        heard = count
        if (problem != null) haptics.reject()
    }
}

/** The problem an error of an online action gives, for the banner. */
internal fun ApiError.actionProblem(): ActionProblem = if (isOffline) ActionProblem.OFFLINE else ActionProblem.FAILED

/**
 * The banner for [problem] (the design's offline banner frame, Notice tone, wifi-off or alert icon). The copy is not
 * in the design for these screens.
 *
 * @param problem what went wrong.
 * @param offlineTitle "You're offline", from the screen's own strings.
 * @param offlineBody "Check your connection and try again. Nothing is lost."
 * @param failedTitle "Could not do that now".
 * @param failedBody "Try again in a moment."
 */
@Composable
internal fun ActionProblemBanner(
    problem: ActionProblem,
    offlineTitle: String,
    offlineBody: String,
    failedTitle: String,
    failedBody: String,
) {
    when (problem) {
        ActionProblem.OFFLINE -> Banner(title = offlineTitle, subtitle = offlineBody, icon = AppIcon.WifiOff, tone = BannerTone.Notice)
        ActionProblem.FAILED -> Banner(title = failedTitle, subtitle = failedBody, icon = AppIcon.Alert, tone = BannerTone.Notice)
    }
}

/**
 * The design's "UPI input" and "UPI note" (01-onboarding/03, 04-you/02): "UPI ID (optional)", and under it "For example
 * name@okaxis. Friends in your groups see it on pay links.". [invalid] shows the field in its error state with "That
 * does not look like a UPI ID." (04-you/07). It draws two children of the caller's column, 12dp apart as the column
 * places them.
 *
 * @param value the text in the field.
 * @param onValueChange called with the new text.
 * @param invalid true when the text is not a UPI ID ([GroupText.checkUpiId] said so, or the server did).
 * @param onDone called by the keyboard's Done key.
 * @param refusedSubmits [ProfileRefusals.upiId]: each new refusal shakes the field and plays the reject haptic.
 */
@Composable
internal fun UpiIdField(
    value: String,
    onValueChange: (String) -> Unit,
    invalid: Boolean,
    onDone: () -> Unit,
    refusedSubmits: Int = 0,
) {
    AppInput(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(R.string.signin_upi_label),
        modifier = Modifier
            .fillMaxWidth()
            .shakeOn(refusedSubmits),
        helper = stringResource(R.string.signin_upi_invalid),
        state = if (invalid) AppInputState.Error else AppInputState.Default,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
    AppText(
        text = stringResource(R.string.signin_upi_note),
        style = AppTheme.type.caption,
        modifier = Modifier.fillMaxWidth(),
        color = AppTheme.colors.textSecondary,
    )
}
