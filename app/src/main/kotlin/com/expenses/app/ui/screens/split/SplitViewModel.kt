package com.expenses.app.ui.screens.split

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.category.CategoryGuesser
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseWithAccount
import com.expenses.app.data.model.payee
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.groups.data.GroupDetail
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.GroupSummary
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.SplitRule
import com.expenses.app.ui.format.shortName
import com.expenses.app.ui.screens.payment.PaymentAccount
import com.expenses.app.ui.state.keepForm
import com.expenses.parser.Direction
import com.expenses.splits.SplitMethod
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Split it (design 02-everyday/09, 10, 11) for a payment or a typed-in expense ([source]). The group is prefilled from
 * the payment's "Do this every time" rule (`GroupsRepository.splitRuleFor`), else the only group there is; the parts
 * from the rule too, else equal parts. A group where everyone else has left is neither offered nor prefilled. "Split ₹450" stays off until the parts add up ([SplitParts]). A payment is
 * split with `GroupsRepository.splitPayment`, a typed expense made with `addGroupExpense`; both are queued, so
 * splitting works offline.
 *
 * @param source the payment, or the typed expense.
 * @param groups groups, members, rules and the split itself.
 * @param expenses the person's payments.
 * @param clock today's date and the time zone.
 * @param savedState where the choices are kept while Android stops the app in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SplitViewModel(
    private val source: SplitSource,
    private val groups: GroupsRepository,
    private val expenses: ExpenseRepository,
    private val clock: Clock,
    savedState: SavedStateHandle,
) : ViewModel() {
    /** What the person chose so far. [texts] holds only the parts they typed; the others show the defaults. */
    private data class Choices(
        val ready: Boolean = false,
        val groupId: String? = null,
        val method: SplitMethod = SplitMethod.EQUAL,
        val texts: Map<String, String> = emptyMap(),
        val remember: Boolean? = null,
        val saving: Boolean = false,
        val finished: Boolean = false,
    )

    /** What is being split, read once. */
    private sealed interface Basis {
        data class Ready(
            val header: SplitHeader,
            val month: YearMonth,
            val counterparty: String?,
            val payerMemberId: String?,
            val fixedGroupId: String?,
            val linked: Boolean,
            val splittable: Boolean,
        ) : Basis

        data object Gone : Basis
    }

    private val choices = MutableStateFlow(restore(savedState) ?: Choices())

    /** The payment's rule as it was before this split; read once. */
    private val rule = MutableStateFlow<SplitRule?>(null)

    private val finished = MutableStateFlow<SplitDone?>(null)

    /** Set once the split is made; the caller then moves on and calls [doneHandled]. */
    val done: StateFlow<SplitDone?> = finished.asStateFlow()

    /** The caller opened the next screen for [done]: it is not opened again if this screen shows once more. */
    fun doneHandled() {
        finished.value = null
    }

    private val prefill: Job

    init {
        savedState.keepForm(viewModelScope, choices) {
            set(READY_KEY, it.ready)
            set(GROUP_KEY, it.groupId)
            set(METHOD_KEY, it.method.name)
            set(TEXTS_KEY, TextsJson.encodeToString(TEXTS, it.texts))
            set(REMEMBER_KEY, it.remember)
        }
        prefill = viewModelScope.launch { prefill() }
    }

    private val basis: Flow<Basis> = when (source) {
        is SplitSource.Payment -> expenses.observe(source.expenseId).map { row -> row?.let(::paymentBasis) ?: Basis.Gone }
        is SplitSource.Typed -> flowOf(source.draft?.let(::typedBasis) ?: Basis.Gone)
    }

    private val chosenGroup: Flow<GroupDetail?> = choices.map { it.groupId }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else groups.observeGroup(id) }

    /** The screen's state. */
    val state: StateFlow<SplitUiState> = combine(basis, groups.observeGroups(), chosenGroup, choices, rule) { basis, named, detail, choices, rule ->
        build(basis, named, detail, choices, rule)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SplitUiState.Loading)

    /** Handles what the person did. NewGroup and Back are handled by the caller and ignored here. */
    fun onEvent(event: SplitEvent) {
        when (event) {
            is SplitEvent.GroupPicked -> pickGroup(event.groupId)
            is SplitEvent.GroupChosen -> pickGroup(event.groupId)
            is SplitEvent.MethodPicked -> choices.update {
                if (it.method == event.method) it else it.copy(method = event.method, texts = emptyMap())
            }
            is SplitEvent.PartChanged -> choices.update {
                it.copy(texts = it.texts + (event.memberId to SplitParts.clean(it.method, event.text)))
            }
            is SplitEvent.RememberChanged -> choices.update { it.copy(remember = event.on) }
            SplitEvent.Split -> split()
            SplitEvent.NewGroup, SplitEvent.Back -> Unit
        }
    }

    /** Someone else paid a typed expense: it belongs to their group, so the group cannot change (not designed). */
    private val groupFixed: Boolean =
        (source as? SplitSource.Typed)?.draft?.let { it.groupId != null && it.paidByMemberId != null } == true

    private fun pickGroup(groupId: String) {
        if (groupFixed) return
        choices.update { if (it.groupId == groupId) it else it.copy(ready = true, groupId = groupId, texts = emptyMap()) }
    }

    // ---- Building the state ----

    private fun build(basis: Basis, named: List<GroupSummary>, detail: GroupDetail?, choices: Choices, rule: SplitRule?): SplitUiState {
        if (basis !is Basis.Ready) return SplitUiState.CannotSplit(CannotSplitReason.GONE)
        val busy = choices.saving || choices.finished
        if (!busy && basis.linked) return SplitUiState.CannotSplit(CannotSplitReason.ALREADY_SPLIT)
        if (!basis.splittable) return SplitUiState.CannotSplit(CannotSplitReason.NOT_SPLITTABLE)
        if (!choices.ready) return SplitUiState.Loading

        // The chosen group's members, once its row is here (it follows the choice a moment later).
        val group = detail?.takeIf { it.group.id == choices.groupId }
        val myId = group?.group?.myMemberId
        // First names, as 09 writes the people rows; whole names where two clash.
        val everyone = group?.members.orEmpty().map { it.member.name }
        // Each person's colour, the same as on every other screen (the person's own for the person).
        val colours = group?.members.orEmpty().associate { it.member.id to it.avatar }
        val members = group?.members.orEmpty().filter { !it.member.removed }.map { it.member.copy(name = shortName(it.member.name, everyone)) }
        val ids = members.map { it.id }
        val payerId = basis.payerMemberId?.takeIf { id -> id in ids } ?: myId
        val defaults = SplitParts.defaults(choices.method, basis.header.amountMinor, ids)
        val texts = defaults + choices.texts.filterKeys { it in ids }
        val outcome = if (myId == null) null else SplitParts.evaluate(choices.method, basis.header.amountMinor, ids, myId, texts)

        val people = members.map { member ->
            val isMe = member.id == myId
            SplitPerson(
                memberId = member.id,
                name = member.name,
                initial = initialOf(member.name),
                isMe = isMe,
                isPayer = member.id == payerId,
                shareMinor = outcome?.shares?.get(member.id) ?: 0L,
                input = texts[member.id].orEmpty(),
                avatar = colours.getValue(member.id),
            )
        }

        val canChangeGroup = !groupFixed
        // A group where everyone else has left has nobody to split with: it is not offered. The chosen group always
        // has its chip (a group just made with nobody in it yet, or the group the expense was typed in).
        val chips = buildList {
            if (canChangeGroup) addAll(named.filter(::hasSomeoneElse).map { GroupChip(it.id, it.name) })
            if (group != null && none { it.id == group.group.id }) add(GroupChip(group.group.id, group.name))
        }
        val hasMerchant = source is SplitSource.Payment &&
            CategoryGuesser.merchantKey(basis.counterparty.orEmpty()).isNotEmpty()
        val payerName = basis.payerMemberId?.let { payer -> members.firstOrNull { it.id == payer }?.name }
        return SplitUiState.Form(
            header = basis.header.copy(payerName = payerName ?: basis.header.payerName),
            groups = chips,
            chosenGroupId = choices.groupId,
            chosenGroupName = group?.name.orEmpty(),
            canChangeGroup = canChangeGroup,
            method = choices.method,
            people = people,
            note = outcome?.note,
            month = basis.month,
            rememberRule = if (hasMerchant && group != null) choices.remember ?: (rule != null) else null,
            inputs = outcome?.inputs,
            saving = busy,
        )
    }

    private fun paymentBasis(row: ExpenseWithAccount): Basis {
        val expense = row.expense
        return Basis.Ready(
            header = SplitHeader(
                title = expense.payee,
                category = expense.category,
                amountMinor = expense.amountMinor,
                date = expense.occurredAt.atZone(clock.zone).toLocalDate(),
                today = LocalDate.now(clock),
                account = PaymentAccount.from(row.account),
                paidWith = expense.paidWith,
                payerName = null,
                typed = false,
            ),
            month = YearMonth.from(expense.occurredAt.atZone(clock.zone)),
            counterparty = expense.counterparty,
            payerMemberId = null,
            fixedGroupId = null,
            linked = expense.groupExpenseId != null,
            splittable = expense.direction == Direction.DEBIT && expense.currency == Expense.INR,
        )
    }

    private fun typedBasis(draft: TypedExpense): Basis = Basis.Ready(
        header = SplitHeader(
            title = draft.whatFor.trim().ifEmpty { null },
            category = draft.category,
            amountMinor = draft.amountMinor,
            date = draft.date,
            today = LocalDate.now(clock),
            account = null,
            paidWith = draft.paidWith,
            payerName = null,
            typed = true,
        ),
        month = YearMonth.from(draft.date),
        counterparty = null,
        payerMemberId = draft.paidByMemberId,
        fixedGroupId = draft.groupId,
        linked = false,
        splittable = true,
    )

    /** True when [group] has someone besides the person to split with. */
    private fun hasSomeoneElse(group: GroupSummary): Boolean = group.memberCount > 1

    // ---- Prefill ----

    private suspend fun prefill() {
        val kept = (source as? SplitSource.Payment)?.let { payment ->
            expenses.get(payment.expenseId)?.counterparty?.let { groups.splitRuleFor(it).first() }
        }
        rule.value = kept
        if (choices.value.ready) return
        val typedGroup = (source as? SplitSource.Typed)?.draft?.groupId
        // Neither the rule's group nor the only group is chosen when everyone else has left it.
        val ruleGroup = kept?.groupId?.takeIf { id ->
            groups.observeGroup(id).first()?.let { group -> group.members.count { !it.member.removed } > 1 } == true
        }
        val groupId = typedGroup ?: ruleGroup ?: groups.observeGroups().first().filter(::hasSomeoneElse).singleOrNull()?.id
        val useRule = typedGroup == null && kept != null && ruleGroup == groupId
        choices.update {
            if (it.ready) {
                it
            } else {
                it.copy(
                    ready = true,
                    groupId = groupId,
                    method = if (useRule) kept!!.method else SplitMethod.EQUAL,
                    texts = if (useRule) SplitParts.texts(kept!!.method, kept.inputs) else emptyMap(),
                )
            }
        }
    }

    // ---- Split ----

    private fun split() {
        val form = state.value as? SplitUiState.Form ?: return
        if (!form.canSplit) return
        val groupId = form.chosenGroupId ?: return
        val inputs = form.inputs ?: return
        choices.update { it.copy(saving = true) }
        viewModelScope.launch {
            prefill.join()
            val done = try {
                when (source) {
                    is SplitSource.Payment -> splitPayment(source.expenseId, groupId, form, inputs)
                    is SplitSource.Typed -> source.draft?.let { addTyped(it, groupId, form.method, inputs) }
                }
            } catch (_: IllegalArgumentException) {
                // The payment changed meanwhile (split elsewhere, deleted) or the group is gone: the state shows why.
                null
            }
            if (done == null) {
                choices.update { it.copy(saving = false) }
            } else {
                choices.update { it.copy(finished = true) }
                finished.value = done
            }
        }
    }

    private suspend fun splitPayment(expenseId: Long, groupId: String, form: SplitUiState.Form, inputs: Map<String, Long?>): SplitDone {
        val row = expenses.get(expenseId)
        val counterparty = row?.counterparty
        val before = rule.value
        val remember = form.rememberRule == true
        val id = groups.splitPayment(expenseId, groupId, form.method, inputs, rememberRule = remember, auto = false)
        if (counterparty != null) {
            // A rule that split by itself stays automatic; switching "Do this every time" off forgets it.
            if (remember && before?.auto == true) groups.setSplitRuleAuto(counterparty, true)
            if (!remember && before != null && form.rememberRule != null) groups.forgetSplitRule(counterparty)
        }
        val offer = remember && before != null && !before.auto && before.groupId == groupId && before.method == form.method
        return SplitDone(id, sameSplitOffer = offer, expenseId = expenseId, priorStatus = row?.status)
    }

    private suspend fun addTyped(draft: TypedExpense, groupId: String, method: SplitMethod, inputs: Map<String, Long?>): SplitDone? {
        val group = groups.observeGroup(groupId).first() ?: return null
        val me = group.group.myMemberId
        val payer = draft.paidByMemberId?.takeIf { id -> group.members.any { it.member.id == id && !it.member.removed } } ?: me
        val id = groups.addGroupExpense(
            groupId,
            GroupExpenseDraft(
                description = draft.whatFor,
                category = draft.category,
                amountMinor = draft.amountMinor,
                occurredOn = draft.date,
                paidByMemberId = payer,
                method = method,
                inputs = inputs,
            ),
        )
        if (payer == me) {
            // How the person paid stays on their own row; the group never gets it.
            expenses.findByGroupExpense(id)?.let { row ->
                if (row.paidWith != draft.paidWith) expenses.update(row.copy(paidWith = draft.paidWith))
            }
        }
        return SplitDone(id, sameSplitOffer = false, expenseId = null)
    }

    private fun restore(savedState: SavedStateHandle): Choices? {
        if (savedState.get<Boolean>(READY_KEY) != true) return null
        return Choices(
            ready = true,
            groupId = savedState[GROUP_KEY],
            method = savedState.get<String>(METHOD_KEY)?.let { name -> SplitMethod.entries.firstOrNull { it.name == name } } ?: SplitMethod.EQUAL,
            texts = savedState.get<String>(TEXTS_KEY)?.let { runCatching { TextsJson.decodeFromString(TEXTS, it) }.getOrNull() }.orEmpty(),
            remember = savedState[REMEMBER_KEY],
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val READY_KEY = "split_ready"
        const val GROUP_KEY = "split_group"
        const val METHOD_KEY = "split_method"
        const val TEXTS_KEY = "split_texts"
        const val REMEMBER_KEY = "split_remember"
        val TEXTS = MapSerializer(String.serializer(), String.serializer())
        val TextsJson = Json
    }
}
