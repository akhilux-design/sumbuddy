package com.expenses.app.ui.screens.split

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.groups.data.GroupDetail
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.PersonalSnapshot
import com.expenses.app.groups.data.SplitRule
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.format.shortName
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Split. Sorted. (design 02-everyday/13-split-added) shows. */
sealed interface SplitAddedUiState {
    /** The split is being read. */
    data object Loading : SplitAddedUiState

    /** The split is not on this phone any more (deleted meanwhile). */
    data object Gone : SplitAddedUiState

    /** Undo is done; the caller goes back to where the person was. */
    data object Undone : SplitAddedUiState

    /**
     * The split just made.
     *
     * @param sentence the line under "Split. Sorted.".
     * @param balances who owes whom through this split, as Balance Rows.
     * @param myShareMinor the person's share in paise.
     * @param totalMinor the whole expense in paise.
     * @param month the month the share counts in.
     * @param rememberedFor the payee when "Do this every time" is kept for it in this group ("Remembered"), else null.
     * @param paymentId the person's own row of this split, or null.
     * @param busy true while Undo runs; the buttons ignore more taps.
     */
    @Immutable
    data class Shown(
        val sentence: AddedSentence,
        val balances: List<AddedBalance>,
        val myShareMinor: Long,
        val totalMinor: Long,
        val month: YearMonth,
        val rememberedFor: String?,
        val paymentId: Long?,
        val busy: Boolean,
    ) : SplitAddedUiState
}

/** The sentence under "Split. Sorted.". [what] is the group expense's description ("Swiggy"). */
sealed interface AddedSentence {
    /** "Rahul and Priya each owe you ₹150 for Swiggy." */
    data class EachOwesYou(val names: List<String>, val eachMinor: Long, val what: String) : AddedSentence

    /** Not designed: one person, or a direct split. "Rahul owes you ₹150 for Swiggy." */
    data class OneOwesYou(val name: String, val amountMinor: Long, val what: String) : AddedSentence

    /** Not designed: parts that differ. "Rahul and Priya owe you ₹250 for Swiggy." */
    data class OweYouTogether(val names: List<String>, val totalMinor: Long, val what: String) : AddedSentence

    /** Not designed: someone else paid. "You owe Rahul ₹200 for Dinner." */
    data class YouOwe(val payer: String, val amountMinor: Long, val what: String) : AddedSentence

    /** Not designed: nobody owes anybody through the person, in a named group. "Added to Flat 302." */
    data class InGroup(val group: String) : AddedSentence

    /**
     * Not designed: nobody owes anybody through the person, in a split with one friend. It has no group to name
     * ("Added to Rahul." would read wrong), so "Split with Rahul."
     */
    data class WithFriend(val name: String) : AddedSentence
}

/**
 * One Balance Row of Split. Sorted.: "Rahul" / "Flat 302" / "+₹150".
 *
 * @param name the other person.
 * @param initial the avatar's letter.
 * @param group the group's name (the row's second line); null for a direct split, whose name is the friend's own, so
 * the row says "owes you" or "you owe" as the Groups tab's friend rows do (02-everyday/20).
 * @param amountMinor what is owed through this split, in paise.
 * @param owesYou true when they owe the person (green, "+"); false when the person owes them (red, "-").
 * @param avatar the avatar's colour.
 */
@Immutable
data class AddedBalance(
    val name: String,
    val initial: String,
    val group: String?,
    val amountMinor: Long,
    val owesYou: Boolean,
    val avatar: AvatarColor,
)

/** Everything the person can do on Split. Sorted.. */
sealed interface SplitAddedEvent {
    /** "Done", or Back. Handled by the caller. */
    data object Done : SplitAddedEvent

    /** "Undo": the split is taken back and the payment waits again. */
    data object Undo : SplitAddedEvent
}

/** Where Split. Sorted. was opened from: what the screen under it is. */
internal enum class SplitAddedBelow {
    /** Add to expenses for the same payment: it leaves with Done, as the payment no longer waits. */
    SAME_PAYMENT_FORM,

    /** Add expense, whose typed expense is now the split: it leaves with Done. */
    TYPED_FORM,

    /** Home, Review payments, Expense detail, a Group page or anything else: it stays. */
    OTHER,
}

/**
 * How many screens Done (or Undo) leaves, Split. Sorted. included: Done goes back to the screen that opened Split
 * it, past an Add to expenses of the same payment or the Add expense the split came from; Undo goes back to where the
 * person was (the payment waits again there, the typed expense is in its form again).
 */
internal fun splitAddedScreensToLeave(undo: Boolean, below: SplitAddedBelow): Int =
    if (undo || below == SplitAddedBelow.OTHER) 1 else 2

/**
 * Split. Sorted. for the group expense [groupExpenseId] just made: who owes the person now, that only their share
 * counts in their month, and "Remembered" when the payee's rule is kept for this group. Undo deletes the split
 * (`GroupsRepository.undoSplit`): a payment goes back to waiting; a typed expense is removed with it.
 *
 * @param groupExpenseId the split.
 * @param typed true when the split was typed in on Add expense (its own row goes with Undo).
 * @param groups the split and the group.
 * @param expenses the person's row of the split.
 * @param priorStatus the payment's status before the split (from the route), or null. Undo uses it when nothing else
 * on the phone remembers how the payment was: Android stopped the app after the split was sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SplitAddedViewModel(
    private val groupExpenseId: String,
    private val typed: Boolean,
    private val groups: GroupsRepository,
    private val expenses: ExpenseRepository,
    private val priorStatus: ExpenseStatus? = null,
) : ViewModel() {
    private data class Personal(val id: Long?, val counterparty: String?, val read: Boolean)

    private val personal = MutableStateFlow(Personal(null, null, read = false))
    private val local = MutableStateFlow(Local())

    private data class Local(val busy: Boolean = false, val undone: Boolean = false)

    init {
        viewModelScope.launch {
            val row = expenses.findByGroupExpense(groupExpenseId)
            personal.value = Personal(row?.id, row?.counterparty, read = true)
        }
    }

    private val detail = groups.observeSplitLabel(groupExpenseId).map { it?.groupId }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else groups.observeGroup(id) }

    private val rule = personal.map { it.counterparty }.distinctUntilChanged()
        .flatMapLatest { payee -> if (payee == null) flowOf(null) else groups.splitRuleFor(payee) }

    /** The screen's state. */
    val state: StateFlow<SplitAddedUiState> = combine(detail, personal, rule, local) { detail, personal, rule, local ->
        when {
            local.undone -> SplitAddedUiState.Undone
            !personal.read -> SplitAddedUiState.Loading
            // Undo removes the split a moment before it finishes: not "gone".
            local.busy && detail == null -> SplitAddedUiState.Loading
            else -> detail?.let { shown(it, personal, rule, local) } ?: SplitAddedUiState.Gone
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SplitAddedUiState.Loading)

    /** Handles what the person did. Done is handled by the caller and ignored here. */
    fun onEvent(event: SplitAddedEvent) {
        when (event) {
            SplitAddedEvent.Undo -> undo()
            SplitAddedEvent.Done -> Unit
        }
    }

    private fun undo() {
        if (local.value.busy || local.value.undone) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val row = expenses.findByGroupExpense(groupExpenseId)
            // A payment of the person's goes back to how it was before Split, unsplit.
            val prior = priorStatus
            val fallback = if (row != null && prior != null && !typed && row.source != ExpenseSource.GROUP) {
                PersonalSnapshot(row.id, prior.name, row.amountMinor, null)
            } else {
                null
            }
            groups.undoSplit(groupExpenseId, fallback)
            // A typed expense is the split: its own row goes too, so Add expense can make it again.
            if (typed && row != null && row.source == ExpenseSource.MANUAL) {
                expenses.get(row.id)?.takeIf { it.groupExpenseId == null }?.let { expenses.delete(it.id) }
            }
            local.update { it.copy(busy = false, undone = true) }
        }
    }

    private fun shown(detail: GroupDetail, personal: Personal, rule: SplitRule?, local: Local): SplitAddedUiState {
        val row = detail.expenses.firstOrNull { it.expense.id == groupExpenseId } ?: return SplitAddedUiState.Gone
        val expense = row.expense
        val me = detail.group.myMemberId
        val shares = row.shares.associate { it.memberId to it.shareMinor }
        // First names, as 13 writes them ("Rahul and Priya each owe you"); whole names where two clash.
        val all = detail.members.map { it.member }
        val names = all.map { it.name }
        val members = all.map { it.copy(name = shortName(it.name, names)) }
        // Each person's colour, the same as on the Group page and Split it.
        val colours = detail.members.associate { it.member.id to it.avatar }
        val what = expense.description
        // A direct split's name is the friend's own: its rows say "owes you" / "you owe" instead.
        val rowGroup = detail.name.takeIf { detail.group.kind == GroupKind.GROUP }
        // Nobody owes anybody through the person: the group is named, or the friend of a split with one friend.
        val nothingOwed = if (detail.group.kind == GroupKind.GROUP) {
            AddedSentence.InGroup(detail.name)
        } else {
            AddedSentence.WithFriend(members.firstOrNull { it.id != me }?.name ?: shortName(detail.name))
        }
        val balances: List<AddedBalance>
        val sentence: AddedSentence
        if (expense.paidByMemberId == me) {
            val owing = members.filter { it.id != me && (shares[it.id] ?: 0L) > 0L }
            balances = owing.map { AddedBalance(it.name, initialOf(it.name), rowGroup, shares.getValue(it.id), true, colours.getValue(it.id)) }
            val amounts = owing.map { shares.getValue(it.id) }
            sentence = when {
                owing.isEmpty() -> nothingOwed
                owing.size == 1 -> AddedSentence.OneOwesYou(owing.single().name, amounts.single(), what)
                amounts.distinct().size == 1 -> AddedSentence.EachOwesYou(owing.map { it.name }, amounts.first(), what)
                else -> AddedSentence.OweYouTogether(owing.map { it.name }, amounts.sum(), what)
            }
        } else {
            val mine = shares[me] ?: 0L
            val payer = members.firstOrNull { it.id == expense.paidByMemberId }
            if (mine > 0L && payer != null) {
                balances = listOf(AddedBalance(payer.name, initialOf(payer.name), rowGroup, mine, false, colours.getValue(payer.id)))
                sentence = AddedSentence.YouOwe(payer.name, mine, what)
            } else {
                balances = emptyList()
                sentence = nothingOwed
            }
        }
        return SplitAddedUiState.Shown(
            sentence = sentence,
            balances = balances,
            myShareMinor = shares[me] ?: 0L,
            totalMinor = expense.amountMinor,
            month = YearMonth.from(expense.occurredOn),
            rememberedFor = personal.counterparty?.takeIf { !typed && rule != null && rule.groupId == detail.group.id },
            paymentId = personal.id,
            busy = local.busy,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
