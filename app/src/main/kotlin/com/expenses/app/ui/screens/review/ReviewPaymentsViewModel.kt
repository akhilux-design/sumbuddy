package com.expenses.app.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.ExpenseWithAccount
import com.expenses.app.data.model.payee
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.data.settings.ReviewSession
import com.expenses.app.data.settings.ReviewSessionDecision
import com.expenses.app.data.settings.ReviewSessionStore
import com.expenses.app.ui.screens.payment.PaymentAccount
import com.expenses.app.ui.screens.payment.PaymentTime
import com.expenses.parser.Direction
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds Review payments: the debits read from SMS that wait for a decision, oldest first, decided one at a time
 * (swipe or buttons) or all at once (Add all), with Undo of the last decision.
 *
 * A decided payment leaves the stack at once, before the database write ends, so the next card shows without a
 * flicker. Writes run one after the other, so a quick Undo always follows the decision it takes back.
 *
 * The decisions belong to a run kept in [session] (payment ids only, never SMS text) until All sorted's Done: a
 * screen opened again, also after Android stopped the app, carries on with the run, so "3 of 14", Undo and All sorted
 * count every payment of it. When the run is read back, each payment must still be as it was decided; one decided
 * elsewhere meanwhile drops out of the run (one that waits again is simply a card again).
 *
 * All sorted left another way than Done or Back (a notification or a link opens on a fresh stack, the app is swiped
 * away) leaves a finished run on disk. It is kept marked as sorted: the screen coming back to it (after Android stopped
 * the app) still offers Undo, but the next review that finds a payment not of the run waiting starts a new run.
 *
 * "Split it instead" puts the payment in the run's splits. Once it is split (Split it, then Split. Sorted.) it counts
 * as added, with the person's share in the total; while it still waits it is still a card. Undo here never takes a
 * split back (Undo on Split. Sorted. does), so Undo takes back the last Add or Ignore before it.
 *
 * A payment ignored here loses its SMS text at once; Undo brings it back to waiting without the text (see
 * docs/architecture.md).
 *
 * @param expenses the expenses.
 * @param clock gives today, the current month and the time zone.
 * @param session where the run is kept.
 * @param writeScope where the writes run: the app's scope, so leaving the screen never cuts a decision, an Undo or the
 * end of the run short.
 */
class ReviewPaymentsViewModel(
    private val expenses: ExpenseRepository,
    private val clock: Clock,
    private val session: ReviewSessionStore,
    private val writeScope: CoroutineScope,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())
    private val nextToken = AtomicLong()
    private val month: YearMonth = YearMonth.now(clock)

    /** The ids the database listed as waiting last time. */
    @Volatile private var lastWaitingIds: Set<Long> = emptySet()

    /**
     * Whether the run reached All sorted and the person has not decided, undone or split anything since; written with
     * the run. A payment that comes in while All sorted shows is a card on the screen, but does not open the run again.
     */
    @Volatile private var lastSorted = false

    /**
     * The waiting payments. Each new list ends the stay of the payments Undo brought back that it lists, and settles
     * the run's splits before the screen sees it, so a payment just split never shows as a card in between.
     */
    private val waiting = expenses.observeWaiting().onEach { list ->
        val ids = list.mapTo(HashSet()) { it.expense.id }
        lastWaitingIds = ids
        local.update { it.pruneListed(ids) }
        settleSplits(ids)
    }

    /** The screen's state. */
    val state: StateFlow<ReviewPaymentsUiState> = combine(
        waiting,
        expenses.observeMonthTotal(month),
        local,
    ) { waiting, monthTotal, local -> uiState(waiting, monthTotal, local) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ReviewPaymentsUiState.Loading)

    /** The payments on the stack now, oldest first, as last read; Add all takes these. */
    @Volatile private var shown: List<ExpenseWithAccount> = emptyList()

    init {
        writeScope.launch { session.exclusive { restore() } }
    }

    /** Handles what the person did. Navigation (category, Split, Back, Done) is also handled by the caller. */
    fun onEvent(event: ReviewPaymentsEvent) {
        when (event) {
            is ReviewPaymentsEvent.Decide -> decide(event.decision, shown.filter { it.expense.id == event.paymentId })
            ReviewPaymentsEvent.Undo -> undo()
            ReviewPaymentsEvent.OpenAddAll -> local.update { it.copy(sheetOpen = true) }
            ReviewPaymentsEvent.CancelAddAll -> local.update { it.copy(sheetOpen = false) }
            ReviewPaymentsEvent.ConfirmAddAll -> {
                local.update { it.copy(sheetOpen = false) }
                decide(ReviewDecision.Add, shown)
            }
            is ReviewPaymentsEvent.Split -> split(event.paymentId)
            ReviewPaymentsEvent.Done -> end()
            is ReviewPaymentsEvent.ChangeCategory,
            ReviewPaymentsEvent.Back,
            -> Unit
        }
    }

    private fun decide(decision: ReviewDecision, payments: List<ExpenseWithAccount>) {
        val state = local.value
        if (!state.restored || state.ended) return
        val hidden = state.hiddenIds
        val items = payments.filter { it.expense.id !in hidden }.map { DecidedPayment(it.withoutText()) }
        if (items.isEmpty()) return
        reopen()
        val token = nextToken.getAndIncrement()
        val ids = items.mapTo(HashSet()) { it.id }
        local.update {
            it.copy(
                decisions = it.decisions + Decision(token, decision, items),
                // Decided here after all: no longer a split of this run.
                splitIds = it.splitIds - ids,
                everDecided = true,
                pendingWrites = it.pendingWrites + 1,
            )
        }
        write { writeDecision(token, decision, items) }
    }

    private suspend fun writeDecision(token: Long, decision: ReviewDecision, items: List<DecidedPayment>) {
        val ids = items.map { it.id }
        val done = when (decision) {
            ReviewDecision.Add -> expenses.addWaiting(ids)
            ReviewDecision.Ignore -> expenses.ignoreWaiting(ids)
        }.toSet()
        // A payment added, ignored or deleted elsewhere meanwhile is not this review's decision.
        if (done.size != items.size) {
            val gone = ids.filterNot { it in done }.toSet()
            local.update { state ->
                state.copy(
                    decisions = state.decisions.mapNotNull { d ->
                        if (d.token != token) d else d.copy(items = d.items.filter { it.id in done }).takeIf { it.items.isNotEmpty() }
                    },
                    comingBack = state.comingBack - gone,
                )
            }
        }
        save()
    }

    private fun undo() {
        val state = local.value
        if (state.ended) return
        val last = state.decisions.lastOrNull() ?: return
        reopen()
        // The payments show again at once, before the write ends, so the screen never looks empty in between.
        local.update {
            it.copy(
                decisions = it.decisions.dropLast(1),
                comingBack = it.comingBack + last.items.associate { item -> item.id to item.payment },
                backWritten = it.backWritten - last.items.map { item -> item.id }.toSet(),
                pendingWrites = it.pendingWrites + 1,
            )
        }
        write {
            val ids = last.items.map { it.id }
            // A payment split since (from Expense detail) is not put back to waiting: that would leave its group
            // expense behind. It stays in the run as a split, which Undo never takes back.
            val split = ids.mapNotNull { id -> expenses.observe(id).first()?.takeIf { it.isSplit() } }
                .associate { it.expense.id to DecidedPayment(it.withoutText()) }
            val back = expenses.backToWaiting(ids - split.keys).toSet()
            val failed = ids.filterNot(back::contains).toSet()
            // From now on the next waiting list that names them ends their stay in comingBack. The list may already
            // have come while the write ran, so it is checked here too.
            local.update {
                it.copy(
                    comingBack = it.comingBack - failed,
                    backWritten = it.backWritten + back,
                    splitIds = it.splitIds + (split.keys - it.splitIds.toSet()),
                    splitDone = it.splitDone + split,
                ).pruneListed(lastWaitingIds)
            }
            save()
        }
    }

    /** "Split it instead": the payment becomes one of the run's splits, counted as added once it is split. */
    private fun split(paymentId: Long) {
        val state = local.value
        if (!state.restored || state.ended || paymentId in state.splitIds) return
        reopen()
        local.update { it.copy(splitIds = it.splitIds + paymentId) }
        write(counted = false) { save() }
    }

    /** Done on All sorted: the run ends. Nothing of it is kept. */
    private fun end() {
        local.update { it.copy(ended = true, sheetOpen = false) }
        write(counted = false) { session.clear() }
    }

    /**
     * Runs [block] in [writeScope], after the run's earlier writes, while it holds the run. A [counted] write was
     * added to [LocalState.pendingWrites] by the caller and is taken off it when it ends.
     */
    private fun write(counted: Boolean = true, block: suspend () -> Unit) {
        writeScope.launch {
            try {
                session.exclusive { block() }
            } finally {
                if (counted) local.update { it.copy(pendingWrites = it.pendingWrites - 1) }
            }
        }
    }

    /** Writes what this screen holds of the run to [session]; called while holding it. */
    private suspend fun save() {
        val state = local.value
        when {
            !state.restored -> Unit
            state.ended -> session.clear()
            else -> session.update { state.toSession(sorted = lastSorted) }
        }
    }

    /**
     * Reads the run back. A run that was sorted ends instead when a payment not of the run waits now (one of its
     * splits waiting again is still of it): this is a new review. Otherwise each decision keeps only the payments still
     * as it left them (an added one is added and not split, an ignored one is ignored); a payment split since becomes
     * one of the run's splits. Called while holding the run.
     */
    private suspend fun restore() {
        val run = session.session.first()
        if (run.sorted && expenses.observeWaiting().first().any { it.expense.id !in run.splitIds }) {
            session.clear()
            local.update { it.copy(restored = true) }
            return
        }
        lastSorted = run.sorted
        val decisions = mutableListOf<Decision>()
        val splitIds = run.splitIds.toMutableList()
        for (stored in run.decisions) {
            val decision = if (stored.added) ReviewDecision.Add else ReviewDecision.Ignore
            val items = stored.ids.mapNotNull { id ->
                val row = expenses.observe(id).first() ?: return@mapNotNull null
                when {
                    row.isSplit() -> {
                        if (id !in splitIds) splitIds += id
                        null
                    }
                    row.stillAsDecided(decision) -> DecidedPayment(row.withoutText())
                    else -> null
                }
            }
            if (items.isNotEmpty()) decisions += Decision(nextToken.getAndIncrement(), decision, items)
        }
        // Settled before the screen sees the run, so no split shows as missing in between.
        val (split, gone) = settle(splitIds, lastWaitingIds, known = emptyMap())
        local.update {
            val ids = (splitIds - gone) + (it.splitIds - splitIds.toSet())
            it.copy(
                restored = true,
                decisions = decisions + it.decisions,
                splitIds = ids,
                splitDone = split.filterKeys { id -> id in ids },
                everDecided = it.everDecided || decisions.isNotEmpty(),
            )
        }
        save()
    }

    /** Settles the run's splits against the database when the waiting list [waitingIds] changes; see [settle]. */
    private suspend fun settleSplits(waitingIds: Set<Long>) {
        val state = local.value
        if (!state.restored || state.splitIds.isEmpty()) return
        val (split, gone) = settle(state.splitIds, waitingIds, state.splitDone)
        val checked = state.splitIds.toSet()
        local.update { now ->
            val splitIds = now.splitIds - gone
            // Splits added meanwhile (an Undo that found a payment split) keep what they were given.
            val splitDone = now.splitDone.filterKeys { it !in checked } + split
            now.copy(splitIds = splitIds, splitDone = splitDone.filterKeys { it in splitIds })
        }
        if (gone.isNotEmpty()) writeScope.launch { session.exclusive { save() } }
    }

    /**
     * Where each of the run's splits [splitIds] stands: a payment that waits (in [waitingIds], or still DETECTED) is a
     * card; one that is split counts as added, with the share it has now ([known] holds those already read); any
     * other was decided elsewhere and leaves the run.
     *
     * @return the splits made, and the ids that leave the run.
     */
    private suspend fun settle(
        splitIds: List<Long>,
        waitingIds: Set<Long>,
        known: Map<Long, DecidedPayment>,
    ): Pair<Map<Long, DecidedPayment>, Set<Long>> {
        val split = HashMap<Long, DecidedPayment>()
        val gone = HashSet<Long>()
        for (id in splitIds) {
            if (id in waitingIds) continue
            known[id]?.let {
                split[id] = it
                continue
            }
            val row = expenses.observe(id).first()
            when {
                row == null -> gone += id
                row.expense.status == ExpenseStatus.DETECTED -> Unit
                row.isSplit() -> split[id] = DecidedPayment(row.withoutText())
                else -> gone += id
            }
        }
        return split to gone
    }

    private fun uiState(
        waiting: List<ExpenseWithAccount>,
        monthTotal: Long,
        local: LocalState,
    ): ReviewPaymentsUiState {
        if (!local.restored) return ReviewPaymentsUiState.Loading
        val hidden = local.hiddenIds
        val waitingIds = waiting.mapTo(HashSet()) { it.expense.id }
        val back = local.comingBack.values.filter { it.expense.id !in waitingIds }
        val visible = (waiting + back)
            .filter { it.expense.id !in hidden }
            .sortedWith(compareBy({ it.expense.occurredAt }, { it.expense.id }))
        shown = visible
        val added = local.decisions.filter { it.decision == ReviewDecision.Add }.flatMap { it.items } + local.splitDone.values
        val decided = local.decisions.sumOf { it.items.size } + local.splitDone.size
        val nothingStands = local.decisions.isEmpty() && local.splitDone.isEmpty()
        val top = visible.firstOrNull()
        val screen = when {
            top != null -> ReviewPaymentsUiState.Reviewing(
                card = top.toCard(),
                cardsBehind = (visible.size - 1).coerceAtMost(MAX_CARDS_BEHIND),
                position = decided + 1,
                total = decided + visible.size,
                canUndo = local.decisions.isNotEmpty(),
                addAll = if (local.sheetOpen) addAllSummary(visible) else null,
            )
            // Nothing decided here stands (none yet, or each was taken back, or handled elsewhere at the same moment)
            // and nothing waits. While a write runs, the stack may still change: wait for it.
            nothingStands && local.everDecided && local.pendingWrites > 0 -> ReviewPaymentsUiState.Loading
            nothingStands -> ReviewPaymentsUiState.NothingWaiting
            else -> ReviewPaymentsUiState.Sorted(
                addedCount = added.size,
                addedMinor = added.sumOf { it.shareMinor },
                ignoredCount = decided - added.size,
                month = month,
                addedAllInMonth = added.all { YearMonth.from(it.occurredAt.atZone(clock.zone)) == month },
                monthTotalMinor = monthTotal,
                canUndo = local.decisions.isNotEmpty(),
            )
        }
        if (screen is ReviewPaymentsUiState.Sorted) noteSorted()
        return screen
    }

    /**
     * The screen reached All sorted: the run is finished, and written so at once, so a run left there another way than
     * Done is known for finished when it is read back (see [restore]).
     */
    private fun noteSorted() {
        if (lastSorted) return
        lastSorted = true
        writeScope.launch { session.exclusive { save() } }
    }

    /**
     * The person acted on the stack (a decision, an Undo, "Split it instead"): the run is open again until All sorted
     * shows once more. Called before the change, so its write never marks the run finished by mistake.
     */
    private fun reopen() {
        lastSorted = false
    }

    private fun addAllSummary(payments: List<ExpenseWithAccount>): AddAllSummary {
        val byCategory = payments.groupBy { it.expense.category }
        return AddAllSummary(
            count = payments.size,
            totalMinor = payments.sumOf { it.expense.amountMinor },
            byCategory = Category.entries.mapNotNull { category ->
                byCategory[category]?.let { list ->
                    CategoryTotal(category, list.size, list.sumOf { it.expense.amountMinor })
                }
            },
        )
    }

    private fun ExpenseWithAccount.toCard(): ReviewCard = ReviewCard(
        id = expense.id,
        amountMinor = expense.amountMinor,
        payee = expense.payee,
        time = PaymentTime.of(expense.occurredAt, clock),
        account = PaymentAccount.from(account),
        paidWith = expense.paidWith,
        category = expense.category,
    )

    /** The payment as this screen keeps it: without the SMS text (an ignored payment's text is gone for good). */
    private fun ExpenseWithAccount.withoutText(): ExpenseWithAccount = copy(expense = expense.copy(originalMessage = null))

    /** A payment of the person's split in a group: added, linked to a group expense, counting their share. */
    private fun ExpenseWithAccount.isSplit(): Boolean =
        expense.status == ExpenseStatus.ADDED && expense.groupExpenseId != null

    /** Whether this payment read from SMS is still as [decision] left it. */
    private fun ExpenseWithAccount.stillAsDecided(decision: ReviewDecision): Boolean =
        expense.source == ExpenseSource.SMS &&
            expense.direction == Direction.DEBIT &&
            expense.status == if (decision == ReviewDecision.Add) ExpenseStatus.ADDED else ExpenseStatus.IGNORED

    /** One payment decided in this run, as it was on the stack: enough to undo it and to sum up what was added. */
    private data class DecidedPayment(val payment: ExpenseWithAccount) {
        val id: Long get() = payment.expense.id
        val shareMinor: Long get() = payment.expense.myShareMinor
        val occurredAt: Instant get() = payment.expense.occurredAt
    }

    /** One decision: a swipe or button (one payment) or Add all (every payment then waiting). */
    private data class Decision(val token: Long, val decision: ReviewDecision, val items: List<DecidedPayment>)

    /**
     * What only this screen holds.
     *
     * @param restored whether the run kept on disk has been read back; until then the screen shows Loading.
     * @param ended whether Done ended the run.
     * @param decisions the Add and Ignore decisions of the run, the last one last.
     * @param splitIds the payments of the run whose "Split it instead" was tapped, in that order.
     * @param splitDone those of [splitIds] that are split now, counted as added.
     * @param sheetOpen whether the Add all sheet is open.
     * @param everDecided whether anything was decided in the run, so a stack emptied by Undo is not taken as nothing
     * waiting.
     * @param comingBack payments put back to waiting by Undo, shown until the database lists them again. They hold no
     * SMS text: the stack's rows are shown, never their original message.
     * @param backWritten the ids of [comingBack] whose Undo write has ended: the first waiting list that names one of
     * them drops it from [comingBack], so a payment later decided elsewhere does not come back as a stale card.
     * @param pendingWrites decisions and undos whose database write has not ended yet.
     */
    private data class LocalState(
        val restored: Boolean = false,
        val ended: Boolean = false,
        val decisions: List<Decision> = emptyList(),
        val splitIds: List<Long> = emptyList(),
        val splitDone: Map<Long, DecidedPayment> = emptyMap(),
        val sheetOpen: Boolean = false,
        val everDecided: Boolean = false,
        val comingBack: Map<Long, ExpenseWithAccount> = emptyMap(),
        val backWritten: Set<Long> = emptySet(),
        val pendingWrites: Int = 0,
    ) {
        val hiddenIds: Set<Long>
            get() = decisions.flatMapTo(HashSet()) { d -> d.items.map { it.id } }.apply { addAll(splitDone.keys) }

        /** Without the payments of [comingBack] that the database lists as waiting again, [waitingIds]. */
        fun pruneListed(waitingIds: Set<Long>): LocalState {
            val listed = backWritten.filterTo(HashSet()) { it in waitingIds }
            return if (listed.isEmpty()) this else copy(comingBack = comingBack - listed, backWritten = backWritten - listed)
        }

        /** The run as [ReviewSessionStore] keeps it: ids only, and whether All sorted was showing ([sorted]). */
        fun toSession(sorted: Boolean): ReviewSession = ReviewSession(
            decisions = decisions.map { d -> ReviewSessionDecision(added = d.decision == ReviewDecision.Add, ids = d.items.map { it.id }) },
            splitIds = splitIds,
            sorted = sorted,
        )
    }

    private companion object {
        /** The design draws at most two plain cards behind the top one. */
        const val MAX_CARDS_BEHIND = 2

        /** How long the reads stay active after the screen stops watching, so a rotation does not restart them. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
