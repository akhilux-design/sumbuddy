package com.expenses.app.ui.screens.signin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.api.SetTo
import com.expenses.app.groups.auth.AuthRepository
import com.expenses.app.groups.data.GroupText
import com.expenses.app.groups.data.UpiIdInput
import com.expenses.app.ui.state.keepForm
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Your name (after sign-in): the name, phone and UPI ID are filled from the account; Continue saves them on the server
 * in one request and the name on this phone (Home). The phone number goes to the server only now, after sign-in, and
 * only if typed. Nothing is saved while the phone or the UPI ID is wrong (the UPI ID rule of docs/server.md 4.5); a
 * blank UPI ID is sent as null. Saving needs the server: offline, nothing is tried and the banner says so.
 *
 * @param auth the session.
 * @param savedState keeps the typed fields when Android stops the app.
 * @param online whether the phone can reach the internet (`NetworkStatus.online`); always online when not given.
 */
class SignInNameViewModel(
    private val auth: AuthRepository,
    savedState: SavedStateHandle,
    private val online: StateFlow<Boolean> = MutableStateFlow(true),
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SignInNameUiState(
            name = savedState.get<String>(NAME_KEY).orEmpty(),
            phone = savedState.get<String>(PHONE_KEY).orEmpty(),
            upiId = savedState.get<String>(UPI_KEY).orEmpty(),
        ),
    )
    private val done = Channel<Unit>(Channel.BUFFERED)

    /** What the screen shows. */
    val state: StateFlow<SignInNameUiState> = mutableState.asStateFlow()

    private val mutableRefusals = MutableStateFlow(ProfileRefusals())

    /** Refused saves per field, for the error shake of each field ([ProfileRefusals]). */
    val refusals: StateFlow<ProfileRefusals> = mutableRefusals.asStateFlow()

    /** Emits once the profile is saved. */
    val saved: Flow<Unit> = done.receiveAsFlow()

    init {
        val restored = savedState.contains(NAME_KEY)
        savedState.keepForm(viewModelScope, mutableState) {
            set(NAME_KEY, it.name)
            set(PHONE_KEY, it.phone)
            set(UPI_KEY, it.upiId)
        }
        if (!restored) {
            viewModelScope.launch {
                val profile = auth.profile() ?: return@launch
                mutableState.update {
                    if (it.name.isEmpty() && it.phone.isEmpty() && it.upiId.isEmpty()) {
                        it.copy(name = profile.name, phone = displayPhone(profile.phone), upiId = profile.upiId.orEmpty())
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** Handles what the person did. */
    fun onEvent(event: SignInNameEvent) {
        when (event) {
            is SignInNameEvent.NameChanged ->
                mutableState.update { it.copy(name = event.name.take(GroupText.MAX_PERSON_NAME)) }
            is SignInNameEvent.PhoneChanged ->
                mutableState.update { it.copy(phone = event.phone.take(MAX_PHONE_FIELD), phoneInvalid = false) }
            is SignInNameEvent.UpiIdChanged ->
                mutableState.update { it.copy(upiId = upiFieldText(event.upiId), upiInvalid = false) }
            SignInNameEvent.Continue -> save()
        }
    }

    private fun save() {
        val current = mutableState.value
        if (!current.canContinue) return
        val upiId = GroupText.checkUpiId(current.upiId)
        val phoneInvalid = !GroupText.isPhoneOk(current.phone)
        val upiInvalid = upiId == UpiIdInput.Invalid
        if (phoneInvalid || upiInvalid) {
            mutableState.update { it.copy(phoneInvalid = phoneInvalid, upiInvalid = upiInvalid) }
            mutableRefusals.update { it.after(phone = phoneInvalid, upiId = upiInvalid) }
            return
        }
        val name = GroupText.clean(current.name, GroupText.MAX_PERSON_NAME)
        if (name.isEmpty()) return
        if (!online.value) {
            mutableState.update { it.copy(problem = ActionProblem.OFFLINE, problemCount = it.problemCount + 1) }
            return
        }
        mutableState.update { it.copy(saving = true, problem = null) }
        viewModelScope.launch {
            // Once started, the save finishes even when the screen goes, so what the server took is kept here too.
            val error = withContext(NonCancellable) {
                auth.saveProfile(name, GroupText.normalizePhone(current.phone), SetTo((upiId as? UpiIdInput.Valid)?.value))
            }
            // The route hears it is saved before the screen stops waiting.
            if (error == null) done.send(Unit)
            error?.profileField()?.let { field -> mutableRefusals.update { it.after(field) } }
            mutableState.update {
                when {
                    error == null -> it.copy(saving = false)
                    error.profileField() == ProfileField.UPI_ID -> it.copy(saving = false, upiInvalid = true)
                    error.profileField() == ProfileField.PHONE -> it.copy(saving = false, phoneInvalid = true)
                    else -> it.copy(saving = false, problem = error.actionProblem(), problemCount = it.problemCount + 1)
                }
            }
        }
    }

    private companion object {
        const val NAME_KEY = "name"
        const val PHONE_KEY = "phone"
        const val UPI_KEY = "upi_id"
    }
}
