package com.expenses.app.ui.screens.family

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.data.FamilyInfo
import com.expenses.app.groups.data.FamilyRepository
import com.expenses.app.groups.data.FamilyResult
import com.expenses.app.groups.data.GroupText
import com.expenses.app.ui.screens.signin.ActionProblem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Set up Family (design 06-family/00, 09 to 12; docs/family.md 1.2), shown only while signed in (the route's
 * `SignInGate`).
 *
 * "Invite partner" needs the network: it sets the family up with a placeholder for the partner
 * ([FamilyRepository.create]), makes the placeholder's link ([FamilyRepository.inviteLink]) and hands the invite to the
 * share sheet, then Home opens on the Family side. Offline nothing is made and the Offline banner shows; a refusal
 * shows the Problem banner. When the family was set up but the link was not made, the next tap only makes the link: the
 * family's id is kept. A family that is already in the account (`409 already_in_family`), or already on this phone when
 * the screen opens (a race after sign-in), opens Home on the Family side.
 *
 * @param family the family on this phone.
 * @param online whether the phone can reach the internet (`NetworkStatus.online`).
 * @param savedState keeps the typed name and the made family's id across process death.
 * @param defaultName the name the field starts with ("Home").
 */
class SetUpFamilyViewModel(
    private val family: FamilyRepository,
    private val online: StateFlow<Boolean>,
    private val savedState: SavedStateHandle,
    defaultName: String,
) : ViewModel() {
    private val mutableState = (savedState.get<String>(NAME_KEY) ?: defaultName).let { name ->
        MutableStateFlow(SetUpFamilyUiState(name = name, nameError = cleaned(name).isEmpty()))
    }
    private val effects = Channel<SetUpFamilyEffect>(Channel.BUFFERED)

    /** The family set up by this screen whose link was not made yet, or null. */
    private var createdId: String?
        get() = savedState[CREATED_KEY]
        set(value) {
            savedState[CREATED_KEY] = value
        }

    /** What the screen shows. */
    val state: StateFlow<SetUpFamilyUiState> = mutableState.asStateFlow()

    /** One-off results for the route. */
    val effect: Flow<SetUpFamilyEffect> = effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            family.observeShareableEarlier().collect { count -> mutableState.update { it.copy(earlierCount = count) } }
        }
        viewModelScope.launch {
            // A family already on this phone (made on another phone and pulled after sign-in): nothing to set up.
            val existing: FamilyInfo? = family.observeFamily().first()
            if (existing != null && createdId == null && !mutableState.value.busy) effects.send(SetUpFamilyEffect.OpenFamily)
        }
    }

    /** Handles what the person did. Back is the route's (ignored while [SetUpFamilyUiState.busy]). */
    fun onEvent(event: SetUpFamilyEvent) {
        when (event) {
            is SetUpFamilyEvent.NameChanged -> {
                val text = event.text.take(GroupText.MAX_GROUP_NAME)
                savedState[NAME_KEY] = text
                mutableState.update { it.copy(name = text, nameError = cleaned(text).isEmpty()) }
            }
            SetUpFamilyEvent.Invite -> invite()
            SetUpFamilyEvent.Back -> Unit
        }
    }

    private fun invite() {
        val current = mutableState.value
        if (current.busy) return
        val name = cleaned(current.name)
        if (name.isEmpty()) {
            mutableState.update { it.copy(nameError = true) }
            return
        }
        if (!online.value) {
            refused(ActionProblem.OFFLINE)
            return
        }
        mutableState.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            if (createdId == null) {
                when (val made = family.create(name)) {
                    is FamilyResult.Ok -> createdId = made.value
                    FamilyResult.AlreadyInFamily -> {
                        mutableState.update { it.copy(busy = false) }
                        effects.send(SetUpFamilyEffect.OpenFamily)
                        return@launch
                    }
                    else -> {
                        stopped(made)
                        return@launch
                    }
                }
            }
            when (val link = family.inviteLink()) {
                is FamilyResult.Ok -> {
                    val info = family.observeFamily().first()
                    createdId = null
                    // Busy stays on: the screen leaves for Home.
                    effects.send(SetUpFamilyEffect.Share(FamilyInvite.of(link.value, info?.me?.name.orEmpty(), info?.name ?: name)))
                    effects.send(SetUpFamilyEffect.Done)
                }
                else -> stopped(link)
            }
        }
    }

    /** A step that did not happen: the banner it gives, or nothing for a session that ended (the gate opens Sign in). */
    private fun stopped(result: FamilyResult<*>) {
        when (result) {
            FamilyResult.Offline -> refused(ActionProblem.OFFLINE)
            FamilyResult.SignedOut -> mutableState.update { it.copy(busy = false) }
            else -> refused(ActionProblem.FAILED)
        }
    }

    private fun refused(problem: ActionProblem) {
        mutableState.update { it.copy(busy = false, problem = problem, problemCount = it.problemCount + 1) }
    }

    private fun cleaned(text: String): String = GroupText.clean(text, GroupText.MAX_GROUP_NAME)

    private companion object {
        const val NAME_KEY = "set_up_family_name"
        const val CREATED_KEY = "set_up_family_created"
    }
}
