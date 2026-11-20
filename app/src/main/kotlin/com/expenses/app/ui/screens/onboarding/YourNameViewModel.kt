package com.expenses.app.ui.screens.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.ui.state.keepForm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The longest name the field takes. */
private const val MaxNameLength = 40

/** The saved state key of the name being typed. */
private const val NameKey = "name"

/**
 * Holds the name being typed on Your name and stores it with [SettingsRepository.setUserName] on Continue. A name
 * stored earlier (the person came back to this step) fills the field. The name being typed is kept in
 * [savedState], so it comes back after Android stopped the app in the background.
 */
class YourNameViewModel(
    private val settings: SettingsRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(YourNameUiState(name = savedState.get<String>(NameKey).orEmpty()))
    private val effects = Effects<Unit>()
    private var saving = false

    /** What the screen shows. */
    val state: StateFlow<YourNameUiState> = mutableState.asStateFlow()

    /** Emits once the name is stored and the next step can open. */
    val done: Flow<Unit> = effects.flow

    init {
        savedState.keepForm(viewModelScope, mutableState) { set(NameKey, it.name) }
        viewModelScope.launch {
            val stored = settings.settings.first().userName
            if (stored != null) mutableState.update { if (it.name.isEmpty()) it.copy(name = stored) else it }
        }
    }

    /** Handles what the person did. */
    fun onEvent(event: YourNameEvent) {
        when (event) {
            is YourNameEvent.NameChanged -> mutableState.update { it.copy(name = event.name.take(MaxNameLength)) }
            YourNameEvent.Continue -> {
                val current = mutableState.value
                // A second Continue (or the keyboard's Done) while the name is stored would open the next step twice.
                if (!current.canContinue || saving) return
                saving = true
                viewModelScope.launch {
                    settings.setUserName(current.name)
                    effects.send(Unit)
                    saving = false
                }
            }
        }
    }
}
