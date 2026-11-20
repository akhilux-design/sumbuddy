package com.expenses.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.OptionCard
import com.expenses.app.ui.components.TopBar
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space
import java.time.LocalDate

/** The four answers to "How far back should we go?". */
enum class StartChoice {
    /** No past payments: tracking starts now. */
    Fresh,

    /** From the first day of this month. The suggested answer. */
    ThisMonth,

    /** From the first day of the month two months back. */
    LastThreeMonths,

    /** From a day the person picks with the system date picker. */
    FromDate,
}

/**
 * What How far back shows.
 *
 * @param selected the chosen answer.
 * @param thisMonthStart first day of this month, for "Payments since 1 Nov are added.".
 * @param threeMonthsStart first day of the month two months back.
 * @param pickedDate the day picked for [StartChoice.FromDate], or null before one was picked.
 * @param today today's date, the last day the date picker allows.
 * @param pickingDate true while the system date picker is open.
 */
@Immutable
data class HowFarBackUiState(
    val selected: StartChoice,
    val thisMonthStart: LocalDate,
    val threeMonthsStart: LocalDate,
    val pickedDate: LocalDate?,
    val today: LocalDate,
    val pickingDate: Boolean = false,
)

/** What the person does on How far back. */
sealed interface HowFarBackEvent {
    /** They tapped Start fresh, This month or Last 3 months. */
    data class Choose(val choice: StartChoice) : HowFarBackEvent

    /** They tapped Pick a date: the system date picker should open. */
    data object PickDate : HowFarBackEvent

    /** They picked [date] in the date picker. */
    data class DatePicked(val date: LocalDate) : HowFarBackEvent

    /** The date picker closed, with or without a pick. */
    data object DatePickerClosed : HowFarBackEvent

    /** Continue. */
    data object Continue : HowFarBackEvent
}

/**
 * How far back (design 01-onboarding/08-how-far-back), step 2 of 3: from which day bank messages are read.
 *
 * @param state what to show.
 * @param onEvent called with what the person did. [HowFarBackEvent.PickDate] asks the caller to open the system
 * date picker.
 * @param onBack called by the back button.
 * @param modifier layout changes from the caller.
 */
@Composable
fun HowFarBackScreen(
    state: HowFarBackUiState,
    onEvent: (HowFarBackEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingFrame(
        modifier = modifier,
        topBar = { TopBar(title = stringResource(R.string.onboarding_step_2), onBack = onBack) },
        footer = {
            AppButton(
                label = stringResource(R.string.onboarding_continue),
                onClick = { onEvent(HowFarBackEvent.Continue) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        OnboardingHeadline(text = stringResource(R.string.onboarding_how_far_title))
        OnboardingBody(text = stringResource(R.string.onboarding_how_far_body))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Space.s8)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(Space.s12),
        ) {
            OptionCard(
                title = stringResource(R.string.onboarding_how_far_fresh_title),
                subtitle = stringResource(R.string.onboarding_how_far_fresh_body),
                icon = AppIcon.Sprout,
                selected = state.selected == StartChoice.Fresh,
                onClick = { onEvent(HowFarBackEvent.Choose(StartChoice.Fresh)) },
            )
            OptionCard(
                title = stringResource(R.string.onboarding_how_far_month_title),
                subtitle = stringResource(R.string.onboarding_how_far_since, formatShortDate(state.thisMonthStart)),
                icon = AppIcon.Calendar,
                selected = state.selected == StartChoice.ThisMonth,
                onClick = { onEvent(HowFarBackEvent.Choose(StartChoice.ThisMonth)) },
            )
            OptionCard(
                title = stringResource(R.string.onboarding_how_far_three_months_title),
                subtitle = stringResource(R.string.onboarding_how_far_since, formatShortDate(state.threeMonthsStart)),
                icon = AppIcon.Calendar,
                selected = state.selected == StartChoice.LastThreeMonths,
                onClick = { onEvent(HowFarBackEvent.Choose(StartChoice.LastThreeMonths)) },
            )
            OptionCard(
                title = stringResource(R.string.onboarding_how_far_date_title),
                subtitle = state.pickedDate
                    ?.let { stringResource(R.string.onboarding_how_far_since, formatShortDate(it)) }
                    ?: stringResource(R.string.onboarding_how_far_date_body),
                icon = AppIcon.Edit,
                selected = state.selected == StartChoice.FromDate,
                onClick = { onEvent(HowFarBackEvent.PickDate) },
            )
        }
    }
}
