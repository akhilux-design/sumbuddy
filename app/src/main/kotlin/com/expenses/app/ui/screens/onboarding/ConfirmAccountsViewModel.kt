package com.expenses.app.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.repository.AccountRepository
import com.expenses.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The longest account name the rename field takes. */
private const val MaxAccountNameLength = 40

/**
 * Lists the accounts the scan found. Switching one off or renaming it is stored at once with [AccountRepository];
 * Looks good finishes onboarding (name, tracking mode and start were stored on the earlier steps).
 */
class ConfirmAccountsViewModel(
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val effects = Effects<Unit>()
    private val renaming = MutableStateFlow<RenameDraft?>(null)

    /** What the screen shows. */
    val state: StateFlow<ConfirmAccountsUiState> = combine(accounts.observeAll(), renaming) { all, draft ->
        ConfirmAccountsUiState(accounts = all.map(AccountItem::from), renaming = draft)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), ConfirmAccountsUiState())

    /** Emits once onboarding is finished and Home can open. */
    val done: Flow<Unit> = effects.flow

    /** Handles what the person did. */
    fun onEvent(event: ConfirmAccountsEvent) {
        when (event) {
            is ConfirmAccountsEvent.SetTracked -> viewModelScope.launch {
                accounts.setTracked(event.id, event.tracked)
            }
            is ConfirmAccountsEvent.StartRename -> {
                val account = state.value.accounts.firstOrNull { it.id == event.id } ?: return
                renaming.value = RenameDraft(account, account.displayName ?: account.bank.orEmpty())
            }
            is ConfirmAccountsEvent.RenameChanged -> renaming.value =
                renaming.value?.copy(text = event.text.take(MaxAccountNameLength))
            ConfirmAccountsEvent.SaveRename -> {
                val draft = renaming.value ?: return
                renaming.value = null
                // The bank's own name is not stored as a rename, so the account keeps following it.
                val name = draft.text.takeUnless { it.trim() == draft.account.bank }
                viewModelScope.launch { accounts.rename(draft.account.id, name) }
            }
            ConfirmAccountsEvent.CancelRename -> renaming.value = null
            ConfirmAccountsEvent.Done -> viewModelScope.launch {
                settings.completeOnboarding()
                effects.send(Unit)
            }
        }
    }

    private companion object {
        /** How long the state keeps updating after the screen stops listening. */
        const val StopTimeoutMillis = 5_000L
    }
}
