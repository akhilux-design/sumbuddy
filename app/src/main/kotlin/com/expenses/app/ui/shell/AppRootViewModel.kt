package com.expenses.app.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/**
 * What [AppRoot] needs to know.
 *
 * @param startAtOnboarding where the navigation starts: true for onboarding, false for Home, null while the settings
 * are still loading. Fixed after the first value, so finishing onboarding does not rebuild the navigation.
 * @param onboardingDone whether onboarding is finished now. Notification deep links are followed only when true.
 */
data class AppRootUiState(
    val startAtOnboarding: Boolean? = null,
    val onboardingDone: Boolean = false,
)

/** Decides where the app starts. */
class AppRootViewModel(settings: SettingsRepository) : ViewModel() {
    /** The current [AppRootUiState]. */
    val state: StateFlow<AppRootUiState> = settings.settings
        .map { it.onboardingDone }
        .distinctUntilChanged()
        .runningFold(AppRootUiState()) { previous, done ->
            AppRootUiState(startAtOnboarding = previous.startAtOnboarding ?: !done, onboardingDone = done)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppRootUiState())
}
