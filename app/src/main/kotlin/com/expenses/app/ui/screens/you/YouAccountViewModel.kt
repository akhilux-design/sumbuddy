package com.expenses.app.ui.screens.you

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.api.SetTo
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.auth.AuthRepository
import com.expenses.app.groups.auth.SignOutResult
import com.expenses.app.groups.data.GroupText
import com.expenses.app.groups.data.UpiIdInput
import com.expenses.app.ui.screens.signin.ActionProblem
import com.expenses.app.ui.screens.signin.MAX_PHONE_FIELD
import com.expenses.app.ui.screens.signin.ProfileField
import com.expenses.app.ui.screens.signin.ProfileRefusals
import com.expenses.app.ui.screens.signin.actionProblem
import com.expenses.app.ui.screens.signin.displayPhone
import com.expenses.app.ui.screens.signin.profileField
import com.expenses.app.ui.screens.signin.upiFieldText
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
 * Account (design 04-you/02-account): the name, the optional phone number and UPI ID, Sign out and Delete account.
 *
 * The fields show the profile as last stored at once. Each time Account opens it is also read from the server
 * (`GET /v1/me`), so a change made on another phone shows: the fields follow it while the person has not changed them.
 *
 * Save checks the three fields first and saves nothing while one is wrong (the name is empty, the phone is not a
 * phone number, or the UPI ID breaks the rule of docs/server.md 4.5: 07). Then one `PATCH /v1/me`; a blank UPI ID is
 * sent as null. Sign out sends what waits first; when that cannot be done it asks (08) and "Sign out anyway" signs out
 * with the changes lost. Delete asks first (03), then deletes the account and everything of the app on this phone.
 * Saving and deleting need the server: offline, nothing is tried and the banner says so. Back waits while one of the
 * three runs, and once started each one finishes even when the screen goes (a link opened over it), so the phone
 * never misses what the server already did.
 *
 * @param auth the session.
 * @param unsentCount how many changes wait to be sent (`GroupsRepository.observeUnsentCount()`).
 * @param online whether the phone can reach the internet (`NetworkStatus.online`).
 * @param savedState keeps the typed fields and the open dialog when Android stops the app.
 */
class YouAccountViewModel(
    private val auth: AuthRepository,
    unsentCount: Flow<Int>,
    private val online: StateFlow<Boolean>,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        YouAccountUiState(
            name = savedState.get<String>(NAME_KEY).orEmpty(),
            phone = savedState.get<String>(PHONE_KEY).orEmpty(),
            upiId = savedState.get<String>(UPI_KEY).orEmpty(),
            dialog = savedState.get<String>(DIALOG_KEY)?.let { name -> YouAccountDialog.entries.firstOrNull { it.name == name } },
        ),
    )
    private val effects = Channel<YouAccountEffect>(Channel.BUFFERED)

    /** What the screen shows. */
    val state: StateFlow<YouAccountUiState> = mutableState.asStateFlow()

    private val mutableRefusals = MutableStateFlow(ProfileRefusals())

    /** Refused saves per field, for the error shake of each field ([ProfileRefusals]). */
    val refusals: StateFlow<ProfileRefusals> = mutableRefusals.asStateFlow()

    /** One-off results for the route. */
    val effect: Flow<YouAccountEffect> = effects.receiveAsFlow()

    init {
        val restored = savedState.contains(NAME_KEY)
        savedState.keepForm(viewModelScope, mutableState) {
            set(NAME_KEY, it.name)
            set(PHONE_KEY, it.phone)
            set(UPI_KEY, it.upiId)
            set(DIALOG_KEY, it.dialog?.name)
        }
        viewModelScope.launch { unsentCount.collect { count -> mutableState.update { it.copy(unsentCount = count) } } }
        viewModelScope.launch {
            // Fields Android kept for a stopped app are the person's own; only the store follows the server then.
            val shown = if (restored) null else auth.profile()?.let(::fieldsOf)
            if (shown != null) mutableState.update { if (it.fields() == NO_FIELDS) it.withFields(shown) else it }
            val fresh = auth.refreshProfile()?.let(::fieldsOf) ?: return@launch
            if (shown == null || fresh == shown) return@launch
            mutableState.update { if (!it.busy && it.fields() == shown) it.withFields(fresh) else it }
        }
    }

    /** The three fields as the screen writes them. */
    private data class Fields(val name: String, val phone: String, val upiId: String)

    private fun fieldsOf(profile: UserDto) = Fields(profile.name, displayPhone(profile.phone), profile.upiId.orEmpty())

    private fun YouAccountUiState.fields() = Fields(name, phone, upiId)

    private fun YouAccountUiState.withFields(fields: Fields) = copy(
        name = fields.name,
        phone = fields.phone,
        upiId = fields.upiId,
        nameInvalid = false,
        phoneInvalid = false,
        upiInvalid = false,
    )

    /** Handles what the person did; Back is the route's. */
    fun onEvent(event: YouAccountEvent) {
        when (event) {
            is YouAccountEvent.NameChanged ->
                mutableState.update { it.copy(name = event.name.take(GroupText.MAX_PERSON_NAME), nameInvalid = false) }
            is YouAccountEvent.PhoneChanged ->
                mutableState.update { it.copy(phone = event.phone.take(MAX_PHONE_FIELD), phoneInvalid = false) }
            is YouAccountEvent.UpiIdChanged ->
                mutableState.update { it.copy(upiId = upiFieldText(event.upiId), upiInvalid = false) }
            YouAccountEvent.Save -> save()
            YouAccountEvent.SignOut -> signOut(force = false)
            YouAccountEvent.SignOutAnyway -> {
                mutableState.update { it.copy(dialog = null) }
                signOut(force = true)
            }
            YouAccountEvent.Delete -> mutableState.update { if (it.busy) it else it.copy(dialog = YouAccountDialog.DELETE) }
            YouAccountEvent.ConfirmDelete -> {
                mutableState.update { it.copy(dialog = null) }
                delete()
            }
            YouAccountEvent.DismissDialog -> mutableState.update { it.copy(dialog = null) }
            YouAccountEvent.Back -> Unit
        }
    }

    private fun save() {
        val current = mutableState.value
        if (current.busy) return
        val name = GroupText.clean(current.name, GroupText.MAX_PERSON_NAME)
        val upiId = GroupText.checkUpiId(current.upiId)
        val nameInvalid = name.isEmpty()
        val phoneInvalid = !GroupText.isPhoneOk(current.phone)
        val upiInvalid = upiId == UpiIdInput.Invalid
        if (nameInvalid || phoneInvalid || upiInvalid) {
            mutableState.update { it.copy(nameInvalid = nameInvalid, phoneInvalid = phoneInvalid, upiInvalid = upiInvalid) }
            mutableRefusals.update { it.after(name = nameInvalid, phone = phoneInvalid, upiId = upiInvalid) }
            return
        }
        if (!online.value) {
            mutableState.update { it.copy(problem = ActionProblem.OFFLINE, problemCount = it.problemCount + 1) }
            return
        }
        mutableState.update { it.copy(working = YouAccountWork.SAVING, problem = null) }
        viewModelScope.launch {
            val error = withContext(NonCancellable) {
                auth.saveProfile(name, GroupText.normalizePhone(current.phone), SetTo((upiId as? UpiIdInput.Valid)?.value))
            }
            // The route hears the result before the screen stops waiting.
            when {
                error == null -> {
                    effects.send(YouAccountEffect.Leave)
                    mutableState.update { it.copy(working = null) }
                }
                error.needsSignIn -> {
                    effects.send(YouAccountEffect.SignIn)
                    mutableState.update { it.copy(working = null) }
                }
                else -> {
                    error.profileField()?.let { field -> mutableRefusals.update { r -> r.after(field) } }
                    mutableState.update {
                        when (error.profileField()) {
                            ProfileField.NAME -> it.copy(working = null, nameInvalid = true)
                            ProfileField.PHONE -> it.copy(working = null, phoneInvalid = true)
                            ProfileField.UPI_ID -> it.copy(working = null, upiInvalid = true)
                            null -> it.copy(working = null, problem = error.actionProblem(), problemCount = it.problemCount + 1)
                        }
                    }
                }
            }
        }
    }

    private fun signOut(force: Boolean) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(working = YouAccountWork.SIGNING_OUT, problem = null) }
        viewModelScope.launch {
            when (withContext(NonCancellable) { auth.signOut(force) }) {
                SignOutResult.DONE -> {
                    effects.send(YouAccountEffect.Leave)
                    mutableState.update { it.copy(working = null) }
                }
                SignOutResult.UNSENT_CHANGES -> mutableState.update { it.copy(working = null, dialog = YouAccountDialog.UNSENT_CHANGES) }
            }
        }
    }

    private fun delete() {
        if (mutableState.value.busy) return
        if (!online.value) {
            mutableState.update { it.copy(problem = ActionProblem.OFFLINE, problemCount = it.problemCount + 1) }
            return
        }
        mutableState.update { it.copy(working = YouAccountWork.DELETING, problem = null) }
        viewModelScope.launch {
            val error = withContext(NonCancellable) { auth.deleteAccount() }
            when {
                // The app's data is gone and the process ends in the app; a slow end lands on You.
                error == null -> effects.send(YouAccountEffect.Leave)
                error.needsSignIn -> effects.send(YouAccountEffect.SignIn)
            }
            val problem = error?.takeUnless { e -> e.needsSignIn }?.actionProblem()
            mutableState.update {
                it.copy(working = null, problem = problem, problemCount = it.problemCount + if (problem != null) 1 else 0)
            }
        }
    }

    private companion object {
        const val NAME_KEY = "name"
        const val PHONE_KEY = "phone"
        const val UPI_KEY = "upi_id"
        const val DIALOG_KEY = "dialog"
        val NO_FIELDS = Fields("", "", "")
    }
}
