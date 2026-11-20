package com.expenses.app.groups.data

import com.expenses.app.data.db.ExpenseDao
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.parser.Direction
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeps the personal `expenses` rows in step with group expenses (docs/server.md 9.4), so the month rule
 * (sum of `myShareMinor` of ADDED debits) counts only the person's share of a split, and counts a split once.
 *
 * - My payment, split: the SMS or typed row is linked (`groupExpenseId`) and counts my share, even 0 while I am the
 *   payer. Its amount, payee and date stay as the bank SMS says; a typed row follows the group expense.
 * - Someone else paid and I have a share: a mirror row with source GROUP counts my share.
 * - Deleted, or the payer changed away from me: my row is unlinked and counts in full again; a mirror goes.
 *
 * A family expense is never a split: [sync] hands it to [family] ([FamilyLinker.applyStored], docs/family.md 7.4).
 *
 * Every function must run inside the caller's database transaction.
 *
 * @param expenses the personal expenses table.
 * @param groups the group tables.
 * @param zone India time: a group expense's date starts at 00:00 there.
 * @param family keeps the rows shared in the family month; null leaves family expenses alone.
 */
class MirrorWriter(
    private val expenses: ExpenseDao,
    private val groups: GroupDao,
    private val zone: ZoneId,
    private val family: FamilyLinker? = null,
) {
    /**
     * Brings the personal row of group expense [groupExpenseId] in line with the group expense as stored now. A family
     * expense (or one no longer stored, which may have been one) goes to [FamilyLinker.applyStored].
     */
    suspend fun sync(groupExpenseId: String) {
        val stored = groups.getExpenseWithShares(groupExpenseId)
        val linked = expenses.findByGroupExpense(groupExpenseId)
        if (stored == null || stored.expense.deleted) {
            if (linked != null) unlink(linked)
            family?.applyStored(groupExpenseId)
            return
        }
        val group = groups.getGroup(stored.expense.groupId) ?: return
        if (group.kind == GroupKind.FAMILY) {
            family?.applyStored(groupExpenseId)
            return
        }
        val me = group.myMemberId
        val expense = stored.expense
        val myShare = stored.shares.firstOrNull { it.memberId == me }?.shareMinor ?: 0L
        val iPaid = expense.paidByMemberId == me
        when {
            linked == null && !iPaid -> if (myShare > 0) expenses.insert(mirror(expense, myShare))
            linked == null -> linkRestored(expense, myShare)
            linked.source == ExpenseSource.GROUP ->
                if (!iPaid && myShare == 0L) expenses.delete(linked.id) else expenses.update(updatedMirror(linked, expense, myShare))
            iPaid -> expenses.update(updatedPayment(linked, expense, myShare))
            else -> {
                // The payer changed away from me: my payment counts in full again, and my share (if any) is a mirror.
                expenses.update(linked.copy(groupExpenseId = null, myShareMinor = linked.amountMinor))
                if (myShare > 0) expenses.insert(mirror(expense, myShare))
            }
        }
    }

    /**
     * Puts a personal row back as [snapshot] says (a rolled back or dropped change). A row the change made is
     * deleted.
     */
    suspend fun restore(snapshot: PersonalSnapshot) {
        val row = expenses.get(snapshot.expenseId) ?: return
        if (snapshot.deleteRow) {
            expenses.delete(row.id)
            return
        }
        val link = snapshot.groupExpenseId
        if (link != null) {
            // The change may have moved the link to another row (a payer changed away from me made a mirror): the
            // snapshot's row takes it back, so a split is still linked to one row only.
            expenses.findByGroupExpense(link)?.takeIf { it.id != row.id }?.let { unlink(it) }
        }
        val status = ExpenseStatus.entries.firstOrNull { it.name == snapshot.status } ?: row.status
        expenses.update(row.copy(status = status, myShareMinor = snapshot.myShareMinor, groupExpenseId = link))
    }

    /** The split fields of personal row [row] now, for a [RestoreSnapshot]. */
    fun snapshotOf(row: Expense, deleteRow: Boolean = false): PersonalSnapshot =
        PersonalSnapshot(row.id, row.status.name, row.myShareMinor, row.groupExpenseId, deleteRow)

    /**
     * Forgets the person's part in group expense [groupExpenseId], as if it had never been split with them (an undone
     * join, docs/server.md 9.4): its mirror goes, and a payment linked to it is unlinked and goes back to the status it
     * had before the split linked it ([rememberLink]): a payment that was waiting waits again for Add, Split or Ignore
     * rather than counting in full without the person deciding. With nothing remembered (a link made before
     * `grp_link_priors` existed, or put back by [restore]), a payment read from an SMS waits; one the person typed in
     * stays added, in full: it was never detected, and a waiting typed row would show as "Payment detected" on Home
     * and in Review payments.
     */
    suspend fun forget(groupExpenseId: String) {
        val row = expenses.findByGroupExpense(groupExpenseId) ?: return
        if (row.source == ExpenseSource.GROUP) {
            expenses.delete(row.id)
            return
        }
        val remembered = groups.getLinkPrior(groupExpenseId)?.takeIf { it.expenseId == row.id }?.status
        val before = remembered ?: if (row.source == ExpenseSource.MANUAL) ExpenseStatus.ADDED else ExpenseStatus.DETECTED
        expenses.update(row.copy(groupExpenseId = null, myShareMinor = row.amountMinor, status = before))
    }

    /**
     * Remembers that personal payment [expenseId] had [status] just before a split linked it to group expense
     * [groupExpenseId] in [groupId], for [forget]. The first link of that payment is kept: a link put back later (a
     * rollback, the payer changed back to the person) would otherwise record the status the first link gave it.
     */
    suspend fun rememberLink(groupId: String, groupExpenseId: String, expenseId: Long, status: ExpenseStatus) {
        if (groups.getLinkPrior(groupExpenseId)?.expenseId == expenseId) return
        groups.upsertLinkPrior(LinkPriorEntity(groupExpenseId, groupId, expenseId, status))
    }

    /** A different account signed in: mirrors of the old account go, and the person's own payments are unlinked. */
    suspend fun forgetAccount() {
        expenses.deleteGroupMirrors()
        expenses.unlinkAllGroupExpenses()
    }

    private suspend fun unlink(row: Expense) {
        if (row.source == ExpenseSource.GROUP) {
            expenses.delete(row.id)
        } else {
            expenses.update(row.copy(groupExpenseId = null, myShareMinor = row.amountMinor))
        }
    }

    /**
     * A group expense I paid that no row links, restored from the server (new phone, reinstall, signing in again):
     * link the one SMS or typed debit of that amount within a day of its date, else make a mirror. Without this the
     * payment found again by the SMS scan would wait as new and count in full on top of the split.
     */
    private suspend fun linkRestored(expense: GroupExpenseEntity, myShare: Long) {
        val dayStart = expense.occurredOn.atStartOfDay(zone).toInstant()
        val candidates = expenses.findUnlinkedPayments(
            amountMinor = expense.amountMinor,
            from = expense.occurredOn.minusDays(1).atStartOfDay(zone).toInstant(),
            to = expense.occurredOn.plusDays(2).atStartOfDay(zone).toInstant(),
        )
        val match = candidates.singleOrNull()
        if (match != null) {
            rememberLink(expense.groupId, expense.id, match.id, match.status)
            expenses.update(match.copy(status = ExpenseStatus.ADDED, myShareMinor = myShare, groupExpenseId = expense.id))
        } else {
            expenses.insert(mirror(expense, myShare).copy(occurredAt = dayStart))
        }
    }

    private fun mirror(expense: GroupExpenseEntity, myShare: Long): Expense = Expense(
        amountMinor = expense.amountMinor,
        direction = Direction.DEBIT,
        counterparty = expense.description,
        category = expense.category,
        occurredAt = expense.occurredOn.atStartOfDay(zone).toInstant(),
        source = ExpenseSource.GROUP,
        status = ExpenseStatus.ADDED,
        paidWith = PaidWith.OTHER,
        accountId = null,
        myShareMinor = myShare,
        groupExpenseId = expense.id,
    )

    private fun updatedMirror(row: Expense, expense: GroupExpenseEntity, myShare: Long): Expense = row.copy(
        amountMinor = expense.amountMinor,
        myShareMinor = myShare,
        counterparty = expense.description,
        category = expense.category,
        occurredAt = expense.occurredOn.atStartOfDay(zone).toInstant(),
        status = ExpenseStatus.ADDED,
    )

    private fun updatedPayment(row: Expense, expense: GroupExpenseEntity, myShare: Long): Expense =
        if (row.source == ExpenseSource.MANUAL) {
            row.copy(
                amountMinor = expense.amountMinor,
                myShareMinor = myShare,
                counterparty = expense.description,
                category = expense.category,
                occurredAt = sameDayOr(row, expense.occurredOn),
                status = ExpenseStatus.ADDED,
            )
        } else {
            // An SMS payment keeps what the bank said (amount, payee, time); the split sets its share and category.
            row.copy(myShareMinor = myShare, category = expense.category, status = ExpenseStatus.ADDED)
        }

    /** The row's time when it is on [date] already, else [date] at 00:00. */
    private fun sameDayOr(row: Expense, date: LocalDate) =
        if (row.occurredAt.atZone(zone).toLocalDate() == date) row.occurredAt else date.atStartOfDay(zone).toInstant()
}
