package com.expenses.app.ui.screens.family

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.data.ActionResult
import com.expenses.app.groups.data.FamilyInfo
import com.expenses.app.groups.data.FamilyRepository
import com.expenses.app.groups.data.FamilyResult
import com.expenses.app.groups.data.GroupText
import com.expenses.app.ui.screens.signin.ActionProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Family settings (design 06-family/17 to 24; docs/family.md 1.6): the family's name, the person and the partner (or
 * the invite, or Invite partner), the invite sheet, and Leave Family.
 *
 * The name is saved when the person leaves the field or the screen; renaming is queued and works offline (blank: not
 * saved, "That does not look right."). Everything else needs the server at once: Send link again, Cancel invite,
 * Invite partner, Remove (the owner only) and Leave. Offline (known before, or found out) the screen stays with the
 * Offline banner; a refusal shows the Problem banner. Leave Family ends with [FamilySettingsUiState.Left]; the family
 * leaving this phone for any other reason gives [FamilySettingsUiState.Gone].
 *
 * @param family the family on this phone.
 * @param online whether the phone is online now.
 * @param sendLink true when opened by Home's "Send link again" or "Invite partner": that starts as soon as the family
 * is read, once.
 * @param savedState remembers that [sendLink] was done, so a recreated screen does not send it again.
 * @param writeScope a scope that outlives the screen, so a rename made while leaving is still saved.
 */
class FamilySettingsViewModel(
    private val family: FamilyRepository,
    private val online: StateFlow<Boolean>,
    sendLink: Boolean,
    private val savedState: SavedStateHandle,
    private val writeScope: CoroutineScope,
) : ViewModel() {
    /** What the screen adds to the stored family. */
    private data class Local(
        val editedName: String? = null,
        val savedName: String? = null,
        val nameError: Boolean = false,
        val busy: Boolean = false,
        val problem: ActionProblem? = null,
        val problemCount: Int = 0,
        val confirm: FamilyConfirm? = null,
        val sheet: Boolean = false,
        val leaving: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val effects = Channel<FamilySettingsEffect>(Channel.BUFFERED)

    /** The family as last read, for the actions. */
    private var info: FamilyInfo? = null

    /** Screens to open, once each. */
    val effect: Flow<FamilySettingsEffect> = effects.receiveAsFlow()

    /** The screen's state. */
    val state: StateFlow<FamilySettingsUiState> = combine(family.observeFamily(), local) { info, local ->
        this.info = info
        when {
            info != null -> shown(info, local)
            local.leaving -> FamilySettingsUiState.Left
            else -> FamilySettingsUiState.Gone
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), FamilySettingsUiState.Loading)

    init {
        viewModelScope.launch {
            online.collect { isOnline ->
                if (isOnline) local.update { if (it.problem == ActionProblem.OFFLINE) it.copy(problem = null) else it }
            }
        }
        if (sendLink && savedState.get<Boolean>(SENT_KEY) != true) {
            viewModelScope.launch {
                val shown = state.filterIsInstance<FamilySettingsUiState.Shown>().first()
                savedState[SENT_KEY] = true
                if (shown.partner !is FamilyPartnerRow.Joined) shareLink()
            }
        }
    }

    /** Handles what the person did. Back is the caller's (it saves the name first). */
    fun onEvent(event: FamilySettingsEvent) {
        when (event) {
            is FamilySettingsEvent.NameChanged -> local.update {
                it.copy(editedName = event.text.take(GroupText.MAX_GROUP_NAME), nameError = false)
            }
            FamilySettingsEvent.NameDone -> saveName()
            FamilySettingsEvent.PartnerTapped -> partnerTapped()
            FamilySettingsEvent.SendLinkAgain -> if (local.value.sheet) shareLink()
            FamilySettingsEvent.CancelInvite -> if (local.value.sheet) cancelInvite()
            FamilySettingsEvent.CloseSheet -> local.update { it.copy(sheet = false) }
            FamilySettingsEvent.Leave -> if (!local.value.busy) local.update { it.copy(confirm = FamilyConfirm.Leave) }
            FamilySettingsEvent.CloseConfirm -> local.update { it.copy(confirm = null) }
            FamilySettingsEvent.Confirm -> confirm()
            FamilySettingsEvent.Back -> Unit
        }
    }

    /** Leaving the screen saves a changed name too. */
    override fun onCleared() {
        saveName()
    }

    // ---- The name ----

    /** Renames the family when the name changed and is not blank. Queued: it works offline. */
    private fun saveName() {
        val current = local.value
        val typed = current.editedName ?: return
        val clean = GroupText.clean(typed, GroupText.MAX_GROUP_NAME)
        if (clean.isEmpty()) {
            local.update { it.copy(nameError = true) }
            return
        }
        if (clean == current.savedName || clean == info?.name) return
        local.update { it.copy(savedName = clean) }
        writeScope.launch { family.rename(clean) }
    }

    // ---- The partner ----

    private fun partnerTapped() {
        val info = info ?: return
        if (local.value.busy) return
        val partner = info.partner
        when {
            partner != null -> if (info.isOwner) {
                local.update { it.copy(confirm = FamilyConfirm.Remove(partner.memberId, partner.shortName)) }
            }
            info.placeholderId != null -> local.update { it.copy(sheet = true) }
            else -> shareLink()
        }
    }

    /**
     * Makes the partner's link (adding an invite first when there is none) and opens the share sheet with it: Invite
     * partner, Send link again, and Home's buttons.
     */
    private fun shareLink() {
        runOnline(closeSheet = true) {
            when (val link = family.inviteLink()) {
                is FamilyResult.Ok -> {
                    val current = info
                    effects.send(FamilySettingsEffect.Share(FamilyInvite.of(link.value, current?.me?.name.orEmpty(), current?.name.orEmpty())))
                    null
                }
                FamilyResult.Offline -> ActionProblem.OFFLINE
                FamilyResult.SignedOut -> null
                else -> ActionProblem.FAILED
            }
        }
    }

    private fun cancelInvite() {
        val placeholder = info?.placeholderId ?: return
        runOnline(closeSheet = true) { problemOf(family.cancelInvite(placeholder)) }
    }

    // ---- Leave and remove ----

    private fun confirm() {
        when (val confirm = local.value.confirm ?: return) {
            FamilyConfirm.Leave -> runOnline(closeSheet = false, leaving = true) { problemOf(family.leave()) }
            is FamilyConfirm.Remove -> runOnline(closeSheet = false) { problemOf(family.removePartner(confirm.memberId)) }
        }
    }

    private fun problemOf(result: ActionResult): ActionProblem? = when (result) {
        ActionResult.Done, ActionResult.SignedOut -> null
        ActionResult.Offline -> ActionProblem.OFFLINE
        ActionResult.NotSettled, is ActionResult.Failed -> ActionProblem.FAILED
    }

    /**
     * Runs an online-only [action] that gives the banner to show, or null when it was done (or the session ended, and
     * the gate opens Sign in). Offline before it starts, nothing is sent. The dialog closes at once; the sheet stays
     * until the answer.
     *
     * @param leaving true for Leave Family: the family leaving this phone is then the person's own leave.
     */
    private fun runOnline(closeSheet: Boolean, leaving: Boolean = false, action: suspend () -> ActionProblem?) {
        if (local.value.busy) return
        if (!online.value) {
            local.update {
                it.copy(
                    problem = ActionProblem.OFFLINE,
                    problemCount = it.problemCount + 1,
                    confirm = null,
                    sheet = if (closeSheet) false else it.sheet,
                )
            }
            return
        }
        local.update { it.copy(busy = true, problem = null, confirm = null, leaving = leaving) }
        viewModelScope.launch {
            val problem = action()
            local.update {
                it.copy(
                    busy = false,
                    problem = problem,
                    problemCount = if (problem != null) it.problemCount + 1 else it.problemCount,
                    sheet = if (closeSheet) false else it.sheet,
                    leaving = leaving && problem == null,
                )
            }
        }
    }

    // ---- State ----

    private fun shown(info: FamilyInfo, local: Local): FamilySettingsUiState.Shown {
        val partner = info.partner
        return FamilySettingsUiState.Shown(
            familyName = info.name,
            nameField = local.editedName ?: info.name,
            nameError = local.nameError,
            me = info.me.name,
            partner = when {
                partner != null -> FamilyPartnerRow.Joined(partner.memberId, partner.name, partner.shortName, canRemove = info.isOwner)
                info.placeholderId != null -> FamilyPartnerRow.Invited
                else -> FamilyPartnerRow.None
            },
            busy = local.busy,
            problem = local.problem,
            problemCount = local.problemCount,
            confirm = local.confirm,
            // The sheet is for an invite that is out; it closes by itself when the partner joins meanwhile.
            inviteSheet = local.sheet && partner == null && info.placeholderId != null,
        )
    }

    private companion object {
        const val SENT_KEY = "family_settings_link_sent"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
