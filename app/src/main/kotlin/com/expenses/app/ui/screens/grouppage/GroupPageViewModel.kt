package com.expenses.app.ui.screens.grouppage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.data.ActionResult
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.GroupDetail
import com.expenses.app.groups.data.GroupExpenseWithShares
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.RejectedChangeEntity
import com.expenses.app.groups.data.RejectedChanges
import com.expenses.app.groups.data.RejectedChanges.RefusedExpense
import com.expenses.app.groups.data.RejectedChanges.RetryOutcome
import com.expenses.app.groups.data.joinsToUndo
import com.expenses.app.groups.sync.SyncScheduler
import com.expenses.app.ui.format.shortName
import com.expenses.app.ui.time.minuteTicks
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Group page of [groupId] (design 02-everyday/24 and its states 22, 23, 40 to 43; docs/groups-build-plan.md 4.2).
 *
 * The page waits for a group that is not on this phone yet (Join group opens it before the pull, an old notification,
 * a group left elsewhere): offline it shows 23; online it asks for one sync and shows the placeholders, and when a
 * sync has run since and the group is still not here, or [waitLimit] passed, the group is gone and the page leaves
 * ([GroupPageUiState.Gone]). A group that goes while the page is open (a sync removed it) leaves the same way.
 *
 * Undo and Remind need the server at once: offline they leave the page as it is with the offline banner.
 *
 * @param groupId the group.
 * @param groups the group data.
 * @param rejectedChanges the changes the server refused ("Not saved", 43).
 * @param expenses the person's own rows (a "This month" row opens Expense detail of one).
 * @param online whether the phone is online now.
 * @param sync asks for syncs and says when one runs.
 * @param clock for today, this month and the join window.
 * @param saved the page's saved state: the joins undone from this page are kept in it, so "Join undone" (41) is still
 * there after Android stopped the app in the background.
 * @param ticks "now", again every minute (the join banner ends when its 7 days do; the month turns).
 * @param waitLimit how long to wait for a group that is not here yet.
 */
class GroupPageViewModel(
    private val groupId: String,
    private val groups: GroupsRepository,
    private val rejectedChanges: RejectedChanges,
    private val expenses: ExpenseRepository,
    private val online: StateFlow<Boolean>,
    private val sync: SyncScheduler,
    private val clock: Clock,
    private val saved: SavedStateHandle,
    ticks: Flow<Instant> = minuteTicks(clock),
    private val waitLimit: Duration = WAIT_LIMIT,
) : ViewModel() {
    /** Whether the group is on this phone, as far as the page knows. */
    private enum class Arrival {
        /** Here, or not looked at yet. */
        Here,

        /** Not here: one sync was asked for and the page waits for it. */
        Waiting,

        /** Not here and the phone is offline (23). */
        Offline,

        /** Not here after a sync, or gone while shown: the page leaves. */
        Gone,
    }

    /**
     * What the page adds to the stored data.
     *
     * @param noRetry refused changes whose Try again found, when tapped, that they cannot be made as they are (the
     * group changed between drawing the row and the tap, or the inputs no longer fit): Try again is hidden for them
     * while the page is open. [RejectedChanges.canRetry] hides it from the start, also on a later visit, for a change
     * it can already tell about (someone in it left).
     * @param retrying refused changes whose Try again is running: a second tap on one is ignored.
     * @param undone the joins undone from this page (41), until the person leaves the page for another screen
     * ([GroupPageEvent.Left]). A theme change, a rotation, the screen going off and another app in front keep them.
     */
    private data class Local(
        val arrival: Arrival = Arrival.Here,
        val notice: GroupNotice? = null,
        val undone: List<UndoneJoin> = emptyList(),
        val busy: Boolean = false,
        val noRetry: Set<Long> = emptySet(),
        val retrying: Set<Long> = emptySet(),
    )

    /** A join undone from this page: the place [memberId] is the friend [friend] again. */
    private data class UndoneJoin(val memberId: String, val friend: String)

    private val local = MutableStateFlow(Local(undone = savedUndone()))
    private val effects = Channel<GroupPageEffect>(Channel.BUFFERED)
    private var waiting: Job? = null

    /** Screens to open, once each. */
    val effect: Flow<GroupPageEffect> = effects.receiveAsFlow()

    /** The page's state. */
    val state: StateFlow<GroupPageUiState> = combine(
        groups.observeGroup(groupId),
        rejectedChanges.observe(groupId),
        ticks,
        local,
    ) { detail, rejected, now, local -> stateOf(detail, rejected, now, local) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), GroupPageUiState.Loading(title = ""))

    init {
        viewModelScope.launch {
            var seen = false
            groups.observeGroup(groupId).map { it != null }.distinctUntilChanged().collect { here ->
                when {
                    here -> {
                        seen = true
                        local.update { it.copy(arrival = Arrival.Here) }
                    }
                    // It was here and went: a sync removed it, or this page's own action did.
                    seen -> local.update { it.copy(arrival = Arrival.Gone) }
                    else -> startWaiting()
                }
            }
        }
        viewModelScope.launch {
            online.collect { isOnline ->
                if (!isOnline) return@collect
                // Back online: the offline banner no longer says anything, and a group that was not here is looked for.
                local.update { if (it.notice == GroupNotice.Offline) it.copy(notice = null) else it }
                if (local.value.arrival == Arrival.Offline) startWaiting()
            }
        }
    }

    /** Handles what the person did. Back, Add, OpenSettings and Settle are the caller's (navigation only). */
    fun onEvent(event: GroupPageEvent) {
        when (event) {
            GroupPageEvent.Reload -> reload()
            is GroupPageEvent.UndoJoin -> undoJoin(event.memberId)
            GroupPageEvent.Remind -> remind()
            is GroupPageEvent.OpenExpense -> viewModelScope.launch {
                expenses.findByGroupExpense(event.groupExpenseId)?.let { effects.send(GroupPageEffect.ExpenseDetail(it.id)) }
            }
            is GroupPageEvent.RetryChange -> retry(event.id)
            is GroupPageEvent.DismissChange -> viewModelScope.launch { rejectedChanges.dismiss(event.id) }
            // 41 shows until the person leaves the page for another screen (Back, or going on).
            GroupPageEvent.Left -> {
                local.update { it.copy(undone = emptyList()) }
                saveUndone()
            }
            GroupPageEvent.Back, GroupPageEvent.Add, GroupPageEvent.OpenSettings, is GroupPageEvent.Settle -> Unit
        }
    }

    /**
     * Try again on the "Not saved" row [id]. A second tap while the first runs is ignored here, and [RejectedChanges.retry]
     * reads, makes and forgets the row in one transaction, so a double tap never makes the expense twice.
     */
    private fun retry(id: Long) {
        if (id in local.value.retrying) return
        local.update { it.copy(retrying = it.retrying + id) }
        viewModelScope.launch {
            try {
                when (val outcome = rejectedChanges.retry(id)) {
                    is RetryOutcome.SplitPayment -> effects.send(GroupPageEffect.SplitPayment(outcome.expenseId))
                    RetryOutcome.NotPossible -> local.update { it.copy(noRetry = it.noRetry + id) }
                    // Made again and queued, or nothing left to do: the row is gone either way.
                    RetryOutcome.Retried, RetryOutcome.Gone -> Unit
                }
            } finally {
                local.update { it.copy(retrying = it.retrying - id) }
            }
        }
    }

    /** The joins undone from this page as [saved] holds them: none on a first visit. */
    private fun savedUndone(): List<UndoneJoin> {
        val ids = saved.get<ArrayList<String>>(UNDONE_IDS_KEY).orEmpty()
        val friends = saved.get<ArrayList<String>>(UNDONE_FRIENDS_KEY).orEmpty()
        return ids.zip(friends) { id, friend -> UndoneJoin(id, friend) }
    }

    /**
     * Puts the joins undone from this page into [saved], at once: when the person leaves for another tab, the page's
     * saved state is stored right after [GroupPageEvent.Left], and must not bring "Join undone" back later.
     */
    private fun saveUndone() {
        val undone = local.value.undone
        saved[UNDONE_IDS_KEY] = ArrayList(undone.map { it.memberId })
        saved[UNDONE_FRIENDS_KEY] = ArrayList(undone.map { it.friend })
    }

    // ---- Waiting for a group that is not here ----

    private fun reload() {
        when (local.value.arrival) {
            Arrival.Offline, Arrival.Waiting -> startWaiting()
            else -> sync.requestSync()
        }
    }

    private fun startWaiting() {
        if (waiting?.isActive == true) return
        waiting = viewModelScope.launch { waitForGroup() }
    }

    /**
     * Offline: 23. Online: one sync, then the group is here, or it is gone once a sync has run since without it, or
     * once [waitLimit] passed.
     */
    private suspend fun waitForGroup() {
        if (!online.value) {
            local.update { it.copy(arrival = Arrival.Offline) }
            return
        }
        local.update { it.copy(arrival = Arrival.Waiting) }
        sync.requestSync()
        val arrived = withTimeoutOrNull(waitLimit.toMillis()) {
            combine(groups.observeGroup(groupId), syncRan()) { group, ran ->
                when {
                    group != null -> true
                    ran -> false
                    else -> null
                }
            }.filterNotNull().first()
        } ?: false
        // A fresh read: the sync wrote the group before it said it was done.
        val here = arrived || groups.observeGroup(groupId).first() != null
        local.update { current ->
            when {
                here -> current.copy(arrival = Arrival.Here)
                current.arrival == Arrival.Waiting -> current.copy(arrival = Arrival.Gone)
                else -> current
            }
        }
    }

    /** True once a sync started and then ended after this was collected. */
    private fun syncRan(): Flow<Boolean> = sync.observeSyncing()
        .runningFold(SYNC_NOT_SEEN) { stage, syncing ->
            when {
                syncing && stage == SYNC_NOT_SEEN -> SYNC_RUNNING
                !syncing && stage == SYNC_RUNNING -> SYNC_ENDED
                else -> stage
            }
        }
        .map { it == SYNC_ENDED }

    // ---- Online-only actions ----

    private fun undoJoin(memberId: String) {
        if (local.value.busy) return
        if (!online.value) {
            local.update { it.copy(notice = GroupNotice.Offline) }
            return
        }
        local.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val before = memberNow(memberId)
            val result = groups.undoJoin(groupId, memberId)
            if (result == ActionResult.SignedOut) effects.send(GroupPageEffect.SignIn)
            // Who is back: the place as the server's answer stored it, which is the friend again. A phone that first
            // saw the group after the join never knew the friend's name, only the name of the person who joined.
            val friend = memberNow(memberId)?.takeIf { result == ActionResult.Done && !it.onApp }?.name
                ?: before?.placeName
                ?: before?.name.orEmpty()
            local.update { current ->
                when (result) {
                    ActionResult.Done -> current.copy(busy = false, undone = current.undone + UndoneJoin(memberId, friend))
                    // Every refusal of an undo already took the banner away in the data (or the group).
                    is ActionResult.Failed -> current.copy(busy = false, notice = GroupNotice.Failed.takeUnless { result.code in UNDO_REFUSALS })
                    else -> current.copy(busy = false, notice = noticeOf(result))
                }
            }
            saveUndone()
        }
    }

    /** The member [memberId] as this phone has it now, or null. */
    private suspend fun memberNow(memberId: String): MemberEntity? =
        groups.observeGroup(groupId).first()?.members?.firstOrNull { it.member.id == memberId }?.member

    /**
     * Remind: every app user who owes the person gets a push; when nobody on the app owes, Remind on WhatsApp for the
     * friend without the app who owes most (reminding other friends without the app is not built).
     */
    private fun remind() {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val members = groups.observeGroup(groupId).first()?.members.orEmpty()
                val everyone = members.map { it.member.name }
                val owing = members.filter { !it.isMe && !it.member.removed && it.pairMinor > 0 }
                val onApp = owing.filter { it.member.onApp && it.member.userId != null }
                if (onApp.isEmpty()) {
                    owing.maxByOrNull { it.pairMinor }?.let { effects.send(GroupPageEffect.RemindOnWhatsApp(it.member.id)) }
                    return@launch
                }
                if (!online.value) {
                    local.update { it.copy(notice = GroupNotice.Offline) }
                    return@launch
                }
                local.update { it.copy(notice = null) }
                val notice = when (val result = groups.remind(groupId, onApp.map { it.member.id })) {
                    is ApiResult.Ok -> {
                        val names = onApp.filter { it.member.id in result.value.sent }.map { shortName(it.member.name, everyone) }
                        if (names.isEmpty()) GroupNotice.NotReminded else GroupNotice.Reminded(names)
                    }
                    is ApiResult.Error -> when {
                        result.error.needsSignIn -> {
                            effects.send(GroupPageEffect.SignIn)
                            null
                        }
                        result.error.isOffline -> GroupNotice.Offline
                        else -> GroupNotice.Failed
                    }
                }
                local.update { it.copy(notice = notice) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    // ---- State ----

    private fun stateOf(detail: GroupDetail?, rejected: List<RejectedChangeEntity>, now: Instant, local: Local): GroupPageUiState =
        when {
            local.arrival == Arrival.Gone -> GroupPageUiState.Gone
            detail == null -> if (local.arrival == Arrival.Offline) GroupPageUiState.CouldNotLoad(title = "") else GroupPageUiState.Loading(title = "")
            detail.load == LoadState.LOADING -> GroupPageUiState.Loading(title = detail.name)
            detail.load == LoadState.FAILED -> GroupPageUiState.CouldNotLoad(title = detail.name)
            else -> shown(detail, rejected, now, local)
        }

    private fun shown(detail: GroupDetail, rejected: List<RejectedChangeEntity>, now: Instant, local: Local): GroupPageUiState.Shown {
        val today = now.atZone(clock.zone).toLocalDate()
        val month = YearMonth.from(today)
        val me = detail.group.myMemberId
        val others = detail.members.filter { !it.isMe && !it.member.removed }
        // First names, as the design writes people in rows and banners (24, 40, 41); whole names where two clash.
        val names = detail.members.map { it.member.name }
        val namesById = detail.members.associate { it.member.id to shortName(it.member.name, names) }
        // Oldest first, as 24 (Rent "1 Nov" above Swiggy "Today") and 42 (Villa "14 Nov" above Scooters "15 Nov") draw
        // them; the data comes newest first.
        val thisMonth = detail.expenses
            .filter { !it.expense.deleted && YearMonth.from(it.expense.occurredOn) == month }
            .sortedWith(compareBy<GroupExpenseWithShares>({ it.expense.occurredOn }, { it.expense.updatedAt }))
        val memberRows = detail.members.map { it.member }

        val refused = rejected.filter { it.entityKind == EntityKind.EXPENSE }
        val notSaved = refused.mapNotNull { change ->
            val stored = detail.expenses.firstOrNull { it.expense.id == change.entityId }
            val tried = rejectedChanges.refusedExpense(change)
                ?: stored?.let(::refusedOfStored)
                ?: return@mapNotNull null
            NotSavedRow(
                id = change.id,
                op = change.op,
                description = tried.description,
                category = tried.category,
                date = tried.occurredOn,
                method = if (tried.auto) RowMethod.Auto else RowMethod.Split(tried.method),
                amountMinor = tried.amountMinor,
                noRetry = noRetryReasonOf(change, memberRows, stored, triedInVain = change.id in local.noRetry),
            )
        }
        val shownAsRefused = refused.mapTo(HashSet()) { it.entityId }

        val balances = others.map { row ->
            val pair = row.pairMinor
            MemberBalanceRow(
                memberId = row.member.id,
                name = namesById.getValue(row.member.id),
                kind = when {
                    pair > 0 -> BalanceKind.OwesYou
                    pair < 0 -> BalanceKind.YouOwe
                    row.netMinor == 0L -> BalanceKind.AllSettled
                    else -> BalanceKind.SettlesWithOthers
                },
                amountMinor = abs(pair),
                avatarColor = row.avatar,
                target = settleTargetOf(row.member.id, pair),
            )
        }.sortedWith(
            compareByDescending<MemberBalanceRow> { it.amountMinor }
                .thenBy { it.kind == BalanceKind.AllSettled }
                .thenBy { it.name.lowercase() },
        )

        val iOweMost = others.filter { it.pairMinor < 0 }.minByOrNull { it.pairMinor }
        val owesMeMost = others.filter { it.pairMinor > 0 }.maxByOrNull { it.pairMinor }
        val settle = iOweMost?.let { SettleTarget.SettleUp(it.member.id) } ?: owesMeMost?.let { SettleTarget.MarkAsPaid(it.member.id) }

        val joins = detail.joinsToUndo(now)
        // "Join undone" (41) says the place is a friend without the app again: once someone takes it again (a new
        // link), that is no longer true, and 40 says what is.
        val undone = local.undone.filter { undone ->
            joins.none { it.id == undone.memberId } && detail.members.none { it.member.id == undone.memberId && it.member.onApp }
        }

        return GroupPageUiState.Shown(
            title = detail.name,
            isDirect = detail.group.kind == GroupKind.DIRECT,
            today = today,
            card = GroupCard(
                name = detail.name,
                netMinor = detail.myNetMinor,
                people = detail.members.count { !it.member.removed },
                expensesThisMonth = thisMonth.size,
                month = month,
            ),
            notice = local.notice,
            joins = joins.map { joinBannerOf(it, names) },
            undone = undone.map { shortName(it.friend, names) },
            balances = balances,
            notSaved = notSaved,
            expenses = thisMonth.filter { it.expense.id !in shownAsRefused }.map { row ->
                val expense = row.expense
                val paidByMe = expense.paidByMemberId == me
                val myShare = row.shares.firstOrNull { it.memberId == me }?.shareMinor ?: 0L
                MonthExpenseRow(
                    id = expense.id,
                    description = expense.description,
                    category = expense.category,
                    date = expense.occurredOn,
                    method = if (expense.auto) RowMethod.Auto else RowMethod.Split(expense.splitMethod),
                    amountMinor = expense.amountMinor,
                    paidByMe = paidByMe,
                    payerName = namesById[expense.paidByMemberId].orEmpty(),
                    // The person's own row exists when they paid (their payment) or have a share (a mirror).
                    opensDetail = paidByMe || myShare > 0,
                )
            },
            footer = settle?.let { GroupFooter(settleUp = it, canRemind = owesMeMost != null) },
            busy = local.busy,
        )
    }

    /**
     * 40: "{joiner} joined · Not {friend}?" in first names. When the two whole names differ behind one first name
     * ("Rahul Kumar" took "Rahul Verma"'s place) both stay whole, which is what the owner must see; "Priya Nair" in
     * the place of "Priya" reads "Priya joined · Not Priya?" as drawn.
     */
    private fun joinBannerOf(member: MemberEntity, names: List<String>): JoinBanner {
        val joiner = member.name.trim()
        val place = (member.placeName ?: member.name).trim()
        val twoPeople = !place.equals(joiner, ignoreCase = true) && place.contains(' ') && joiner.contains(' ')
        return JoinBanner(member.id, shortName(joiner, names + listOfNotNull(place.takeIf { twoPeople })), shortName(place, names))
    }

    /**
     * Why the refused [change] is not offered Try again, for its row's note; null when it is offered.
     *
     * [NoRetryReason.SomeoneLeft] when the only thing in the way is that someone it names, as the payer or with a
     * share, has left the group: [RejectedChanges.canRetry] says no with the members as they are, and yes had nobody
     * left. Anything else is [NoRetryReason.Other]: a refusal that would come again as it is, a change that cannot be
     * read, parts that no longer add up, or a Try again that found the change cannot be made ([triedInVain]).
     *
     * @param members the group's members, removed ones included.
     * @param stored the expense the change is about as stored now, or null.
     * @param triedInVain true when Try again was tapped on this page and could not make the change.
     */
    private fun noRetryReasonOf(
        change: RejectedChangeEntity,
        members: List<MemberEntity>,
        stored: GroupExpenseWithShares?,
        triedInVain: Boolean,
    ): NoRetryReason? = when {
        rejectedChanges.canRetry(change, members, stored) -> if (triedInVain) NoRetryReason.Other else null
        rejectedChanges.canRetry(change, members.map { it.copy(removed = false) }, stored) -> NoRetryReason.SomeoneLeft
        else -> NoRetryReason.Other
    }

    private fun refusedOfStored(stored: GroupExpenseWithShares): RefusedExpense = RefusedExpense(
        description = stored.expense.description,
        category = stored.expense.category,
        amountMinor = stored.expense.amountMinor,
        occurredOn = stored.expense.occurredOn,
        paidByMemberId = stored.expense.paidByMemberId,
        method = stored.expense.splitMethod,
        auto = stored.expense.auto,
        inputs = stored.shares.associate { it.memberId to it.input },
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        val WAIT_LIMIT: Duration = Duration.ofSeconds(15)
        const val SYNC_NOT_SEEN = 0
        const val SYNC_RUNNING = 1
        const val SYNC_ENDED = 2
        const val UNDONE_IDS_KEY = "grouppage_undone_ids"
        const val UNDONE_FRIENDS_KEY = "grouppage_undone_friends"

        /** Undo refusals the data already put right (the banner or the group went): no failure banner. */
        val UNDO_REFUSALS = setOf("undo_expired", "not_joined_by_link", "not_owner", "not_member", "group_deleted", "not_found", "not_allowed")
    }
}

/** Settle up when the person owes [memberId] ([pair] below 0), Mark as paid when they owe the person, else nothing. */
internal fun settleTargetOf(memberId: String, pair: Long): SettleTarget? = when {
    pair < 0 -> SettleTarget.SettleUp(memberId)
    pair > 0 -> SettleTarget.MarkAsPaid(memberId)
    else -> null
}
