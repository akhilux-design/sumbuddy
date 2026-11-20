package com.expenses.app.ui.screens.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppInputState
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.RevealValue
import com.expenses.app.ui.screens.onboarding.OnboardingBody
import com.expenses.app.ui.screens.onboarding.OnboardingHeadline
import com.expenses.app.ui.screens.signin.ActionProblem
import com.expenses.app.ui.screens.signin.ActionProblemBanner
import com.expenses.app.ui.theme.Space

/** Space between the facts (the design's Facts frame, `space/16`). */
private val FactsGap = Space.s16

/**
 * Set up Family (design 06-family/00): the pink sticker with Icon/users, "One month for the two of you", "Family name"
 * ("Home" at first), what is shared, and "Invite partner". The fifth fact "Earlier Family expenses" shows only when
 * this phone has Family expenses that setting up shares (09). A blank name says "That does not look right." and turns
 * "Invite partner" off (10). The Offline (11) and Problem (12) banners sit at the top of the body.
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param modifier layout changes from the caller.
 */
@Composable
fun SetUpFamilyScreen(state: SetUpFamilyUiState, onEvent: (SetUpFamilyEvent) -> Unit, modifier: Modifier = Modifier) {
    PageFrame(
        title = stringResource(R.string.family_title),
        onBack = { onEvent(SetUpFamilyEvent.Back) },
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.family_invite_button),
                onClick = { onEvent(SetUpFamilyEvent.Invite) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Primary,
                size = ButtonSize.Large,
                enabled = !state.nameError,
            )
        },
    ) {
        RevealValue(state.problem) { problem -> FamilyProblemBanner(problem) }
        StickerTile(icon = AppIcon.Users, tone = StickerTone.Pink)
        OnboardingHeadline(text = stringResource(R.string.family_setup_headline))
        OnboardingBody(text = stringResource(R.string.family_setup_body))
        AppInput(
            value = state.name,
            onValueChange = { onEvent(SetUpFamilyEvent.NameChanged(it)) },
            label = stringResource(R.string.family_name_label),
            modifier = Modifier.fillMaxWidth(),
            helper = stringResource(R.string.family_name_invalid),
            state = if (state.nameError) AppInputState.Error else AppInputState.Default,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        )
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FactsGap)) {
            InfoRow(
                title = stringResource(R.string.family_fact_invite_title),
                subtitle = stringResource(R.string.family_fact_invite_body),
                icon = AppIcon.Mail,
            )
            InfoRow(
                title = stringResource(R.string.family_fact_counts_title),
                subtitle = stringResource(R.string.family_fact_counts_body),
                icon = AppIcon.Calculator,
            )
            InfoRow(
                title = stringResource(R.string.family_fact_private_title),
                subtitle = stringResource(R.string.family_fact_private_body),
                icon = AppIcon.EyeOff,
            )
            InfoRow(
                title = stringResource(R.string.family_fact_sms_title),
                subtitle = stringResource(R.string.family_fact_sms_body),
                icon = AppIcon.Mail,
            )
            if (state.earlierCount > 0) EarlierExpensesRow(state.earlierCount)
        }
    }
}

/**
 * "Earlier Family expenses" / "Your 12 Family expenses on this phone are shared too." (Set up Family 09, Join Family
 * 13): shown before the person sets up or joins, so sharing them is their choice (decision 3).
 */
@Composable
internal fun EarlierExpensesRow(count: Int) {
    InfoRow(
        title = stringResource(R.string.family_fact_earlier_title),
        subtitle = pluralStringResource(R.plurals.family_fact_earlier_body, count, count),
        icon = AppIcon.Calendar,
    )
}

/** The Offline (Icon/wifi-off) or Problem (Icon/alert) banner of the family screens (11, 12, 14, 15, 23, 24). */
@Composable
internal fun FamilyProblemBanner(problem: ActionProblem) {
    ActionProblemBanner(
        problem = problem,
        offlineTitle = stringResource(R.string.family_offline_title),
        offlineBody = stringResource(R.string.family_offline_body),
        failedTitle = stringResource(R.string.family_failed_title),
        failedBody = stringResource(R.string.family_failed_body),
    )
}
