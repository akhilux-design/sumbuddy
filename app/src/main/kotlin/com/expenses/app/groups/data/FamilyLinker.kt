package com.expenses.app.groups.data

import com.expenses.app.data.category.Category
import com.expenses.app.data.db.ExpenseDao
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.repository.PersonalChangeListener
import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.ExpenseInputDto
import com.expenses.app.groups.sync.Outbox
import com.expenses.app.groups.sync.SyncScheduler
import com.expenses.parser.Direction
import com.expenses.splits.SplitMath
import com.expenses.splits.SplitMethod
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Keeps the family month in step with the person's own payments (docs/family.md 7). A payment is shared as a family
 * expense exactly while it is [shareable] and a family is on this phone; [Expense.familyExpenseId] links it to its
 * family expense. Only the person's own payments are shared, and only the fields of 7.2: the description (the payee,
 * or the category's name when the payee is a UPI ID or holds a long number), category, amount, India date and the
 * person as payer with the whole amount as their share. Never the note, account, card, "paid with", SMS text or
 * sender, or the exact time.
 *
 * Changes go through the outbox like any group change, so marking Family works offline; a create followed by a delete
 * before anything was sent sends nothing (5.1 coalescing). A partner's family expense never becomes a personal row.
 *
 * Every function runs inside the caller's database transaction.
 *
 * @param zone India time: the family month uses India dates, as the server does.
 * @param categoryName a category's display name, for the description of a payment without a usable payee.
 * @param newId makes family expense ids (UUID v4).
 */
class FamilyLinker(
    private val expenses: ExpenseDao,
    private val groups: GroupDao,
    private val outbox: Outbox,
    private val scheduler: SyncScheduler,
    private val clock: Clock,
    private val zone: ZoneId,
    private val categoryName: (Category) -> String = { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase) },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : PersonalChangeListener {

    /**
     * Whether [row] is shared once a family is on this phone (7.1): tagged Family, added, a rupee debit, not split,
     * an amount the server takes (₹0.01 to ₹1 crore), an India date from 1 Jan 2000 to tomorrow, and not a mirror of
     * group data unless it is the mirror of the person's own family expense (a new phone).
     */
    fun shareable(row: Expense): Boolean {
        if (row.scope != ExpenseScope.FAMILY || row.status != ExpenseStatus.ADDED) return false
        if (row.direction != Direction.DEBIT || row.currency != Expense.INR || row.groupExpenseId != null) return false
        if (row.amountMinor !in 1..SplitMath.MAX_AMOUNT_MINOR) return false
        val date = row.occurredAt.atZone(zone).toLocalDate()
        if (date < EARLIEST_DATE || date > today().plusDays(1)) return false
        return row.source != ExpenseSource.GROUP || row.familyExpenseId != null
    }

    // ---- Writes of the person's own payments (7.3) ----

    /**
     * Rows [ids] were written ([com.expenses.app.data.repository.ExpenseRepository], a split, an undone split): each
     * is shared, taken out of the family month, or its shared copy updated. Nothing happens while no family is on this
     * phone or its first pull has not ended. A row is shared only while the family's data is [complete]: until then
     * the person's own family expense for that payment (shared by their old phone) may be in a part not pulled yet,
     * and sharing it again would count it twice. [reconcile] shares it once the pull is complete.
     */
    override suspend fun onChanged(ids: Collection<Long>) {
        val family = groups.familyGroup()?.takeIf(::started) ?: return
        val complete = complete(family)
        var queued = false
        for (id in ids.distinct()) {
            val row = expenses.get(id) ?: continue
            if (row.scope != ExpenseScope.FAMILY && row.familyExpenseId == null) continue
            val linked = row.familyExpenseId
            val eligible = shareable(row)
            queued = when {
                linked == null -> eligible && complete && create(family, row)
                !eligible -> takeOut(row, linked)
                else -> update(family, row, linked)
            } || queued
        }
        if (queued) scheduler.requestSync()
    }

    /** Rows [rows] are about to be deleted: the family expenses they are shared as are deleted too. */
    override suspend fun onDeleting(rows: List<Expense>) {
        var queued = false
        for (row in rows) {
            val linked = row.familyExpenseId ?: continue
            queued = tombstone(linked) || queued
        }
        if (queued) scheduler.requestSync()
    }

    /**
     * Shares what should be shared and takes out what should not, by eligibility only (7.3): linked rows that are no
     * longer eligible are taken out; once the family's data is [complete], rows linked to a family expense this phone
     * does not have are unlinked, and unlinked eligible rows are shared. Before that nothing is shared: a pull that
     * failed or has not ended may still hold the person's own family expense for the payment ([onChanged]). Field
     * differences are never pushed here, so two phones of one person cannot overwrite each other in turn. Run by the
     * sync engine before each push and after each pull, and when a family is made.
     *
     * @return true when something was queued.
     */
    suspend fun reconcile(): Boolean {
        val family = groups.familyGroup()?.takeIf(::started) ?: return false
        val complete = complete(family)
        val candidates = expenses.familyCandidates()
        // One read of which family expenses this phone has, rather than one per linked row on every push and pull.
        val stored = if (candidates.any { it.familyExpenseId != null }) groups.expenseIdsOf(family.id).toHashSet() else emptySet()
        var queued = false
        for (candidate in candidates) {
            var row = candidate
            val linked = row.familyExpenseId
            if (linked != null) {
                when {
                    linked in stored || groups.getExpense(linked) != null -> {
                        if (!shareable(row)) queued = takeOut(row, linked) || queued
                        continue
                    }
                    !complete -> continue
                    else -> {
                        row = row.copy(familyExpenseId = null)
                        expenses.update(row)
                    }
                }
            }
            if (complete && shareable(row)) queued = create(family, row) || queued
        }
        return queued
    }

    /** [reconcile] after a pull: anything it queued is sent at once. */
    suspend fun reconcileAndRequestSync(): Boolean = reconcile().also { if (it) scheduler.requestSync(expedited = true) }

    // ---- Pulled family expenses (7.3 applyStored) ----

    /**
     * Brings the personal row of family expense [groupExpenseId] in line with the expense as stored now. Called by
     * [MirrorWriter.sync] for every family expense written to this phone, tombstones and ids no longer stored
     * included.
     *
     * - A partner's expense: nothing (no personal row, ever).
     * - The person's own, linked: a typed row or a mirror follows the description, category, amount and date; a row
     *   read from SMS follows the category only (the bank's amount, payee and time stay).
     * - The person's own, not linked (a new phone, a reinstall): the one unlinked SMS or typed rupee debit of that
     *   amount within a day is linked and added as Family; with none or several, a mirror row counts it.
     * - Deleted or gone while linked: unlinked and counted as Just me (taken out on another phone of the person).
     */
    suspend fun applyStored(groupExpenseId: String) {
        val linked = expenses.findByFamilyExpense(groupExpenseId)
        val stored = groups.getExpense(groupExpenseId)
        if (stored == null || stored.deleted) {
            if (linked != null) expenses.update(linked.copy(familyExpenseId = null, scope = ExpenseScope.ME))
            return
        }
        val group = groups.getGroup(stored.groupId) ?: return
        if (group.kind != GroupKind.FAMILY || stored.paidByMemberId != group.myMemberId) return
        if (linked != null) follow(linked, stored) else linkRestored(stored)
    }

    // ---- Refusals and the family leaving this phone ----

    /**
     * The server refused family expense change [entry] with [error] (instead of the rollback and the "Not saved" row of
     * a split, 7.3). The payment is left as Just me and unlinked, so it is not sent again:
     * - a create: the local copy goes;
     * - an update: the server's copy comes back and its delete is queued;
     * - a delete (`not_found`): the local tombstone goes.
     *
     * `not_member` and `group_deleted` never reach this: the family leaves the phone.
     */
    suspend fun onRejected(entry: OutboxEntry, @Suppress("UNUSED_PARAMETER") error: ApiError) {
        val id = entry.entityId
        when (entry.op) {
            OutboxOp.CREATE -> {
                groups.deleteExpense(id)
                keepAsJustMe(id)
            }
            OutboxOp.UPDATE -> {
                keepAsJustMe(id)
                val before = outbox.restoreOf(entry)?.expense
                val restored = before?.toEntity(SyncState.SYNCED, clock.instant())
                if (restored == null) {
                    groups.getExpense(id)?.let { groups.upsertExpense(it.copy(deleted = true, syncState = SyncState.SYNCED)) }
                    groups.deleteShares(id)
                } else {
                    groups.upsertExpense(restored)
                    groups.deleteShares(id)
                    groups.insertShares(before.shareEntities())
                    if (tombstone(id)) scheduler.requestSync()
                }
            }
            OutboxOp.DELETE -> groups.getExpense(id)?.takeIf { it.deleted }?.let { groups.deleteExpense(id) }
            OutboxOp.CONFIRM -> Unit
        }
    }

    /**
     * The family [groupId] left this phone (the person left or was removed, it was deleted, or it is `lost`): every
     * row shared in it is unlinked. Rows keep the Family tag on this phone, as before the family; joining a family
     * later shares them again.
     */
    suspend fun unlinkFamily(groupId: String) {
        val anotherFamily = groups.allGroups().any { it.kind == GroupKind.FAMILY && it.id != groupId }
        if (anotherFamily) {
            groups.expenseIdsOf(groupId).chunked(MAX_IDS_PER_QUERY).forEach { expenses.unlinkFamily(it) }
        } else {
            expenses.unlinkAllFamily()
        }
    }

    /**
     * A different account signed in: every row is unlinked (their family expenses were the old account's). Signing out
     * does not unlink, so the same account signing in again finds its links.
     */
    suspend fun unlinkAll() = expenses.unlinkAllFamily()

    // ---- Helpers ----

    /** Makes the family expense of [row] and queues it. */
    private suspend fun create(family: GroupEntity, row: Expense): Boolean {
        val id = newId()
        val me = family.myMemberId
        val expense = GroupExpenseEntity(
            id = id,
            groupId = family.id,
            description = descriptionOf(row),
            category = row.category,
            amountMinor = row.amountMinor,
            occurredOn = dateOf(row),
            paidByMemberId = me,
            splitMethod = SplitMethod.EQUAL,
            auto = false,
            createdByMemberId = me,
            updatedByMemberId = me,
            updatedAt = clock.instant(),
            deleted = false,
            version = 0,
            syncState = SyncState.PENDING,
        )
        val stored = store(expense, me)
        expenses.update(row.copy(familyExpenseId = id))
        outbox.append(family.id, EntityKind.EXPENSE, id, OutboxOp.CREATE, "PUT", path(family.id, id), encode(stored.toInput(null)), RestoreSnapshot())
        return true
    }

    /** Changes the family expense [linked] of [row] when what would be sent differs from it. */
    private suspend fun update(family: GroupEntity, row: Expense, linked: String): Boolean {
        val before = groups.getExpenseWithShares(linked) ?: return false
        val old = before.expense
        if (old.deleted || old.groupId != family.id || old.paidByMemberId != family.myMemberId) return false
        val description = descriptionOf(row)
        val date = dateOf(row)
        if (old.description == description && old.category == row.category && old.amountMinor == row.amountMinor && old.occurredOn == date) {
            return false
        }
        val changed = old.copy(
            description = description,
            category = row.category,
            amountMinor = row.amountMinor,
            occurredOn = date,
            updatedByMemberId = family.myMemberId,
            updatedAt = clock.instant(),
            syncState = SyncState.PENDING,
        )
        val stored = store(changed, family.myMemberId)
        outbox.append(
            family.id, EntityKind.EXPENSE, linked, OutboxOp.UPDATE, "PUT", path(family.id, linked),
            encode(stored.toInput(old.version.takeIf { it > 0 })), RestoreSnapshot(expense = before.toDto()),
        )
        return true
    }

    /** Takes [row] out of the family month: unlinked, and its family expense deleted. */
    private suspend fun takeOut(row: Expense, linked: String): Boolean {
        expenses.update(row.copy(familyExpenseId = null))
        return tombstone(linked)
    }

    /** Deletes family expense [id] on this phone and queues the delete; false when it is not here or already deleted. */
    private suspend fun tombstone(id: String): Boolean {
        val before = groups.getExpenseWithShares(id) ?: return false
        if (before.expense.deleted) return false
        val groupId = before.expense.groupId
        groups.upsertExpense(before.expense.copy(deleted = true, updatedAt = clock.instant(), syncState = SyncState.PENDING))
        groups.deleteShares(id)
        outbox.append(groupId, EntityKind.EXPENSE, id, OutboxOp.DELETE, "DELETE", path(groupId, id), null, RestoreSnapshot(expense = before.toDto()))
        return true
    }

    /** Writes [expense] with its one share: the payer, for the whole amount. */
    private suspend fun store(expense: GroupExpenseEntity, payer: String): GroupExpenseWithShares {
        val share = ExpenseShareEntity(expense.id, payer, expense.amountMinor, null)
        groups.upsertExpense(expense)
        groups.deleteShares(expense.id)
        groups.insertShares(listOf(share))
        return GroupExpenseWithShares(expense, listOf(share))
    }

    /** The row shared as [groupExpenseId] stays on this phone as Just me, unlinked. */
    private suspend fun keepAsJustMe(groupExpenseId: String) {
        expenses.findByFamilyExpense(groupExpenseId)?.let { expenses.update(it.copy(familyExpenseId = null, scope = ExpenseScope.ME)) }
    }

    /** A linked row follows the person's own family expense [stored] (changed on another phone of theirs). */
    private suspend fun follow(row: Expense, stored: GroupExpenseEntity) {
        val next = when (row.source) {
            ExpenseSource.SMS -> row.copy(category = stored.category)
            ExpenseSource.MANUAL -> row.copy(
                counterparty = if (descriptionOf(row) == stored.description) row.counterparty else stored.description,
                category = stored.category,
                amountMinor = stored.amountMinor,
                myShareMinor = stored.amountMinor,
                occurredAt = if (dateOf(row) == stored.occurredOn) row.occurredAt else stored.occurredOn.atStartOfDay(zone).toInstant(),
            )
            ExpenseSource.GROUP -> row.copy(
                counterparty = stored.description,
                category = stored.category,
                amountMinor = stored.amountMinor,
                myShareMinor = stored.amountMinor,
                occurredAt = if (dateOf(row) == stored.occurredOn) row.occurredAt else stored.occurredOn.atStartOfDay(zone).toInstant(),
                status = ExpenseStatus.ADDED,
                scope = ExpenseScope.FAMILY,
            )
        }
        if (next != row) expenses.update(next)
    }

    /**
     * The person's own family expense [stored] that no row links (a new phone, a reinstall): the one SMS or typed rupee
     * debit of that amount within a day of its date is linked, else a mirror row counts it, so the payment found again
     * by the SMS scan does not count twice.
     */
    private suspend fun linkRestored(stored: GroupExpenseEntity) {
        val match = expenses.findUnlinkedPayments(
            amountMinor = stored.amountMinor,
            from = stored.occurredOn.minusDays(1).atStartOfDay(zone).toInstant(),
            to = stored.occurredOn.plusDays(2).atStartOfDay(zone).toInstant(),
        ).singleOrNull()
        if (match != null) {
            expenses.update(match.copy(familyExpenseId = stored.id, scope = ExpenseScope.FAMILY, status = ExpenseStatus.ADDED))
            return
        }
        expenses.insert(
            Expense(
                amountMinor = stored.amountMinor,
                direction = Direction.DEBIT,
                counterparty = stored.description,
                category = stored.category,
                occurredAt = stored.occurredOn.atStartOfDay(zone).toInstant(),
                source = ExpenseSource.GROUP,
                status = ExpenseStatus.ADDED,
                paidWith = PaidWith.OTHER,
                myShareMinor = stored.amountMinor,
                scope = ExpenseScope.FAMILY,
                familyExpenseId = stored.id,
            ),
        )
    }

    private fun descriptionOf(row: Expense): String = GroupText.descriptionFor(row.counterparty, categoryName(row.category))

    private fun dateOf(row: Expense): LocalDate = row.occurredAt.atZone(zone).toLocalDate()

    private fun today(): LocalDate = clock.instant().atZone(zone).toLocalDate()

    private fun path(groupId: String, id: String) = "/v1/groups/$groupId/expenses/$id"

    private fun encode(input: ExpenseInputDto) = ApiJson.encodeToJsonElement(ExpenseInputDto.serializer(), input)

    private companion object {
        val EARLIEST_DATE: LocalDate = LocalDate.of(2000, 1, 1)

        /** SQLite allows 999 bound values per statement. */
        const val MAX_IDS_PER_QUERY = 900
    }
}

/**
 * True once a family's first pull has ended (or it was made on this phone): before that, what the server has of it is
 * not known, so nothing is shared, taken out or linked ([FamilyLinker]). New rows are shared only once it is also
 * [complete].
 */
internal fun started(family: GroupEntity): Boolean = family.version > 0

/** True when the family's data is complete: pulled up to the newest version the server reported, and the pull worked. */
internal fun complete(family: GroupEntity): Boolean = started(family) && family.version >= family.latestVersion && !family.loadFailed

/**
 * How a family's data stands on this phone, as [GroupsRepository] says it of a group: [LoadState.FAILED] when the last
 * pull failed (what was pulled before still shows), [LoadState.LOADING] until its first pull ended (after Join Family or
 * a sign-in), else [LoadState.READY].
 */
fun familyLoadState(family: GroupEntity): LoadState = when {
    family.loadFailed -> LoadState.FAILED
    !started(family) -> LoadState.LOADING
    else -> LoadState.READY
}
