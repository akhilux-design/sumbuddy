package com.expenses.app.groups.data

import com.expenses.app.data.category.Category
import com.expenses.app.data.db.ExpenseDao
import com.expenses.app.data.repository.TransactionRunner
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.ExpenseInputDto
import com.expenses.app.groups.sync.Outbox
import com.expenses.app.groups.sync.SyncScheduler
import com.expenses.splits.SplitMath
import com.expenses.splits.SplitMethod
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Changes the server refused, for the Group page's "Not saved" row (design 02-everyday/43-group-page-change-not-saved,
 * flow lane 10 "If the server refuses a change"). The sync engine has already rolled each one back when it is kept
 * here (docs/server.md 4.1), so the group and the person's month are right; this only lets the person see it and
 * choose Try again or Dismiss.
 *
 * Owned by the group-page area (docs/groups-build-plan.md 4.2). Try again makes the change again through [repository]
 * (`addGroupExpense`, `updateGroupExpense`, `deleteGroupExpense`), so it is queued with a new idempotency key, rolled
 * back and mirrored like any other change. Only expense changes are shown and tried again (the design draws only
 * those).
 */
class RejectedChanges(
    private val transactions: TransactionRunner,
    private val groups: GroupDao,
    private val outbox: Outbox,
    private val mirrors: MirrorWriter,
    private val expenses: ExpenseDao,
    private val scheduler: SyncScheduler,
    private val clock: Clock,
    private val repository: GroupsRepository,
) {
    /** The refused changes of [groupId], newest first. */
    fun observe(groupId: String): Flow<List<RejectedChangeEntity>> = groups.observeRejected(groupId)

    /** Dismiss: forgets the refused change [id]. It was already put back as it was. */
    suspend fun dismiss(id: Long) = groups.deleteRejected(id)

    /**
     * What the refused expense change [change] tried, for its "Not saved" row: the body that was sent (a create or an
     * edit), or the expense as it was before (a delete). Null for a change that is not an expense, or that cannot be
     * read.
     */
    fun refusedExpense(change: RejectedChangeEntity): RefusedExpense? {
        if (change.entityKind != EntityKind.EXPENSE) return null
        return when (change.op) {
            OutboxOp.CREATE, OutboxOp.UPDATE -> sentBody(change)?.let { RefusedExpense.of(it) }
            OutboxOp.DELETE -> restoreOf(change)?.expense?.takeIf { !it.deleted && it.amountPaise != null }?.let { dto ->
                RefusedExpense(
                    description = dto.description.orEmpty(),
                    category = categoryOf(dto.category),
                    amountMinor = dto.amountPaise ?: 0L,
                    occurredOn = dto.occurredOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@let null,
                    paidByMemberId = dto.paidByMemberId.orEmpty(),
                    method = splitMethodOf(dto.splitMethod),
                    auto = dto.auto,
                    inputs = dto.shares.associate { it.memberId to it.input },
                )
            }
            OutboxOp.CONFIRM -> null
        }
    }

    /**
     * True when Try again can make [change] as it is now, so its button shows:
     *
     * - an expense create, edit or delete refused for a reason that may have passed (the group changed meanwhile, a
     *   bad request made from older data). Not a refusal that will come again as it is: a role rule (403), a body too
     *   large (413) or a reused key (422) (docs/server.md 4.1);
     * - a create or an edit whose sent body can be read and still divides as its method says;
     * - every person it names may still be named (docs/server.md 4.7): a create's payer and shares are active
     *   [members]; an edit may also keep the payer and the shares that [stored] (the expense as the server has it)
     *   already has, even when they left since. A create that split the person's own payment is not checked: Split
     *   it chooses the people again.
     *
     * The same rule decides [retry], so a change that names someone who left keeps only Dismiss, also when the page is
     * opened again, rather than being refused again on every Try again.
     *
     * @param members the group's members, removed ones included.
     * @param stored the expense an edit changes as stored now, or null (not here, or deleted: Try again then only
     * forgets the row).
     */
    fun canRetry(change: RejectedChangeEntity, members: List<MemberEntity>, stored: GroupExpenseWithShares?): Boolean {
        if (!retriedKind(change)) return false
        if (ownPaymentOf(change) != null) return true
        return when (change.op) {
            OutboxOp.CREATE -> sentDraft(change)?.let { fits(it, members, stored = null) } == true
            OutboxOp.UPDATE -> sentDraft(change)?.let { draft ->
                stored == null || stored.expense.deleted || fits(draft, members, stored)
            } == true
            OutboxOp.DELETE -> true
            OutboxOp.CONFIRM -> false
        }
    }

    /**
     * Try again for the refused change [id], as one transaction: the row is read, the change made again and the row
     * forgotten together, so a second tap (or a second page) finds the row gone and makes nothing
     * ([RetryOutcome.Gone]).
     *
     * - A change [canRetry] says no to is not made; the row stays ([RetryOutcome.NotPossible]).
     * - A create that split one of the person's own payments (its restore snapshot names the payment, which the
     *   rollback left waiting or counted in full again): nothing is made here; the row is forgotten and the answer is
     *   [RetryOutcome.SplitPayment], so the person splits that payment again in Split it. Making the expense again
     *   here would leave the payment unlinked, or add a second typed payment.
     * - Any other create is made again with a new expense id ([GroupsRepository.addGroupExpense]), an edit again
     *   ([GroupsRepository.updateGroupExpense]), a delete again ([GroupsRepository.deleteGroupExpense]), and the row
     *   is forgotten.
     *
     * @return what happened; see [RetryOutcome].
     */
    suspend fun retry(id: Long): RetryOutcome {
        val outcome = try {
            transactions.run { retryInTransaction(id) }
        } catch (_: IllegalArgumentException) {
            // The inputs no longer fit (the repository checks again): nothing was written, the row stays.
            RetryOutcome.NotPossible
        }
        // The repository asked for a sync inside the transaction; ask again now that the change is stored, so a sync
        // that started before the commit runs once more and sends it.
        if (outcome == RetryOutcome.Retried) scheduler.requestSync()
        return outcome
    }

    /** [retry]'s work, inside its transaction. */
    private suspend fun retryInTransaction(id: Long): RetryOutcome {
        val change = groups.getRejected(id) ?: return RetryOutcome.Gone
        if (!retriedKind(change)) return RetryOutcome.NotPossible
        val ownPayment = ownPaymentOf(change)
        if (ownPayment != null) {
            val payment = expenses.get(ownPayment.expenseId)
            groups.deleteRejected(id)
            // The payment was deleted, or split again meanwhile: there is nothing left to try again.
            return if (payment == null || payment.groupExpenseId != null) RetryOutcome.Gone else RetryOutcome.SplitPayment(payment.id)
        }
        when (change.op) {
            OutboxOp.CREATE -> {
                val draft = sentDraft(change) ?: return RetryOutcome.NotPossible
                if (!fits(draft, groups.membersOf(change.groupId), stored = null)) return RetryOutcome.NotPossible
                repository.addGroupExpense(change.groupId, draft)
            }
            OutboxOp.UPDATE -> {
                val draft = sentDraft(change) ?: return RetryOutcome.NotPossible
                val stored = groups.getExpenseWithShares(change.entityId)
                if (stored == null || stored.expense.deleted) {
                    // Deleted meanwhile (by anyone): the edit has nothing left to change.
                    groups.deleteRejected(id)
                    return RetryOutcome.Gone
                }
                if (!fits(draft, groups.membersOf(change.groupId), stored)) return RetryOutcome.NotPossible
                repository.updateGroupExpense(change.entityId, draft)
            }
            OutboxOp.DELETE -> repository.deleteGroupExpense(change.entityId)
            OutboxOp.CONFIRM -> return RetryOutcome.NotPossible
        }
        groups.deleteRejected(id)
        return RetryOutcome.Retried
    }

    /** An expense create, edit or delete whose refusal may not come again (see [canRetry]). */
    private fun retriedKind(change: RejectedChangeEntity): Boolean =
        change.entityKind == EntityKind.EXPENSE && change.op != OutboxOp.CONFIRM && change.errorCode !in NOT_RETRIED

    /** The person's own payment a refused create split (Try again sends them to Split it), or null. */
    private fun ownPaymentOf(change: RejectedChangeEntity): PersonalSnapshot? =
        restoreOf(change)?.personal?.takeIf { change.op == OutboxOp.CREATE && !it.deleteRow }

    /**
     * True when [draft] still divides as its method says and names only people the server takes (docs/server.md 4.7):
     * active [members], or, for an edit, the payer and share members [stored] already has.
     */
    private fun fits(draft: GroupExpenseDraft, members: List<MemberEntity>, stored: GroupExpenseWithShares?): Boolean {
        if (SplitMath.check(draft.method, draft.amountMinor, draft.inputs) != null) return false
        val active = members.filterNot { it.removed }.mapTo(HashSet()) { it.id }
        val payerKept = stored?.expense?.paidByMemberId
        val sharesKept = stored?.shares.orEmpty().mapTo(HashSet()) { it.memberId }
        return (draft.paidByMemberId in active || draft.paidByMemberId == payerKept) &&
            draft.inputs.keys.all { it in active || it in sharesKept }
    }

    private fun sentDraft(change: RejectedChangeEntity): GroupExpenseDraft? = sentBody(change)?.let { RefusedExpense.of(it).toDraft() }

    private fun sentBody(change: RejectedChangeEntity): ExpenseInputDto? =
        change.bodyJson?.let { runCatching { ApiJson.decodeFromString(ExpenseInputDto.serializer(), it) }.getOrNull() }

    private fun restoreOf(change: RejectedChangeEntity): RestoreSnapshot? =
        change.restoreJson?.let { runCatching { ApiJson.decodeFromString(RestoreSnapshot.serializer(), it) }.getOrNull() }

    private companion object {
        /** Codes Try again cannot get past: 403 `not_owner` / `not_allowed` / `not_member`, 413, 422. */
        val NOT_RETRIED = setOf("not_owner", "not_allowed", "not_member", "too_large", "idempotency_key_reused")
    }

    /**
     * The expense a refused change tried to make, or the one a refused delete tried to remove.
     *
     * @param description what it was for.
     * @param category its category.
     * @param amountMinor the whole amount in paise.
     * @param occurredOn its date.
     * @param paidByMemberId who paid.
     * @param method how it was divided.
     * @param auto true when a rule made it ("splits itself").
     * @param inputs member id to input, as [GroupExpenseDraft.inputs] takes them.
     */
    data class RefusedExpense(
        val description: String,
        val category: Category,
        val amountMinor: Long,
        val occurredOn: LocalDate,
        val paidByMemberId: String,
        val method: SplitMethod,
        val auto: Boolean,
        val inputs: Map<String, Long?>,
    ) {
        /** The same expense as a draft for [GroupsRepository]. */
        fun toDraft(): GroupExpenseDraft = GroupExpenseDraft(description, category, amountMinor, occurredOn, paidByMemberId, method, inputs, auto)

        /** Reading a sent body. */
        companion object {
            /** The expense of an `ExpenseInput` body. */
            fun of(input: ExpenseInputDto): RefusedExpense = RefusedExpense(
                description = input.description,
                category = categoryOf(input.category),
                amountMinor = input.amountPaise,
                occurredOn = runCatching { LocalDate.parse(input.occurredOn) }.getOrDefault(LocalDate.ofEpochDay(0)),
                paidByMemberId = input.paidByMemberId,
                method = splitMethodOf(input.splitMethod),
                auto = input.auto,
                inputs = input.shares.associate { it.memberId to it.input },
            )
        }
    }

    /** What Try again did ([RejectedChanges.retry]). */
    sealed interface RetryOutcome {
        /** The change was made again and queued; the refused row is gone. */
        data object Retried : RetryOutcome

        /**
         * The change split the person's own payment [expenseId], which waits again: open Split it for it. The refused
         * row is gone.
         */
        data class SplitPayment(val expenseId: Long) : RetryOutcome

        /** There was nothing left to try again (the row, the payment or the expense is gone); the refused row is gone. */
        data object Gone : RetryOutcome

        /** It cannot be tried again as it is (a refusal that would come again, or a body that cannot be read). */
        data object NotPossible : RetryOutcome
    }
}
