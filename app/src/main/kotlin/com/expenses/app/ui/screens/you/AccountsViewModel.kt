package com.expenses.app.ui.screens.you

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.repository.AccountRepository
import com.expenses.app.ui.screens.onboarding.AccountItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Holds Accounts and cards: the accounts found in SMS with their switches, and the name being typed for one of
 * them. Switching an account off hides its waiting payments; switching it back on brings them back.
 *
 * @param accounts the accounts and cards.
 */
class AccountsViewModel(private val accounts: AccountRepository) : ViewModel() {
    private val renaming = MutableStateFlow<RenameDraft?>(null)

    /** What the screen shows; null until the first read from the database. */
    val state: StateFlow<AccountsUiState?> = combine(accounts.observeAll(), renaming) { all, draft ->
        AccountsUiState(accounts = all.map { AccountItem.from(it) }, renaming = draft)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** Handles the switches and renaming. Back is handled by the caller. */
    fun onEvent(event: AccountsEvent) {
        when (event) {
            is AccountsEvent.SetTracked -> viewModelScope.launch { accounts.setTracked(event.accountId, event.tracked) }
            is AccountsEvent.StartRename -> {
                val item = state.value?.accounts?.firstOrNull { it.id == event.accountId } ?: return
                renaming.value = RenameDraft(accountId = item.id, text = item.displayName.orEmpty())
            }
            is AccountsEvent.RenameChanged -> renaming.value = renaming.value?.copy(text = event.text)
            AccountsEvent.SaveRename -> {
                val draft = renaming.value ?: return
                renaming.value = null
                viewModelScope.launch { accounts.rename(draft.accountId, draft.text.trim().ifEmpty { null }) }
            }
            AccountsEvent.CancelRename -> renaming.value = null
            AccountsEvent.Back -> Unit
        }
    }

    private companion object {
        /** How long the reads stay active after the screen stops watching, so a rotation does not restart them. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
