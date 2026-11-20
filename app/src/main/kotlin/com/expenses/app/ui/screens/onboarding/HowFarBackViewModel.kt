package com.expenses.app.ui.screens.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.ui.state.keepForm
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where How far back goes after Continue. */
enum class HowFarBackEffect {
    /** Start fresh: nothing to read, onboarding is finished and Home opens. */
    FinishOnboarding,

    /** A start in the past: Scanning SMS reads the messages since then. */
    Scan,
}

/** Months counted by "Last 3 months": this one and the two before it. */
private const val ExtraMonthsForThree = 2L

/** Saved state keys of the answer, so it survives Android stopping the app in the background. */
private const val SelectedKey = "selected"
private const val PickedDayKey = "picked_day"
private const val PickingKey = "picking"

/**
 * Holds the answer to "How far back should we go?" and stores it on Continue: tracking mode SMS and the tracking
 * start (docs/architecture.md: "Start fresh" is now, "This month" the first day of the month at 00:00, "Last 3
 * months" the first day of the month two months back, "From a date" that day at 00:00, all in [clock]'s zone).
 * Start fresh also marks the inbox as read up to now and finishes onboarding. The answer, the picked day and whether
 * the date picker is open are kept in [savedState].
 */
class HowFarBackViewModel(
    private val settings: SettingsRepository,
    private val clock: Clock,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val effects = Effects<HowFarBackEffect>()
    private val mutableState: MutableStateFlow<HowFarBackUiState>

    init {
        val today = LocalDate.now(clock)
        val monthStart = today.withDayOfMonth(1)
        mutableState = MutableStateFlow(
            HowFarBackUiState(
                selected = savedState.get<String>(SelectedKey)?.let(StartChoice::valueOf) ?: StartChoice.ThisMonth,
                thisMonthStart = monthStart,
                threeMonthsStart = monthStart.minusMonths(ExtraMonthsForThree),
                pickedDate = savedState.get<Long>(PickedDayKey)?.let(LocalDate::ofEpochDay),
                today = today,
                pickingDate = savedState.get<Boolean>(PickingKey) ?: false,
            ),
        )
        savedState.keepForm(viewModelScope, mutableState) {
            set(SelectedKey, it.selected.name)
            set(PickedDayKey, it.pickedDate?.toEpochDay())
            set(PickingKey, it.pickingDate)
        }
    }

    /** What the screen shows. */
    val state: StateFlow<HowFarBackUiState> = mutableState.asStateFlow()

    /** Where to go once the choice is stored. */
    val effect: Flow<HowFarBackEffect> = effects.flow

    /** Handles what the person did. */
    fun onEvent(event: HowFarBackEvent) {
        when (event) {
            is HowFarBackEvent.Choose -> mutableState.update { it.copy(selected = event.choice) }
            is HowFarBackEvent.DatePicked -> mutableState.update {
                it.copy(selected = StartChoice.FromDate, pickedDate = minOf(event.date, it.today), pickingDate = false)
            }
            HowFarBackEvent.PickDate -> mutableState.update { it.copy(pickingDate = true) }
            HowFarBackEvent.DatePickerClosed -> mutableState.update { it.copy(pickingDate = false) }
            HowFarBackEvent.Continue -> store(mutableState.value)
        }
    }

    private fun store(current: HowFarBackUiState) {
        viewModelScope.launch {
            settings.setTrackingMode(TrackingMode.SMS)
            val startDay = when (current.selected) {
                StartChoice.Fresh -> null
                StartChoice.ThisMonth -> current.thisMonthStart
                StartChoice.LastThreeMonths -> current.threeMonthsStart
                StartChoice.FromDate -> current.pickedDate ?: current.thisMonthStart
            }
            if (startDay == null) {
                val now = clock.instant()
                settings.setTrackingStart(now)
                settings.setLastScan(now)
                settings.completeOnboarding()
                effects.send(HowFarBackEffect.FinishOnboarding)
            } else {
                settings.setTrackingStart(startDay.atStartOfDay(clock.zone).toInstant())
                effects.send(HowFarBackEffect.Scan)
            }
        }
    }
}
