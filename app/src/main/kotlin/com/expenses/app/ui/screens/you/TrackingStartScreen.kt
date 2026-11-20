package com.expenses.app.ui.screens.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.OptionCard
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.components.SurfaceCardStyle
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space
import java.time.LocalDate

/**
 * Tracking start date (design 04-you/05-tracking-start-date): the current start, three ways to move it earlier
 * (this month, the last 3 months, or a day picked in the system date picker), a note that nothing is added twice,
 * and "Add older payments". Choices that are not earlier than the current start say so and cannot be chosen.
 * While older messages are read, a note says how far it got, and when it is done, how many payments wait on Home.
 *
 * @param state what to show; see [TrackingStartUiState].
 * @param modifier outer modifier. The screen fills the space it is given.
 * @param onEvent called with what the person did; see [TrackingStartEvent].
 */
@Composable
fun TrackingStartScreen(
    state: TrackingStartUiState,
    modifier: Modifier = Modifier,
    onEvent: (TrackingStartEvent) -> Unit = {},
) {
    YouDetailFrame(
        title = stringResource(R.string.you_start_title),
        onBack = { onEvent(TrackingStartEvent.Back) },
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.you_start_add),
                onClick = { onEvent(TrackingStartEvent.AddOlderPayments) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Primary,
                size = ButtonSize.Large,
                enabled = state.canAdd,
            )
        },
    ) {
        AppText(
            text = state.currentStart?.let { stringResource(R.string.you_start_intro, formatDate(it)) }
                ?: stringResource(R.string.you_start_intro_not_started),
            style = AppTheme.type.bodyM,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.s8).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(Space.s12),
        ) {
            OptionCard(
                title = stringResource(R.string.you_start_this_month),
                subtitle = sinceLabel(state, state.thisMonth),
                icon = AppIcon.Calendar,
                selected = state.selected == StartChoice.ThisMonth,
                onClick = { onEvent(TrackingStartEvent.Select(StartChoice.ThisMonth)) },
            )
            OptionCard(
                title = stringResource(R.string.you_start_last_three_months),
                subtitle = sinceLabel(state, state.lastThreeMonths),
                icon = AppIcon.Calendar,
                selected = state.selected == StartChoice.LastThreeMonths,
                onClick = { onEvent(TrackingStartEvent.Select(StartChoice.LastThreeMonths)) },
            )
            OptionCard(
                title = stringResource(R.string.you_start_pick),
                subtitle = state.pickedDate?.let { stringResource(R.string.you_start_picked, formatDate(it)) }
                    ?: stringResource(R.string.you_start_pick_subtitle),
                icon = AppIcon.Edit,
                selected = state.selected == StartChoice.PickDate,
                onClick = { onEvent(TrackingStartEvent.PickDate) },
            )
        }
        InfoRow(
            title = stringResource(R.string.you_start_no_doubles_title),
            subtitle = stringResource(R.string.you_start_no_doubles_body),
            icon = AppIcon.Copy,
        )
        val note = statusNote(state)
        if (note != null) {
            SurfaceCard(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = SurfaceCardStyle.Note,
            ) {
                AppText(text = note, style = AppTheme.type.bodyM, color = AppTheme.colors.textPrimary)
            }
        }
    }
}

/**
 * The second line of a fixed choice: "Since 1 Sep 2026" when it is earlier than the current start, else that it
 * is what the person has now or is already included.
 */
@Composable
private fun sinceLabel(state: TrackingStartUiState, start: LocalDate): String {
    val since = formatDate(start)
    return when {
        state.isEarlier(start) -> stringResource(R.string.you_start_since, since)
        start == state.currentStart -> stringResource(R.string.you_start_since_now, since)
        else -> stringResource(R.string.you_start_since_included, since)
    }
}

/** The note under the choices: why nothing can be read in manual mode, or how reading goes. Null for none. */
@Composable
private fun statusNote(state: TrackingStartUiState): String? = when (val scan = state.scan) {
    is OlderScanStatus.Running -> if (scan.total > 0) {
        stringResource(R.string.you_start_running, scan.read, scan.total)
    } else {
        stringResource(R.string.you_start_running_starting)
    }
    is OlderScanStatus.Done -> if (scan.found > 0) {
        pluralStringResource(R.plurals.you_start_done, scan.found, scan.found)
    } else {
        stringResource(R.string.you_start_done_none)
    }
    OlderScanStatus.Failed -> stringResource(R.string.you_start_failed)
    OlderScanStatus.Idle -> if (state.smsTrackingOn) null else stringResource(R.string.you_start_manual_note)
}
