package com.expenses.app.ui.screens.settle

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.data.GroupDetail
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupText
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.groups.match.SettlementMatcher
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.format.shortName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How the person pays on Settle up. */
enum class SettleChoice {
    /** "Pay by UPI": opens the person's UPI app. */
    Upi,

    /** "Paid another way": marks it as paid. */
    Other,
}

/** What Settle up (design 03-getting-paid-back/00-settle-up, 07-settle-up-no-upi-id) shows. */
sealed interface SettleUpUiState {
    /** The group is being read. */
    data object Loading : SettleUpUiState

    /** Nothing to settle: the group or the friend is gone, or the person no longer owes them. The caller leaves. */
    data object Gone : SettleUpUiState

    /** "Mark ₹1,200 as paid" was recorded. The caller leaves. */
    data object Done : SettleUpUiState

    /**
     * What the person owes one friend in one group.
     *
     * @param name the friend.
     * @param amountMinor what the person owes them, in paise.
     * @param expenseCount the expenses since the two last settled up where one paid and the other has a share
     * (docs/server.md 6.4).
     * @param groupName the group's name, or null for a direct split.
     * @param upiId the friend's UPI ID when Pay by UPI can pay it; null shows 07 (no UPI ID, or not on the app).
     * @param friendOnApp true when the friend is on the app and can confirm.
     * @param choice the option picked.
     * @param noUpiApp true after Pay by UPI found no UPI app on the phone.
     * @param avatar the friend's colour, the same on every screen (`MemberBalance.avatar`).
     */
    @Immutable
    data class Shown(
        val name: String,
        val amountMinor: Long,
        val expenseCount: Int,
        val groupName: String?,
        val upiId: String?,
        val friendOnApp: Boolean,
        val choice: SettleChoice,
        val noUpiApp: Boolean = false,
        val avatar: AvatarColor,
    ) : SettleUpUiState {
        /** The friend's initial for the avatar. */
        val initial: String get() = initialOf(name)
    }
}

/** Everything the person can do on Settle up. */
sealed interface SettleUpEvent {
    /** Back. */
    data object Back : SettleUpEvent

    /** An option card was tapped. */
    data class Choose(val choice: SettleChoice) : SettleUpEvent

    /** The footer button: "Pay ₹1,200" or "Mark ₹1,200 as paid". */
    data object Confirm : SettleUpEvent

    /** The UPI app could not be opened (none on the phone). */
    data object UpiAppMissing : SettleUpEvent
}

/** One-shot messages from Settle up to its screen. */
sealed interface SettleUpEffect {
    /** Open [uri] (`upi://pay?...`) in the person's UPI app. Never logged. */
    data class OpenUpi(val uri: String) : SettleUpEffect
}

/**
 * Settle up for [memberId] in [groupId]: the person owes them; Pay by UPI or Paid another way.
 *
 * Pay by UPI remembers the payment through [matcher] (the bank's debit SMS then settles it by itself) and opens the
 * UPI app; the screen stays, and leaves by itself once the balance closes. Paid another way records the settlement
 * (`OTHER`, queued, so it works offline) and leaves.
 */
class SettleUpViewModel(
    private val groupId: String,
    private val memberId: String,
    private val groups: GroupsRepository,
    private val matcher: SettlementMatcher,
) : ViewModel() {
    private val choice = MutableStateFlow<SettleChoice?>(null)
    private val noUpiApp = MutableStateFlow(false)
    private val done = MutableStateFlow(false)
    private var busy = false

    private val effects = SettleEffects<SettleUpEffect>()

    /** One-shot messages for the screen. */
    val effectFlow: Flow<SettleUpEffect> = effects.flow

    /** The screen's state. */
    val state: StateFlow<SettleUpUiState> = combine(groups.observeGroup(groupId), choice, noUpiApp, done) { detail, picked, missing, recorded ->
        if (recorded) SettleUpUiState.Done else shownOf(detail, picked, missing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettleUpUiState.Loading)

    /** Handles what the person did. Back is handled by the caller. */
    fun onEvent(event: SettleUpEvent) {
        when (event) {
            SettleUpEvent.Back -> Unit
            is SettleUpEvent.Choose -> choice.value = event.choice
            SettleUpEvent.Confirm -> confirm()
            SettleUpEvent.UpiAppMissing -> {
                noUpiApp.value = true
                choice.value = SettleChoice.Other
                viewModelScope.launch { matcher.forgetUpiPayment(groupId, memberId) }
            }
        }
    }

    private fun shownOf(detail: GroupDetail?, picked: SettleChoice?, missing: Boolean): SettleUpUiState {
        if (detail == null) return SettleUpUiState.Gone
        if (detail.load == LoadState.LOADING) return SettleUpUiState.Loading
        val friend = detail.members.firstOrNull { it.member.id == memberId && !it.isMe } ?: return SettleUpUiState.Gone
        val owed = -friend.pairMinor
        if (owed <= 0) return SettleUpUiState.Gone
        val upiId = friend.member.upiId?.takeIf { friend.member.onApp && GroupText.isPayableUpiId(it) }
        val shownChoice = if (upiId == null) SettleChoice.Other else picked ?: SettleChoice.Upi
        return SettleUpUiState.Shown(
            name = shortName(friend.member.name, detail.members.map { it.member.name }),
            amountMinor = owed,
            expenseCount = expenseCount(detail, memberId),
            groupName = detail.name.takeIf { detail.group.kind == GroupKind.GROUP },
            upiId = upiId,
            friendOnApp = friend.member.onApp,
            choice = shownChoice,
            noUpiApp = missing,
            avatar = friend.avatar,
        )
    }

    private fun confirm() {
        if (busy) return
        val shown = state.value as? SettleUpUiState.Shown ?: return
        busy = true
        viewModelScope.launch {
            try {
                when (shown.choice) {
                    SettleChoice.Upi -> {
                        val upiId = shown.upiId ?: return@launch
                        matcher.startUpiPayment(groupId, memberId, shown.amountMinor)
                        noUpiApp.value = false
                        effects.send(SettleUpEffect.OpenUpi(UpiPayLink.of(upiId, shown.name, shown.amountMinor, shown.groupName)))
                    }
                    SettleChoice.Other -> {
                        val myMemberId = groups.observeGroup(groupId).first()?.group?.myMemberId ?: return@launch
                        groups.recordSettlement(groupId, myMemberId, memberId, shown.amountMinor, SettleMethod.OTHER, matchedFromSms = false)
                        // A Pay by UPI tapped before must not settle it a second time when its SMS arrives.
                        matcher.forgetUpiPayment(groupId, memberId)
                        done.value = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalArgumentException) {
                // The group went away meanwhile; the screen follows it.
            } finally {
                busy = false
            }
        }
    }

    /** Constants and the expense count. */
    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * The expenses of [detail] dated after the last settlement between the person and [memberId] (either way),
         * where one of them paid and the other has a share (docs/server.md 6.4). Deleted expenses are not in [detail].
         */
        fun expenseCount(detail: GroupDetail, memberId: String): Int {
            val me = detail.group.myMemberId
            val lastSettled = detail.settlements
                .filter { (it.fromMemberId == me && it.toMemberId == memberId) || (it.fromMemberId == memberId && it.toMemberId == me) }
                .maxOfOrNull { it.occurredOn }
            return detail.expenses.count { row ->
                val expense = row.expense
                val after = lastSettled == null || expense.occurredOn.isAfter(lastSettled)
                fun shareOf(member: String) = row.shares.firstOrNull { it.memberId == member }?.shareMinor ?: 0L
                val between = (expense.paidByMemberId == me && shareOf(memberId) > 0) ||
                    (expense.paidByMemberId == memberId && shareOf(me) > 0)
                !expense.deleted && after && between
            }
        }
    }
}
