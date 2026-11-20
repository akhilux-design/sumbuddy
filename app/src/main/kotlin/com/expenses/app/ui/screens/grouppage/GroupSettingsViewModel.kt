package com.expenses.app.ui.screens.grouppage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.data.ActionResult
import com.expenses.app.groups.data.GroupDetail
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupText
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Group settings of [groupId] (design 02-everyday/25, 45, 46; docs/groups-build-plan.md 4.2 tasks 7 to 9): the name,
 * the people with the owner's tag and "link sent", Add someone, Leave group and Delete group, and the friend's link
 * sheet.
 *
 * Leave and Delete are tappable only at a 0 balance and ask first; they, and Turn off link, need the server at once:
 * offline the screen stays as it is with the offline banner. When the group leaves this phone (this screen's Leave or
 * Delete, or a sync), the state is [GroupSettingsUiState.Gone] and the screen leaves for the Groups tab.
 *
 * @param groupId the group.
 * @param groups the group data.
 * @param online whether the phone is online now.
 * @param sync asks for a sync when the server says a balance changed meanwhile.
 * @param writeScope a scope that outlives the screen, so a rename made while leaving is still saved.
 */
class GroupSettingsViewModel(
    private val groupId: String,
    private val groups: GroupsRepository,
    private val online: StateFlow<Boolean>,
    private val sync: SyncScheduler,
    private val writeScope: CoroutineScope,
) : ViewModel() {
    /** What the screen adds to the stored group. */
    private data class Local(
        val editedName: String? = null,
        val savedName: String? = null,
        val nameError: Boolean = false,
        val notice: GroupNotice? = null,
        val confirm: SettingsConfirm? = null,
        val sheetMemberId: String? = null,
        val busy: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val effects = Channel<GroupSettingsEffect>(Channel.BUFFERED)

    /** Screens to open, once each. */
    val effect: Flow<GroupSettingsEffect> = effects.receiveAsFlow()

    /** The screen's state. */
    val state: StateFlow<GroupSettingsUiState> = combine(
        groups.observeGroup(groupId),
        groups.observeLinkedMembers(groupId),
        local,
    ) { detail, linked, local -> if (detail == null) GroupSettingsUiState.Gone else shown(detail, linked, local) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), GroupSettingsUiState.Loading)

    init {
        viewModelScope.launch {
            online.collect { isOnline ->
                if (isOnline) local.update { if (it.notice == GroupNotice.Offline) it.copy(notice = null) else it }
            }
        }
    }

    /** Handles what the person did. Back and AddSomeone are the caller's (navigation only). */
    fun onEvent(event: GroupSettingsEvent) {
        when (event) {
            is GroupSettingsEvent.NameChanged -> local.update {
                it.copy(editedName = event.text.take(GroupText.MAX_GROUP_NAME), nameError = false)
            }
            GroupSettingsEvent.NameDone -> saveName()
            is GroupSettingsEvent.PersonTapped -> openSheet(event.memberId)
            // Remind on WhatsApp opens over this screen (the caller's): the sheet is closed when the person is back.
            GroupSettingsEvent.CloseSheet, is GroupSettingsEvent.Remind -> local.update { it.copy(sheetMemberId = null) }
            GroupSettingsEvent.TurnOffLink -> turnOffLink()
            GroupSettingsEvent.Leave -> ask(SettingsConfirm.Leave)
            GroupSettingsEvent.Delete -> ask(SettingsConfirm.Delete)
            GroupSettingsEvent.CloseConfirm -> local.update { it.copy(confirm = null) }
            GroupSettingsEvent.Confirm -> confirm()
            GroupSettingsEvent.Back, GroupSettingsEvent.AddSomeone -> Unit
        }
    }

    /** Leaving the screen saves a changed name too. */
    override fun onCleared() {
        saveName()
    }

    // ---- The name ----

    /** Renames the group when the name changed and is not blank. Queued: it works offline. */
    private fun saveName() {
        val current = local.value
        val typed = current.editedName ?: return
        val clean = GroupText.clean(typed, GroupText.MAX_GROUP_NAME)
        if (clean.isEmpty()) {
            local.update { it.copy(nameError = true) }
            return
        }
        if (clean == current.savedName) return
        local.update { it.copy(savedName = clean) }
        writeScope.launch {
            val detail = groups.observeGroup(groupId).first() ?: return@launch
            if (detail.group.kind == GroupKind.GROUP && detail.group.name != clean) groups.renameGroup(groupId, clean)
        }
    }

    // ---- The friend's link ----

    /**
     * Any friend without the app opens the sheet: 46 when this phone made their link, the same sheet with "No link from
     * this phone" otherwise.
     */
    private fun openSheet(memberId: String) {
        val shown = state.value as? GroupSettingsUiState.Shown ?: return
        if (shown.people.any { it.memberId == memberId && it.kind.isFriend }) {
            local.update { it.copy(sheetMemberId = memberId) }
        }
    }

    /**
     * Turns off every link of the sheet's friend, whoever made it: with or without a link from this phone, as any
     * member can make one (the server's Turn off link stops them all).
     */
    private fun turnOffLink() {
        val memberId = local.value.sheetMemberId ?: return
        if ((state.value as? GroupSettingsUiState.Shown)?.sheet == null) return
        runOnline(closeSheet = true) { groups.turnOffLinks(groupId, memberId) }
    }

    private val PersonKind.isFriend: Boolean get() = this == PersonKind.Friend || this == PersonKind.FriendLinkSent

    // ---- Leave and delete ----

    private fun ask(confirm: SettingsConfirm) {
        val shown = state.value as? GroupSettingsUiState.Shown ?: return
        val allowed = when (confirm) {
            SettingsConfirm.Leave -> shown.canLeave
            SettingsConfirm.Delete -> (shown.delete as? DeleteRow.Owner)?.canDelete == true
        }
        if (allowed) local.update { it.copy(confirm = confirm) }
    }

    private fun confirm() {
        val confirm = local.value.confirm ?: return
        runOnline(closeSheet = false) {
            when (confirm) {
                SettingsConfirm.Leave -> groups.leaveGroup(groupId)
                SettingsConfirm.Delete -> groups.deleteGroup(groupId)
            }
        }
    }

    /**
     * Runs an online-only [action]: offline (known before, or found out), the screen stays with the offline banner;
     * a refusal shows the failure banner; balances that changed meanwhile pull the group again, so the rows re-read
     * and stop being tappable; signed out opens Sign in. Done needs nothing here: the group leaving this phone (or the
     * link) changes the rows.
     */
    private fun runOnline(closeSheet: Boolean, action: suspend () -> ActionResult) {
        if (local.value.busy) return
        if (!online.value) {
            local.update { it.copy(notice = GroupNotice.Offline, confirm = null, sheetMemberId = if (closeSheet) null else it.sheetMemberId) }
            return
        }
        // The dialog closes at once; the sheet stays until the answer.
        local.update { it.copy(busy = true, notice = null, confirm = null) }
        viewModelScope.launch {
            val result = action()
            if (result == ActionResult.SignedOut) effects.send(GroupSettingsEffect.SignIn)
            if (result == ActionResult.NotSettled) sync.requestSync()
            local.update {
                it.copy(
                    busy = false,
                    notice = if (result == ActionResult.NotSettled) null else noticeOf(result),
                    confirm = null,
                    sheetMemberId = if (closeSheet) null else it.sheetMemberId,
                )
            }
        }
    }

    // ---- State ----

    private fun shown(detail: GroupDetail, linked: Set<String>, local: Local): GroupSettingsUiState.Shown {
        val active = detail.members.filter { !it.member.removed }
        val isDirect = detail.group.kind == GroupKind.DIRECT
        // As 25 and 45 draw People: the person first, the owner right after them (when it is someone else), then
        // everyone else in the order the group gives them. A direct split has no owner to put forward.
        val ownerId = detail.ownerMemberId.takeIf { !isDirect }
        val ordered = active.sortedBy { row ->
            when {
                row.isMe -> 0
                row.member.id == ownerId -> 1
                else -> 2
            }
        }
        val people = ordered.map { row ->
            val member = row.member
            PersonRow(
                memberId = member.id,
                name = member.name,
                kind = when {
                    row.isMe -> PersonKind.You
                    member.onApp -> PersonKind.OnApp
                    member.id in linked -> PersonKind.FriendLinkSent
                    else -> PersonKind.Friend
                },
                // A direct split has no owner to speak of (nothing in it needs one): no tag (not designed).
                isOwner = member.id == ownerId,
            )
        }
        val owner = active.firstOrNull { it.member.id == detail.ownerMemberId }?.member
        val delete = when {
            isDirect -> null
            detail.isOwner -> {
                // The owner may also delete while nobody else is on the app (docs/server.md 4.6): an import of the
                // wrong file, or a group of friends without the app, can be removed though nobody can settle it.
                val allSettled = detail.members.all { it.netMinor == 0L }
                val nobodyElseJoined = active.none { !it.isMe && it.member.onApp }
                DeleteRow.Owner(canDelete = allSettled || nobodyElseJoined, nobodyElseJoined = !allSettled && nobodyElseJoined)
            }
            owner != null -> DeleteRow.NotOwner(ownerName = owner.name)
            else -> null
        }
        val sheet = local.sheetMemberId?.let { id ->
            people.firstOrNull { it.memberId == id && it.kind.isFriend }?.let { person ->
                FriendSheet(
                    memberId = person.memberId,
                    name = person.name,
                    linkOn = person.kind == PersonKind.FriendLinkSent,
                    // Remind on WhatsApp sends what the friend owes: only for a friend who owes the person.
                    canRemind = active.any { it.member.id == id && it.pairMinor > 0 },
                )
            }
        }
        return GroupSettingsUiState.Shown(
            groupName = detail.name,
            isDirect = isDirect,
            nameField = local.editedName ?: detail.name,
            nameError = local.nameError,
            people = people,
            canLeave = detail.myNetMinor == 0L,
            delete = delete,
            notice = local.notice,
            confirm = local.confirm,
            sheet = sheet,
            busy = local.busy,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
