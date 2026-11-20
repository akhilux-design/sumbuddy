package com.expenses.app.ui.screens.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.icons.AppIcon
import java.time.LocalDate

/**
 * What Nothing found shows.
 *
 * @param since the first day that was read, or null while the settings load.
 */
@Immutable
data class NothingFoundUiState(val since: LocalDate? = null)

/** What the person does on Nothing found. */
sealed interface NothingFoundEvent {
    /** Go to Home: onboarding is finished and new payments show up as their SMS arrive. */
    data object GoHome : NothingFoundEvent

    /** Look further back: How far back opens again. */
    data object LookFurtherBack : NothingFoundEvent
}

/**
 * Nothing found (design 01-onboarding/10-nothing-found): the scan found no bank messages. The person can start
 * from today or go back and pick an earlier day.
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param modifier layout changes from the caller.
 */
@Composable
fun NothingFoundScreen(
    state: NothingFoundUiState,
    onEvent: (NothingFoundEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingFrame(
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.onboarding_nothing_home),
                onClick = { onEvent(NothingFoundEvent.GoHome) },
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                label = stringResource(R.string.onboarding_nothing_further_back),
                onClick = { onEvent(NothingFoundEvent.LookFurtherBack) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        StickerTile(icon = AppIcon.Search, tone = StickerTone.Brand)
        OnboardingHeadline(text = stringResource(R.string.onboarding_nothing_title))
        if (state.since != null) {
            OnboardingBody(text = stringResource(R.string.onboarding_nothing_body, formatShortDate(state.since)))
        }
        InfoRow(
            title = stringResource(R.string.onboarding_nothing_today_title),
            subtitle = stringResource(R.string.onboarding_nothing_today_body),
            icon = AppIcon.Sprout,
        )
        InfoRow(
            title = stringResource(R.string.onboarding_nothing_by_hand_title),
            subtitle = stringResource(R.string.onboarding_nothing_by_hand_body),
            icon = AppIcon.Edit,
        )
    }
}
