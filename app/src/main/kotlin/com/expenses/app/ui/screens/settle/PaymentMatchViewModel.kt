package com.expenses.app.ui.screens.settle

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseWithAccount
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.groups.data.GroupDetail
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.match.CreditMatch
import com.expenses.app.groups.match.CreditMatching
import com.expenses.app.groups.match.OwingFriend
import com.expenses.app.groups.match.SettlementMatcher
import com.expenses.app.groups.match.observeGroupDetails
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.format.shortName
import com.expenses.parser.Direction
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the friend's balance on Money received is for. */
sealed interface OwedFor {
    /** One named group: "owes you for Flat 302". */
    data class Group(val name: String) : OwedFor

    /** A direct split: "owes you". */
    data object Direct : OwedFor

    /** Several groups: "owes you for 2 groups". */
    data class Several(val count: Int) : OwedFor
}

/** What Money received (design 03-getting-paid-back/02-payment-match) shows. */
sealed interface PaymentMatchUiState {
    /** The payment and the groups are being read. */
    data object Loading : PaymentMatchUiState

    /** The payment is gone, is not money in, or no longer matches what a friend owes. */
    data object Gone : PaymentMatchUiState

    /**
     * "Yes, clear it" was done: the caller opens All square for [memberId] in [groupId] (the largest transfer).
     *
     * @param amountMinor what arrived, in paise.
     */
    data class Cleared(val groupId: String, val memberId: String, val amountMinor: Long) : PaymentMatchUiState

    /**
     * Money in that may be a friend paying back.
     *
     * @param amountMinor what arrived, in paise.
     * @param accountName the account it arrived in (its name, else its bank), or null.
     * @param accountLast4 the account's last digits, or null.
     * @param minutesAgo how long ago it arrived, in whole minutes.
     * @param friendName the friend it may be.
     * @param owedFor what they owe for.
     * @param owedMinor what they owe, in paise (the same as [amountMinor]).
     * @param friendAvatar the friend's colour, the same on every screen (`MemberBalance.avatar`).
     */
    @Immutable
    data class Shown(
        val amountMinor: Long,
        val accountName: String?,
        val accountLast4: String?,
        val minutesAgo: Long,
        val friendName: String,
        val owedFor: OwedFor,
        val owedMinor: Long,
        val friendAvatar: AvatarColor,
    ) : PaymentMatchUiState {
        /** The friend's initial for the avatar. */
        val friendInitial: String get() = initialOf(friendName)
    }
}

/** Everything the person can do on Money received. */
sealed interface PaymentMatchEvent {
    /** Back. */
    data object Back : PaymentMatchEvent

    /** "Yes, clear it". */
    data object Yes : PaymentMatchEvent

    /** "No, something else": leaves, nothing changes. */
    data object No : PaymentMatchEvent
}

/**
 * Money received for the money-in payment [creditId]: "Was this Rahul paying you back?". The friend is found again
 * the way the settlement matcher found them ([CreditMatching]). "Yes, clear it" records one settlement per group they
 * owe in (matched from the SMS, so confirmed at once) and opens All square.
 */
class PaymentMatchViewModel(
    private val creditId: Long,
    private val groups: GroupsRepository,
    expenses: ExpenseRepository,
    private val clock: Clock,
) : ViewModel() {
    private val cleared = MutableStateFlow<PaymentMatchUiState.Cleared?>(null)

    /** True while "Yes, clear it" writes: the balance closes first, and the screen keeps showing it until done. */
    private val clearing = MutableStateFlow(false)
    private var lastShown: PaymentMatchUiState.Shown? = null
    private var current: Pair<Expense, OwingFriend>? = null

    /** The screen's state. */
    val state: StateFlow<PaymentMatchUiState> =
        combine(expenses.observe(creditId), groups.observeGroupDetails(), cleared, clearing) { row, details, done, writing ->
            when {
                done != null -> done
                writing -> lastShown ?: PaymentMatchUiState.Loading
                else -> shownOf(row, details).also { if (it is PaymentMatchUiState.Shown) lastShown = it }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PaymentMatchUiState.Loading)

    /** Handles what the person did. Back and No are handled by the caller. */
    fun onEvent(event: PaymentMatchEvent) {
        when (event) {
            PaymentMatchEvent.Yes -> clear()
            PaymentMatchEvent.Back, PaymentMatchEvent.No -> Unit
        }
    }

    private fun shownOf(row: ExpenseWithAccount?, details: List<GroupDetail>): PaymentMatchUiState {
        val credit = row?.expense
        // Money into an account the person switched off is not the app's business: an older "Was this ... ?" opens nothing.
        if (credit == null || credit.direction != Direction.CREDIT || credit.currency != Expense.INR || row.account?.tracked == false) {
            current = null
            return PaymentMatchUiState.Gone
        }
        val friends = CreditMatching.owingFriends(details)
        val friend = when (val match = CreditMatching.match(credit.amountMinor, credit.counterparty, credit.counterpartyVpa, friends)) {
            CreditMatch.None -> null
            is CreditMatch.Ask -> match.friend
            is CreditMatch.Clear -> match.friend
        }
        if (friend == null) {
            current = null
            return PaymentMatchUiState.Gone
        }
        // The colour of the friend's place in the group they owe most in: the same as on its Group page and on Groups.
        val avatar = details.firstOrNull { it.group.id == friend.largest.groupId }
            ?.members?.firstOrNull { it.member.id == friend.largest.memberId }?.avatar
        if (avatar == null) {
            current = null
            return PaymentMatchUiState.Gone
        }
        current = credit to friend
        val account = row.account
        val receivedAt = credit.smsReceivedAt ?: credit.occurredAt
        return PaymentMatchUiState.Shown(
            amountMinor = credit.amountMinor,
            accountName = account?.displayName?.takeIf(String::isNotBlank) ?: account?.bank ?: account?.upiId,
            accountLast4 = account?.last4,
            minutesAgo = Duration.between(receivedAt, clock.instant()).toMinutes().coerceAtLeast(0),
            friendName = shortName(friend.name, details.flatMap { d -> d.members.map { it.member.name } }.distinct()),
            owedFor = owedFor(friend),
            owedMinor = friend.totalMinor,
            friendAvatar = avatar,
        )
    }

    private fun clear() {
        if (clearing.value || cleared.value != null) return
        val (credit, friend) = current ?: return
        clearing.value = true
        viewModelScope.launch {
            try {
                val settled = SettlementMatcher.settleCredit(groups, friend, credit, clock.zone)
                val largest = settled.maxByOrNull { it.amountMinor }
                if (largest != null) cleared.value = PaymentMatchUiState.Cleared(largest.groupId, largest.memberId, credit.amountMinor)
            } finally {
                clearing.value = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        fun owedFor(friend: OwingFriend): OwedFor {
            val single = friend.transfers.singleOrNull() ?: return OwedFor.Several(friend.transfers.size)
            return if (single.groupKind == GroupKind.GROUP) OwedFor.Group(single.groupName) else OwedFor.Direct
        }
    }
}
