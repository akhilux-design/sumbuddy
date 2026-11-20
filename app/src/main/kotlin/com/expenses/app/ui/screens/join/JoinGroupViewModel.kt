package com.expenses.app.ui.screens.join

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.data.FamilyRepository
import com.expenses.app.groups.data.FamilyResult
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.ui.screens.signin.ActionProblem
import com.expenses.app.ui.screens.signin.actionProblem
import java.time.Clock
import java.time.YearMonth
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Join group for a friend's link (design 02-everyday/32), shown only while signed in (the route's `SignInGate`).
 *
 * [start] looks the group up (`GET /v1/invites/{token}`). Someone already in the group goes straight to it. "Join
 * group" takes the friend's place (`POST .../accept`, online only) and then opens the group. A link that was turned
 * off, ran out or was used by someone else shows "This link no longer works" (50). Offline, the lookup shows the
 * wording of 02-everyday/23 with Try again, and a join is not tried: the banner says so and the screen stays.
 *
 * @param token the link's token. A secret: never logged or shown.
 * @param groups group data.
 * @param online whether the phone can reach the internet (`NetworkStatus.online`).
 * @param clock gives the current month (India time in the app), for "{k} expenses in {Month}".
 * @param family the family on this phone: a family link's preview shows Join Family (06-family/01), and "Join Family"
 * joins through it so Home never shows a frame without the family (docs/family.md 1.4). The person already in a family
 * sees "You're already in a family" (16).
 */
class JoinGroupViewModel(
    private val token: String,
    private val groups: GroupsRepository,
    private val online: StateFlow<Boolean>,
    private val clock: Clock,
    private val family: FamilyRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<JoinGroupUiState>(JoinGroupUiState.Loading)
    private val effects = Channel<JoinGroupEffect>(Channel.BUFFERED)
    private var lookup: Job? = null

    /** How many of this phone's Family expenses joining a family shares now (Join Family's "Earlier Family expenses"). */
    private var earlierCount = 0

    init {
        viewModelScope.launch {
            family.observeShareableEarlier().collect { count ->
                earlierCount = count
                mutableState.update { if (it is JoinGroupUiState.ReadyFamily) it.copy(earlierCount = count) else it }
            }
        }
    }

    /** What the screen shows. */
    val state: StateFlow<JoinGroupUiState> = mutableState.asStateFlow()

    /** One-off results for the route. */
    val effect: Flow<JoinGroupEffect> = effects.receiveAsFlow()

    /**
     * The screen is shown, signed in: look the group up, unless it is known already or being looked up. Called again
     * after a sign-in the server asked for, which finds it still loading.
     */
    fun start() {
        if (mutableState.value != JoinGroupUiState.Loading || lookup?.isActive == true) return
        load()
    }

    /** Handles what the person did; Leave is the route's. */
    fun onEvent(event: JoinGroupEvent) {
        when (event) {
            JoinGroupEvent.Join -> if (mutableState.value is JoinGroupUiState.ReadyFamily) joinFamily() else join()
            JoinGroupEvent.TryAgain -> if (mutableState.value is JoinGroupUiState.CouldNotLoad) {
                mutableState.value = JoinGroupUiState.Loading
                load()
            }
            JoinGroupEvent.Leave -> Unit
        }
    }

    private fun load() {
        if (!online.value) {
            mutableState.value = JoinGroupUiState.CouldNotLoad(offline = true)
            return
        }
        lookup = viewModelScope.launch {
            when (val result = groups.previewInvite(token)) {
                is ApiResult.Ok -> {
                    val preview = result.value
                    val isFamily = preview.kind == GroupKind.FAMILY
                    when {
                        isFamily && preview.alreadyMember -> {
                            // Joined on another phone maybe: the family comes to this one with the sync.
                            family.refresh()
                            effects.send(JoinGroupEffect.OpenFamily)
                        }
                        isFamily && preview.alreadyInFamily -> mutableState.value = JoinGroupUiState.AlreadyInFamily
                        isFamily -> mutableState.value = JoinGroupUiState.ReadyFamily(preview, earlierCount)
                        preview.alreadyMember -> effects.send(JoinGroupEffect.OpenGroup(preview.groupId))
                        else -> mutableState.value = JoinGroupUiState.Ready(preview, YearMonth.now(clock))
                    }
                }
                is ApiResult.Error -> {
                    val error = result.error
                    mutableState.value = when {
                        error.code == LINK_NOT_FOUND -> JoinGroupUiState.LinkNoLongerWorks
                        // The session ended: Sign in opens by itself (the gate), and start() looks again after it.
                        error.needsSignIn -> JoinGroupUiState.Loading
                        else -> JoinGroupUiState.CouldNotLoad(offline = error.isOffline)
                    }
                }
            }
        }
    }

    private fun join() {
        val ready = mutableState.value as? JoinGroupUiState.Ready ?: return
        if (ready.joining) return
        if (!online.value) {
            mutableState.value = ready.copy(problem = ActionProblem.OFFLINE, problemCount = ready.problemCount + 1)
            return
        }
        mutableState.value = ready.copy(joining = true, problem = null)
        viewModelScope.launch {
            when (val result = groups.acceptInvite(token)) {
                // Joining stays on until the group opens, so a second tap does nothing.
                is ApiResult.Ok -> effects.send(JoinGroupEffect.OpenGroup(result.value))
                is ApiResult.Error -> {
                    val error = result.error
                    when {
                        error.code == LINK_NOT_FOUND || error.code == ALREADY_CLAIMED ->
                            mutableState.value = JoinGroupUiState.LinkNoLongerWorks
                        error.code == ALREADY_MEMBER -> effects.send(JoinGroupEffect.OpenGroup(ready.preview.groupId))
                        // A group link never answers this; kept so a family link seen as a group still says why.
                        error.code == FamilyRepository.ALREADY_IN_FAMILY -> mutableState.value = JoinGroupUiState.AlreadyInFamily
                        // The session ended: Sign in opens by itself (the gate); the preview stays for after it.
                        error.needsSignIn -> mutableState.value = ready.copy(joining = false)
                        else -> mutableState.value =
                            ready.copy(joining = false, problem = error.actionProblem(), problemCount = ready.problemCount + 1)
                    }
                }
            }
        }
    }

    /**
     * "Join Family" (docs/family.md 1.4): online only. Joined, or in the family already, Home opens on the Family side;
     * `409 already_in_family` shows "You're already in a family"; a link that no longer works shows 50.
     */
    private fun joinFamily() {
        val ready = mutableState.value as? JoinGroupUiState.ReadyFamily ?: return
        if (ready.joining) return
        if (!online.value) {
            mutableState.value = ready.copy(problem = ActionProblem.OFFLINE, problemCount = ready.problemCount + 1)
            return
        }
        mutableState.value = ready.copy(joining = true, problem = null)
        viewModelScope.launch {
            when (val result = family.join(token, ready.preview.groupName)) {
                // Joining stays on until Home opens, so a second tap does nothing.
                is FamilyResult.Ok -> effects.send(JoinGroupEffect.OpenFamily)
                FamilyResult.AlreadyInFamily -> mutableState.value = JoinGroupUiState.AlreadyInFamily
                // The session ended: Sign in opens by itself (the gate); the preview stays for after it.
                FamilyResult.SignedOut -> mutableState.value = ready.copy(joining = false)
                FamilyResult.Offline -> mutableState.value =
                    ready.copy(joining = false, problem = ActionProblem.OFFLINE, problemCount = ready.problemCount + 1)
                is FamilyResult.Failed -> when (result.code) {
                    LINK_NOT_FOUND, ALREADY_CLAIMED -> mutableState.value = JoinGroupUiState.LinkNoLongerWorks
                    ALREADY_MEMBER -> effects.send(JoinGroupEffect.OpenFamily)
                    else -> mutableState.value =
                        ready.copy(joining = false, problem = ActionProblem.FAILED, problemCount = ready.problemCount + 1)
                }
            }
        }
    }

    private companion object {
        /** The link was turned off, ran out, or never existed (docs/server.md 4.10). */
        const val LINK_NOT_FOUND = "link_not_found"

        /** Someone else took the friend's place with this link already. */
        const val ALREADY_CLAIMED = "already_claimed"

        /** The person is already in the group as another member. */
        const val ALREADY_MEMBER = "already_member"
    }
}
