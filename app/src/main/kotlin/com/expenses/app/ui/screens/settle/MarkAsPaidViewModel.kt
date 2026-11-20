package com.expenses.app.ui.screens.settle

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.data.GroupDetail
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.format.shortName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How the friend paid the person, on Mark as paid. */
enum class PaidHow {
    /** "Cash": "Rahul handed it over." */
    Cash,

    /** "Bank transfer or other": "It did not arrive as a UPI SMS." */
    Other,
}

/** What Mark as paid (design 03-getting-paid-back/01-mark-as-paid) shows. */
sealed interface MarkAsPaidUiState {
    /** The group is being read. */
    data object Loading : MarkAsPaidUiState

    /** Nothing to mark: the group or the friend is gone, or they no longer owe the person. The caller leaves. */
    data object Gone : MarkAsPaidUiState

    /** It was recorded. The caller leaves. */
    data object Done : MarkAsPaidUiState

    /**
     * What a friend owes the person in one group.
     *
     * @param name the friend.
     * @param amountMinor what they owe, in paise.
     * @param groupName the group's name, or null for a direct split.
     * @param canConfirm true when the friend is on the app: they get a note and can confirm.
     * @param how the option picked.
     * @param avatar the friend's colour, the same on every screen (`MemberBalance.avatar`).
     */
    @Immutable
    data class Shown(
        val name: String,
        val amountMinor: Long,
        val groupName: String?,
        val canConfirm: Boolean,
        val how: PaidHow,
        val avatar: AvatarColor,
    ) : MarkAsPaidUiState {
        /** The friend's initial for the avatar. */
        val initial: String get() = initialOf(name)
    }
}

/** Everything the person can do on Mark as paid. */
sealed interface MarkAsPaidEvent {
    /** Back. */
    data object Back : MarkAsPaidEvent

    /** An option card was tapped. */
    data class Choose(val how: PaidHow) : MarkAsPaidEvent

    /** "Mark ₹1,200 as paid". */
    data object Confirm : MarkAsPaidEvent
}

/**
 * Mark as paid for [memberId] in [groupId]: the friend paid the person in cash or another way. "Mark ₹1,200 as paid"
 * records the settlement from them to the person (`CASH` or `OTHER`; queued, so it works offline) and leaves.
 */
class MarkAsPaidViewModel(
    private val groupId: String,
    private val memberId: String,
    private val groups: GroupsRepository,
) : ViewModel() {
    private val how = MutableStateFlow(PaidHow.Cash)
    private val done = MutableStateFlow(false)
    private var busy = false

    /** The screen's state. */
    val state: StateFlow<MarkAsPaidUiState> = combine(groups.observeGroup(groupId), how, done) { detail, picked, recorded ->
        if (recorded) MarkAsPaidUiState.Done else shownOf(detail, picked)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), MarkAsPaidUiState.Loading)

    /** Handles what the person did. Back is handled by the caller. */
    fun onEvent(event: MarkAsPaidEvent) {
        when (event) {
            MarkAsPaidEvent.Back -> Unit
            is MarkAsPaidEvent.Choose -> how.value = event.how
            MarkAsPaidEvent.Confirm -> confirm()
        }
    }

    private fun shownOf(detail: GroupDetail?, picked: PaidHow): MarkAsPaidUiState {
        if (detail == null) return MarkAsPaidUiState.Gone
        if (detail.load == LoadState.LOADING) return MarkAsPaidUiState.Loading
        val friend = detail.members.firstOrNull { it.member.id == memberId && !it.isMe } ?: return MarkAsPaidUiState.Gone
        if (friend.pairMinor <= 0) return MarkAsPaidUiState.Gone
        return MarkAsPaidUiState.Shown(
            name = shortName(friend.member.name, detail.members.map { it.member.name }),
            amountMinor = friend.pairMinor,
            groupName = detail.name.takeIf { detail.group.kind == GroupKind.GROUP },
            canConfirm = friend.member.onApp,
            how = picked,
            avatar = friend.avatar,
        )
    }

    private fun confirm() {
        if (busy) return
        val shown = state.value as? MarkAsPaidUiState.Shown ?: return
        busy = true
        viewModelScope.launch {
            try {
                val myMemberId = groups.observeGroup(groupId).first()?.group?.myMemberId ?: return@launch
                val method = if (shown.how == PaidHow.Cash) SettleMethod.CASH else SettleMethod.OTHER
                groups.recordSettlement(groupId, memberId, myMemberId, shown.amountMinor, method, matchedFromSms = false)
                done.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalArgumentException) {
                // The group went away meanwhile; the screen follows it.
            } finally {
                busy = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
