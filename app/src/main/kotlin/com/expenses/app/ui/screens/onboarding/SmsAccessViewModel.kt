package com.expenses.app.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.data.settings.TrackingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The ViewModel of the two screens that ask for SMS access: Privacy promise and SMS access blocked. Asking itself
 * is done by the screen with the system dialog; this stores whether the person answered that dialog before, and
 * their choice to type expenses in by hand (also used when they answer Don't allow): tracking mode MANUAL and
 * onboarding finished.
 */
class SmsAccessViewModel(private val settings: SettingsRepository) : ViewModel() {
    private val effects = Effects<Unit>()

    /** True once the person said no to Android's SMS dialog, in this session or an earlier one. */
    val askedBefore: StateFlow<Boolean> = settings.settings.map { it.smsAskedBefore }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The person said no to Android's SMS dialog. */
    fun onAnswered() {
        viewModelScope.launch { settings.markSmsAsked() }
    }

    /** Emits once manual mode is stored and Home can open. */
    val manualModeStored: Flow<Unit> = effects.flow

    /** The person chose to add expenses by hand, or did not allow SMS access. */
    fun useManualMode() {
        viewModelScope.launch {
            settings.setTrackingMode(TrackingMode.MANUAL)
            settings.completeOnboarding()
            effects.send(Unit)
        }
    }
}
