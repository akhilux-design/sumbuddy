package com.expenses.app.ui.screens.you

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.ui.state.keepForm
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Reads the inbox for a period before the tracking start ("Add older payments") and reports how it goes. */
fun interface OlderPaymentsScanner {
    /**
     * Starts reading messages received at or after [from] and before [until], and returns how it goes, ending with
     * [OlderScanStatus.Done] or [OlderScanStatus.Failed]. Payments already stored are not added twice. On success the
     * tracking start becomes [from].
     */
    fun scan(from: Instant, until: Instant): Flow<OlderScanStatus>
}

/**
 * Holds Tracking start date: the current start, the earlier starts on offer and the reading of the extra period.
 * Only a start earlier than the current one can be chosen; "Add older payments" reads only the messages between it
 * and the old start, and the new start is stored once that reading succeeded. What is found waits on Home as
 * detected payments.
 *
 * The choice, the picked day and whether the date picker is open are kept in [savedState].
 *
 * @param settings the person's settings, for the tracking start and mode.
 * @param scanner reads the extra period.
 * @param clock gives today and the time zone.
 * @param savedState where the choice is kept while Android stops the app in the background.
 */
class TrackingStartViewModel(
    private val settings: SettingsRepository,
    private val scanner: OlderPaymentsScanner,
    private val clock: Clock,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val local = MutableStateFlow(
        LocalState(
            selected = savedState.get<String>(SELECTED_KEY)?.let(StartChoice::valueOf),
            pickedDate = savedState.get<Long>(PICKED_DAY_KEY)?.let(LocalDate::ofEpochDay),
            pickingDate = savedState.get<Boolean>(PICKING_KEY) ?: false,
        ),
    )

    init {
        savedState.keepForm(viewModelScope, local) {
            set(SELECTED_KEY, it.selected?.name)
            set(PICKED_DAY_KEY, it.pickedDate?.toEpochDay())
            set(PICKING_KEY, it.pickingDate)
        }
    }

    /** What the screen shows; null until the settings were read. */
    val state: StateFlow<TrackingStartUiState?> = combine(settings.settings, local) { appSettings, screen ->
        val today = LocalDate.now(clock)
        val thisMonth = today.withDayOfMonth(1)
        TrackingStartUiState(
            today = today,
            currentStart = appSettings.trackingStartAt?.atZone(clock.zone)?.toLocalDate(),
            thisMonth = thisMonth,
            lastThreeMonths = thisMonth.minusMonths(2),
            pickedDate = screen.pickedDate,
            selected = screen.selected,
            smsTrackingOn = appSettings.trackingMode == TrackingMode.SMS,
            scan = screen.scan,
            pickingDate = screen.pickingDate,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** Handles the choices, the date picker and "Add older payments". Back is handled by the caller. */
    fun onEvent(event: TrackingStartEvent) {
        when (event) {
            is TrackingStartEvent.Select -> select(event.choice)
            TrackingStartEvent.PickDate -> local.update { it.copy(pickingDate = true) }
            is TrackingStartEvent.DatePicked -> local.update {
                val latest = state.value?.latestPickable
                val date = if (latest != null) minOf(event.date, latest) else event.date
                it.copy(pickedDate = date, selected = StartChoice.PickDate, pickingDate = false)
            }
            TrackingStartEvent.DatePickerClosed -> local.update { it.copy(pickingDate = false) }
            TrackingStartEvent.AddOlderPayments -> addOlderPayments()
            TrackingStartEvent.Back -> Unit
        }
    }

    private fun select(choice: StartChoice) {
        val shown = state.value ?: return
        val date = shown.copy(selected = choice).selectedStart ?: return
        if (shown.isEarlier(date)) local.update { it.copy(selected = choice) }
    }

    private fun addOlderPayments() {
        val shown = state.value ?: return
        if (!shown.canAdd) return
        val newStartDay = shown.selectedStart ?: return
        val newStart = newStartDay.atStartOfDay(clock.zone).toInstant()
        local.update { it.copy(selected = null, scan = OlderScanStatus.Running(read = 0, total = 0)) }
        viewModelScope.launch {
            val oldStart = settings.settings.first().trackingStartAt ?: clock.instant()
            // The scan moves the tracking start itself, and only once it succeeded: a scan that stops early, even
            // after this screen closed, leaves the old start, so the person can try again.
            scanner.scan(newStart, oldStart).collect { status -> local.update { it.copy(scan = status) } }
        }
    }

    /** What only this screen decides: the choice, the picked day, the open date picker and how reading goes. */
    private data class LocalState(
        val selected: StartChoice? = null,
        val pickedDate: LocalDate? = null,
        val pickingDate: Boolean = false,
        val scan: OlderScanStatus = OlderScanStatus.Idle,
    )

    private companion object {
        const val SELECTED_KEY = "selected"
        const val PICKED_DAY_KEY = "picked_day"
        const val PICKING_KEY = "picking"

        /** How long the reads stay active after the screen stops watching, so a rotation does not restart them. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
