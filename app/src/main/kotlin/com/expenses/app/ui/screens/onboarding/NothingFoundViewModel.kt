package com.expenses.app.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.settings.SettingsRepository
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where Nothing found goes. */
enum class NothingFoundEffect {
    /** Onboarding is finished: Home. */
    FinishOnboarding,

    /** How far back, to pick an earlier day. */
    LookFurtherBack,
}

/**
 * Shows from which day the scan looked, and finishes onboarding on Go to Home. The tracking start stays the day
 * that was read from: nothing was found since then, and new messages are read from the scan onwards.
 */
class NothingFoundViewModel(private val settings: SettingsRepository, clock: Clock) : ViewModel() {
    private val effects = Effects<NothingFoundEffect>()

    /** What the screen shows. */
    val state: StateFlow<NothingFoundUiState> = settings.settings
        .map { NothingFoundUiState(since = it.trackingStartAt?.atZone(clock.zone)?.toLocalDate()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), NothingFoundUiState())

    /** Where to go next. */
    val effect: Flow<NothingFoundEffect> = effects.flow

    /** Handles what the person did. */
    fun onEvent(event: NothingFoundEvent) {
        when (event) {
            NothingFoundEvent.GoHome -> viewModelScope.launch {
                settings.completeOnboarding()
                effects.send(NothingFoundEffect.FinishOnboarding)
            }
            NothingFoundEvent.LookFurtherBack -> effects.send(NothingFoundEffect.LookFurtherBack)
        }
    }

    private companion object {
        /** How long the state keeps updating after the screen stops listening. */
        const val StopTimeoutMillis = 5_000L
    }
}
