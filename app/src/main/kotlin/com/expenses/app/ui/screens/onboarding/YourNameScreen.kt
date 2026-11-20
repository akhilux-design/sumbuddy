package com.expenses.app.ui.screens.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.TopBar

/**
 * What Your name shows.
 *
 * @param name the text in the name field.
 */
@Immutable
data class YourNameUiState(val name: String = "") {
    /** True when Continue can be used: the name is not blank. */
    val canContinue: Boolean get() = name.isNotBlank()
}

/** What the person does on Your name. */
sealed interface YourNameEvent {
    /** The person typed; [name] is the new text. */
    data class NameChanged(val name: String) : YourNameEvent

    /** Continue, or the keyboard's Done key. */
    data object Continue : YourNameEvent
}

/**
 * Your name (design 01-onboarding/04-your-name), step 1 of 3. The name is kept on this phone and greets the person
 * on Home. Typing uses the system keyboard.
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param onBack called by the back button.
 * @param modifier layout changes from the caller.
 */
@Composable
fun YourNameScreen(
    state: YourNameUiState,
    onEvent: (YourNameEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingFrame(
        modifier = modifier,
        topBar = { TopBar(title = stringResource(R.string.onboarding_step_1), onBack = onBack) },
        footer = {
            AppButton(
                label = stringResource(R.string.onboarding_continue),
                onClick = { onEvent(YourNameEvent.Continue) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canContinue,
            )
        },
    ) {
        OnboardingHeadline(text = stringResource(R.string.onboarding_name_title))
        OnboardingBody(text = stringResource(R.string.onboarding_name_body))
        AppInput(
            value = state.name,
            onValueChange = { onEvent(YourNameEvent.NameChanged(it)) },
            label = stringResource(R.string.onboarding_name_label),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onEvent(YourNameEvent.Continue) }),
        )
    }
}
