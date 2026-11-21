package com.expenses.app.ui.screens.cardbill

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.cardbill.BillState
import com.expenses.app.cardbill.CardBillDetail
import com.expenses.app.cardbill.CardBillMath
import com.expenses.app.cardbill.FriendOwes
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.data.RemindResult
import com.expenses.app.ui.format.shortName
import com.expenses.app.ui.screens.grouppage.GroupNotice
import com.expenses.app.ui.screens.grouppage.initialOf
import com.expenses.app.ui.time.minuteTicks
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Card bill view (design 03-getting-paid-back/04 and its states 20 to 26; docs/card-bills.md 2.3 and 2.6).
 *
 * Remind everyone (D19) sends a push to every friend on the app who owes part of the bill, one call per group, and
 * shows the Group page's result banner; then it opens Remind on WhatsApp for the friend without the app who owes the
 * most. Opened from the reminder's "Remind friends" ([remindOnOpen]), it runs once by itself; the saved state keeps
 * it from running again after a rotation or after Android stopped the app.
 *
 * @param remindOnOpen true from "Remind friends" (D21).
 * @param detail the bill (`CardBillReader.observeBill`); null once it is gone.
 * @param remind reminds members of a group by push (`GroupsRepository.remind`).
 * @param online whether the phone is online now.
 * @param clock today, in India time.
 * @param saved the view's saved state.
 * @param ticks "now", again every minute, so "due in 3 days" turns into "due in 2 days" at midnight.
 */
class CardBillViewModel(
    private val remindOnOpen: Boolean,
    private val detail: Flow<CardBillDetail?>,
    private val remind: suspend (groupId: String, memberIds: List<String>) -> ApiResult<RemindResult>,
    private val online: StateFlow<Boolean>,
    private val clock: Clock,
    private val saved: SavedStateHandle,
    ticks: Flow<Instant> = minuteTicks(clock),
) : ViewModel() {
    /** What the view adds to the stored data: the result banner and whether Remind everyone runs. */
    private data class Local(val notice: GroupNotice? = null, val busy: Boolean = false)

    private val local = MutableStateFlow(Local())
    private val effects = Channel<CardBillEffect>(Channel.BUFFERED)

    /** Screens to open, once each. */
    val effect: Flow<CardBillEffect> = effects.receiveAsFlow()

    /** The view's state. */
    val state: StateFlow<CardBillUiState> = combine(
        detail,
        ticks.map { it.atZone(clock.zone).toLocalDate() }.distinctUntilChanged(),
        local,
    ) { bill, today, local -> stateOf(bill, today, local) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CardBillUiState.Loading)

    init {
        if (remindOnOpen && saved.get<Boolean>(REMIND_RAN_KEY) != true) {
            viewModelScope.launch {
                val first = detail.first()
                saved[REMIND_RAN_KEY] = true
                if (first != null && first.friendsMinor > 0) remindEveryone()
            }
        }
        viewModelScope.launch {
            online.collect { isOnline ->
                // Back online: the offline banner no longer says anything.
                if (isOnline) local.update { if (it.notice == GroupNotice.Offline) it.copy(notice = null) else it }
            }
        }
    }

    /** Handles Remind everyone. Back, the rows and Sign in are the caller's (navigation only). */
    fun onEvent(event: CardBillEvent) {
        when (event) {
            CardBillEvent.RemindEveryone -> remindEveryone()
            CardBillEvent.Back, CardBillEvent.SignIn, is CardBillEvent.OpenGroup -> Unit
        }
    }

    /**
     * Remind everyone (D19; docs/card-bills.md 2.6). A second tap while it runs is ignored. Offline, nothing is sent
     * to anyone when a friend on the app owes part: the banner says so and WhatsApp does not open either.
     */
    private fun remindEveryone() {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val bill = detail.first() ?: return@launch
                val owing = bill.friends
                val everyone = owing.map { it.name }
                val onApp = owing.filter { it.onApp }
                if (onApp.isNotEmpty()) {
                    if (!online.value) {
                        local.update { it.copy(notice = GroupNotice.Offline) }
                        return@launch
                    }
                    local.update { it.copy(notice = null) }
                    val notice = pushTo(onApp, everyone) ?: return@launch
                    local.update { it.copy(notice = notice) }
                }
                owing.filter { !it.onApp }
                    .sortedWith(compareByDescending<FriendOwes> { it.owedMinor }.thenBy { it.name.lowercase() })
                    .firstOrNull()
                    ?.let { effects.send(CardBillEffect.RemindOnWhatsApp(it.targetGroupId, it.targetMemberId)) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * One push call per group to the friends on the app who owe part there. Returns the banner, or null when the
     * session ended (Sign in opens).
     */
    private suspend fun pushTo(friends: List<FriendOwes>, everyone: List<String>): GroupNotice? {
        val byGroup = LinkedHashMap<String, MutableList<Pair<FriendOwes, String>>>()
        for (friend in friends) {
            for (part in friend.parts) {
                if (part.onApp && CardBillMath.owedOf(part) > 0) byGroup.getOrPut(part.groupId) { ArrayList() } += friend to part.memberId
            }
        }
        val sent = ArrayList<String>()
        var problem: GroupNotice? = null
        for ((groupId, members) in byGroup) {
            when (val result = remind(groupId, members.map { it.second })) {
                is ApiResult.Ok -> members.filter { it.second in result.value.sent }
                    .forEach { (friend, _) -> sent += shortName(friend.name, everyone) }
                is ApiResult.Error -> when {
                    result.error.needsSignIn -> {
                        effects.send(CardBillEffect.SignIn)
                        return null
                    }
                    result.error.isOffline -> problem = GroupNotice.Offline
                    else -> if (problem == null) problem = GroupNotice.Failed
                }
            }
        }
        val names = sent.distinct()
        return when {
            names.isNotEmpty() -> GroupNotice.Reminded(names)
            problem != null -> problem
            else -> GroupNotice.NotReminded
        }
    }

    private fun stateOf(bill: CardBillDetail?, today: LocalDate, local: Local): CardBillUiState {
        bill ?: return CardBillUiState.Gone
        val status = bill.state(today)
        val total = bill.bill.totalDueMinor
        val friends = bill.friends
        val body = when {
            !bill.signedIn -> CardBillBody.SignedOut
            friends.isEmpty() -> CardBillBody.NobodyOwes
            else -> {
                val names = friends.map { it.name }
                CardBillBody.FriendsOwe(
                    friendsMinor = bill.friendsMinor,
                    totalMinor = total,
                    oneFriend = friends.singleOrNull()?.let { shortName(it.name, names) },
                    caption = captionOf(status, bill.bill.dueDate, bill.ownMinor),
                    rows = friends.map { friend ->
                        CardBillRow(
                            key = friend.personKey,
                            name = shortName(friend.name, names),
                            initial = initialOf(friend.name),
                            avatar = friend.avatar,
                            owedMinor = friend.owedMinor,
                            groupName = friend.groupName,
                            groupCount = friend.groupIds.size,
                            expenseCount = friend.expenseCount,
                            groupId = friend.targetGroupId,
                        )
                    },
                )
            }
        }
        return CardBillUiState.Shown(
            cardName = bill.cardName,
            last4 = bill.last4,
            status = status,
            totalMinor = total,
            body = body,
            notice = local.notice,
            busy = local.busy,
        )
    }

    private fun captionOf(status: BillState, dueDate: LocalDate?, own: Long): CardBillCaption = when {
        status is BillState.Paid -> CardBillCaption.OwnWas(own)
        status is BillState.Overdue -> CardBillCaption.OwnIs(own)
        dueDate == null -> CardBillCaption.GetBackNoDate(own)
        else -> CardBillCaption.GetBackBy(dueDate, own)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Saved state: the remind of "Remind friends" ran, so it does not run again. */
        const val REMIND_RAN_KEY = "cardbill_remind_ran"
    }
}
