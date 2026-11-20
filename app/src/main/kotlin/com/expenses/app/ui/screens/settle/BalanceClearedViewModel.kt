package com.expenses.app.ui.screens.settle

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.data.FriendBalance
import com.expenses.app.groups.data.GroupDetail
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.groups.data.personKeyOf
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.format.shortName
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What All square (design 03-getting-paid-back/03-balance-cleared) shows. */
sealed interface BalanceClearedUiState {
    /** The group is being read. */
    data object Loading : BalanceClearedUiState

    /** The group or the friend is no longer on this phone. The caller goes to Home. */
    data object Gone : BalanceClearedUiState

    /**
     * A friend's money arrived and closed what they owed.
     *
     * @param name the friend.
     * @param amountMinor what arrived, in paise.
     * @param byUpi true when it arrived by UPI ("arrived by UPI"), false for a bank transfer.
     * @param friendNetMinor the friend's balance with the person over every group now: 0 when all settled, above 0
     * when they still owe the person elsewhere, below 0 when the person owes them.
     * @param avatar the friend's colour, the same on every screen (`MemberBalance.avatar`).
     */
    @Immutable
    data class Shown(
        val name: String,
        val amountMinor: Long,
        val byUpi: Boolean,
        val friendNetMinor: Long,
        val avatar: AvatarColor,
    ) : BalanceClearedUiState {
        /** The friend's initial for the avatar. */
        val initial: String get() = initialOf(name)
    }
}

/**
 * All square for the friend [memberId] of [groupId] after [amountMinor] arrived. One credit that cleared the friend in
 * several groups opens this once, naming the group with the largest transfer; the Balance Row shows the friend over
 * every group (`observeFriends()` by the friend's key; not listed means all settled), so it reads right whatever group
 * the link names.
 */
class BalanceClearedViewModel(
    private val groupId: String,
    private val memberId: String,
    private val amountMinor: Long,
    groups: GroupsRepository,
) : ViewModel() {
    /** The screen's state. */
    val state: StateFlow<BalanceClearedUiState> = combine(groups.observeGroup(groupId), groups.observeFriends()) { detail, friends ->
        shownOf(detail, friends)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BalanceClearedUiState.Loading)

    private fun shownOf(detail: GroupDetail?, friends: List<FriendBalance>): BalanceClearedUiState {
        val friend = detail?.members?.firstOrNull { it.member.id == memberId && !it.isMe } ?: return BalanceClearedUiState.Gone
        val member = friend.member
        val key = personKeyOf(member)
        val me = detail.group.myMemberId
        val matched = detail.settlements
            .filter { it.fromMemberId == memberId && it.toMemberId == me && it.matchedFromSms }
            .maxByOrNull { it.occurredOn }
        return BalanceClearedUiState.Shown(
            name = shortName(member.name, detail.members.map { it.member.name }),
            amountMinor = amountMinor,
            byUpi = matched == null || matched.method == SettleMethod.UPI,
            friendNetMinor = friends.firstOrNull { it.key == key }?.netMinor ?: 0L,
            avatar = friend.avatar,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
