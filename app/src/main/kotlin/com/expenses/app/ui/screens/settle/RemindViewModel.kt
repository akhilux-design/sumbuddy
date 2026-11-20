package com.expenses.app.ui.screens.settle

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.data.GroupDetail
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.sync.NetworkStatus
import com.expenses.app.ui.format.shortName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What Remind on WhatsApp (design 03-getting-paid-back/05-remind-on-whatsapp) shows. */
sealed interface RemindUiState {
    /** The group is being read. */
    data object Loading : RemindUiState

    /** Nothing to remind: the group or the friend is gone, or they no longer owe the person. The caller leaves. */
    data object Gone : RemindUiState

    /**
     * The reminder for one friend.
     *
     * @param friendName the friend who owes.
     * @param myName the person's own name, as the message signs it.
     * @param amountMinor what the friend owes the person, in paise.
     * @param groupName the group's name, or null for a direct split.
     * @param link the friend's pay link, or null until it is made (it needs the server).
     * @param problem why the link could not be made just now, shown as a banner; null for none.
     */
    @Immutable
    data class Shown(
        val friendName: String,
        val myName: String,
        val amountMinor: Long,
        val groupName: String?,
        val link: String?,
        val problem: SettleProblem? = null,
    ) : RemindUiState
}

/** Everything the person can do on Remind on WhatsApp. */
sealed interface RemindEvent {
    /** Back. */
    data object Back : RemindEvent

    /** "Send on WhatsApp". */
    data object Send : RemindEvent

    /** "Copy link". */
    data object Copy : RemindEvent
}

/** One-shot messages from Remind on WhatsApp to its screen. */
sealed interface RemindEffect {
    /** Send the message with [link] from the person's WhatsApp. */
    data class Send(val link: String) : RemindEffect

    /** Put [link] on the clipboard. */
    data class Copy(val link: String) : RemindEffect

    /** The session ended: open Sign in. */
    data object SignIn : RemindEffect
}

/**
 * Remind on WhatsApp for the friend [memberId] of [groupId]: the message and the friend's pay link
 * (`groups.shareLink`, the real link on `BuildConfig.LINK_HOST`), sent from the person's own WhatsApp.
 *
 * Making the link needs the server. Offline (or `network.online` false before trying), the screen stays as it is and
 * shows the offline banner; the link is made as soon as the phone is back online, and Send or Copy tries again. The
 * link is never logged.
 *
 * @param myName the person's own name from their profile, or null to use their name in the group.
 */
class RemindViewModel(
    private val groupId: String,
    private val memberId: String,
    private val groups: GroupsRepository,
    private val network: NetworkStatus,
    private val myName: suspend () -> String?,
) : ViewModel() {
    private val link = MutableStateFlow<String?>(null)
    private val problem = MutableStateFlow<SettleProblem?>(null)
    private val profileName = MutableStateFlow<String?>(null)
    private var loading = false

    /** What Send or Copy asked for while the link was being made: run once it is there. */
    private var afterLoad: ((String) -> Unit)? = null

    private val effects = SettleEffects<RemindEffect>()

    /** One-shot messages for the screen. */
    val effectFlow: Flow<RemindEffect> = effects.flow

    /** The screen's state. */
    val state: StateFlow<RemindUiState> =
        combine(groups.observeGroup(groupId), link, problem, profileName) { detail, made, trouble, name ->
            shownOf(detail, made, trouble, name)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RemindUiState.Loading)

    init {
        viewModelScope.launch { profileName.value = myName()?.takeIf(String::isNotBlank) }
        viewModelScope.launch {
            // Only for a friend who owes the person; then, online now or again, make the link if it is still missing.
            groups.observeGroup(groupId).first { detail -> detail?.members?.any { it.member.id == memberId && !it.isMe && it.pairMinor > 0 } == true }
            network.online.filter { it }.collect {
                if (link.value == null) loadLink(then = null)
            }
        }
        if (!network.online.value) problem.value = SettleProblem.Offline
    }

    /** Handles what the person did. Back is handled by the caller. */
    fun onEvent(event: RemindEvent) {
        when (event) {
            RemindEvent.Back -> Unit
            RemindEvent.Send -> withLink { effects.send(RemindEffect.Send(it)) }
            RemindEvent.Copy -> withLink { effects.send(RemindEffect.Copy(it)) }
        }
    }

    private fun shownOf(detail: GroupDetail?, made: String?, trouble: SettleProblem?, name: String?): RemindUiState {
        if (detail == null) return RemindUiState.Gone
        if (detail.load == LoadState.LOADING) return RemindUiState.Loading
        val friend = detail.members.firstOrNull { it.member.id == memberId && !it.isMe } ?: return RemindUiState.Gone
        if (friend.pairMinor <= 0) return RemindUiState.Gone
        val me = detail.members.firstOrNull { it.isMe }?.member?.name.orEmpty()
        // First names, as the design writes them in a sentence ("Remind Priya", "Hey Priya You owe Arjun ...").
        val names = detail.members.map { it.member.name }
        return RemindUiState.Shown(
            friendName = shortName(friend.member.name, names),
            myName = shortName(name ?: me, names),
            amountMinor = friend.pairMinor,
            groupName = detail.name.takeIf { detail.group.kind == GroupKind.GROUP },
            link = made,
            problem = trouble,
        )
    }

    /** Runs [then] with the link, making it first when needed. */
    private fun withLink(then: (String) -> Unit) {
        val made = link.value
        if (made != null) {
            then(made)
            return
        }
        if (!network.online.value) {
            problem.value = SettleProblem.Offline
            return
        }
        loadLink(then)
    }

    /**
     * Makes the link, then runs [then] with it. A Send or Copy tapped while the link is already being made (it starts
     * as soon as the screen opens online) waits for that request instead of being dropped; the last tap wins.
     */
    private fun loadLink(then: ((String) -> Unit)?) {
        if (then != null) afterLoad = then
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                when (val result = groups.shareLink(groupId, memberId)) {
                    is ApiResult.Ok -> {
                        link.value = result.value
                        problem.value = null
                        afterLoad?.invoke(result.value)
                    }
                    is ApiResult.Error -> when {
                        result.error.code == ApiError.SIGNED_OUT -> effects.send(RemindEffect.SignIn)
                        result.error.isOffline -> problem.value = SettleProblem.Offline
                        else -> problem.value = SettleProblem.Failed
                    }
                }
            } finally {
                afterLoad = null
                loading = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
