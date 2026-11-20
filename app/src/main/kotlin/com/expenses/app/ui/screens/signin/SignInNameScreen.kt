package com.expenses.app.ui.screens.signin

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.expenses.app.R
import com.expenses.app.ui.motion.RevealValue
import com.expenses.app.ui.motion.shakeOn
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppInputState
import com.expenses.app.ui.components.TopBar
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.screens.onboarding.OnboardingBody
import com.expenses.app.ui.screens.onboarding.OnboardingFrame
import com.expenses.app.ui.screens.onboarding.OnboardingHeadline
import com.expenses.app.ui.theme.AppTheme

/**
 * What "Your name (after sign-in)" shows.
 *
 * @param name the name field (filled from the account).
 * @param phone the optional phone field.
 * @param upiId the optional UPI ID field.
 * @param phoneInvalid true when the phone typed is not a phone number.
 * @param upiInvalid true when the UPI ID typed is not a UPI ID ("That does not look like a UPI ID.").
 * @param problem the banner at the top of the body when saving needed the server and did not happen, or null.
 * @param problemCount how many times a save was refused with [problem], so each refused tap plays `haptic/reject`,
 * also when the banner was already there.
 * @param saving true while it saves.
 */
@Immutable
data class SignInNameUiState(
    val name: String = "",
    val phone: String = "",
    val upiId: String = "",
    val phoneInvalid: Boolean = false,
    val upiInvalid: Boolean = false,
    val problem: ActionProblem? = null,
    val problemCount: Int = 0,
    val saving: Boolean = false,
) {
    /** True when Continue can be used: the name is not blank. */
    val canContinue: Boolean get() = name.isNotBlank() && !saving
}

/** What the person does on Your name (after sign-in). */
sealed interface SignInNameEvent {
    /** The name field changed. */
    data class NameChanged(val name: String) : SignInNameEvent

    /** The phone field changed. */
    data class PhoneChanged(val phone: String) : SignInNameEvent

    /** The UPI ID field changed. */
    data class UpiIdChanged(val upiId: String) : SignInNameEvent

    /** Continue, or the keyboard's Done key. */
    data object Continue : SignInNameEvent
}

/**
 * Your name (after sign-in) (design 01-onboarding/03-your-name-after-sign-in), shown after the first sign-in. The
 * name comes from the Google account; the phone number is optional and not checked: it only helps friends find the
 * person, and only people in their groups see it. The UPI ID is optional too: friends in the person's groups see it on
 * pay links. Continue saves all three, and the name is also the one used on Home.
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param onBack called by the back button.
 * @param modifier layout changes from the caller.
 * @param refusals refused saves per field: each new one shakes that field and plays the reject haptic.
 */
@Composable
fun SignInNameScreen(
    state: SignInNameUiState,
    onEvent: (SignInNameEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    refusals: ProfileRefusals = ProfileRefusals(),
) {
    OnboardingFrame(
        modifier = modifier,
        topBar = { TopBar(title = stringResource(R.string.signin_title), onBack = onBack) },
        footer = {
            AppButton(
                label = stringResource(R.string.signin_continue),
                onClick = { onEvent(SignInNameEvent.Continue) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canContinue,
            )
        },
    ) {
        RevealValue(state.problem) { problem ->
            ActionProblemBanner(
                problem = problem,
                offlineTitle = stringResource(R.string.signin_save_offline_title),
                offlineBody = stringResource(R.string.signin_save_offline_body),
                failedTitle = stringResource(R.string.signin_save_failed_title),
                failedBody = stringResource(R.string.signin_save_failed_body),
            )
        }
        OnboardingHeadline(text = stringResource(R.string.signin_name_headline))
        OnboardingBody(text = stringResource(R.string.signin_name_body))
        AppInput(
            value = state.name,
            onValueChange = { onEvent(SignInNameEvent.NameChanged(it)) },
            label = stringResource(R.string.signin_name_label),
            modifier = Modifier
                .fillMaxWidth()
                .shakeOn(refusals.name),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
        )
        AppInput(
            value = state.phone,
            onValueChange = { onEvent(SignInNameEvent.PhoneChanged(it)) },
            label = stringResource(R.string.signin_phone_label),
            modifier = Modifier
                .fillMaxWidth()
                .shakeOn(refusals.phone),
            helper = stringResource(R.string.signin_phone_invalid),
            state = if (state.phoneInvalid) AppInputState.Error else AppInputState.Default,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        )
        AppText(
            text = stringResource(R.string.signin_phone_note),
            style = AppTheme.type.caption,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
        )
        UpiIdField(
            value = state.upiId,
            onValueChange = { onEvent(SignInNameEvent.UpiIdChanged(it)) },
            invalid = state.upiInvalid,
            onDone = { onEvent(SignInNameEvent.Continue) },
            refusedSubmits = refusals.upiId,
        )
    }
}
