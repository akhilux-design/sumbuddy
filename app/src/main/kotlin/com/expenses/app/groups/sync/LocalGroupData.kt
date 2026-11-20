package com.expenses.app.groups.sync

import com.expenses.app.data.repository.TransactionRunner
import com.expenses.app.groups.api.LostReason
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.FamilyLinker
import com.expenses.app.groups.data.GroupDao
import com.expenses.app.groups.data.GroupExtrasDao
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.ImportState
import com.expenses.app.groups.data.MirrorWriter
import com.expenses.app.groups.data.OutboxDao
import com.expenses.app.groups.data.OutboxEntry
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.splitwise.ImportBodies

/**
 * Removing group data from this phone: one group (it was deleted, the person left or was removed, or their join was
 * undone), every group (sign out, account deletion), or every group and the old account's mirrors (a different
 * account signed in). Personal payments stay. Also how a Splitwise import made here stands (docs/splitwise-import.md
 * 5.4): its parts sent, finished, or refused and taken back.
 *
 * @param notifier the "Groups" notifications: every one goes with every group (sign out, account deletion, another
 * account).
 * @param family unlinks the rows shared in a family that leaves this phone (docs/family.md 7.3); null for none.
 */
class LocalGroupData(
    private val transactions: TransactionRunner,
    private val groups: GroupDao,
    private val outboxDao: OutboxDao,
    private val outbox: Outbox,
    private val extras: GroupExtrasDao,
    private val mirrors: MirrorWriter,
    private val notifier: GroupNotifier = GroupNotifier {},
    private val family: FamilyLinker? = null,
) {
    /**
     * Removes group [groupId] with its rows, links, pending UPI payments and waiting changes (docs/server.md 5.2).
     * Personal payments linked to its expenses stay (the spending happened), except a split whose expense never
     * reached the server: that payment goes back to how it was (9.4).
     *
     * @param forgetSplits true when the person's join was undone: they were never in the group, so its spending was
     * not theirs. The mirrors of its expenses go too, and payments linked to them go back to how they were before the
     * split linked them (a waiting payment waits again; [MirrorWriter.forget]).
     *
     * A family's rows are unlinked first ([FamilyLinker.unlinkFamily]); they keep the Family tag on this phone.
     */
    suspend fun removeGroup(groupId: String, forgetSplits: Boolean = false) = transactions.run {
        if (family != null && groups.getGroup(groupId)?.kind == GroupKind.FAMILY) family.unlinkFamily(groupId)
        restoreNeverSent(outboxDao.forGroup(groupId))
        if (forgetSplits) groups.activeExpensesOf(groupId).forEach { mirrors.forget(it.expense.id) }
        outboxDao.deleteForGroup(groupId)
        extras.deleteLinksOf(groupId)
        extras.deletePendingUpiOf(groupId)
        groups.deleteGroup(groupId)
    }

    /**
     * The person deleted or left group [groupId] themselves and the server agreed: the group leaves this phone
     * ([removeGroup]) and so does the record of the import that made it, however it stood. Parts of it that still
     * waited are dropped with the group's other waiting changes, so a row left at SENDING could never finish; nothing
     * failed either, so no FAILED row stays for Import failed (140).
     */
    suspend fun removeOwnGroup(groupId: String) = transactions.run {
        removeGroup(groupId)
        extras.deleteImport(groupId)
    }

    /**
     * Sign out: every group row, waiting change, link, pending UPI payment and import goes. Split rules stay (they are
     * the person's own and come back into use when they sign in again); personal rows, mirrors included, stay, except
     * that a split never sent puts its payment back as it was (9.4), as [removeGroup] does: nobody else will ever
     * see that split, so the month must count the whole payment again.
     *
     * Rows shared in the family month stay linked ([FamilyLinker]): the same account signing in again finds them.
     */
    suspend fun clearAll() {
        transactions.run {
            restoreNeverSent(outboxDao.all())
            outboxDao.deleteAll()
            extras.deleteAllLinks()
            extras.deleteAllPendingUpi()
            extras.deleteAllImports()
            groups.deleteAllGroups()
        }
        // Their Confirm buttons and links would point at data that is gone, and they name other people.
        notifier.cancelAll()
    }

    /**
     * Forgets the link this phone made for [memberId]: the server turned it off (the friend joined, was removed, or
     * an undo-join put the place back), so "link sent" must not show and the next Remind makes a new one.
     */
    suspend fun forgetLink(memberId: String) = extras.deleteLink(memberId)

    /**
     * The group [groupId] is no longer the person's ([SyncEngine]: the server no longer lists it, or answers
     * `not_member`): removes it. The server says why ([reason]: `lost` of `/v1/me/groups`, or the `not_member` answer's
     * `reason`, docs/server.md 4.5). Only an undone join forgets the group's splits ([removeGroup] with
     * `forgetSplits`): the person took a friend's place by mistake and was never that friend. A leave, a removal, a
     * deletion, or a reason the server does not know (over 90 days ago, or an older server) keeps them: the spending
     * happened.
     */
    suspend fun removeLostGroup(groupId: String, reason: String?) {
        removeGroup(groupId, forgetSplits = reason == LostReason.JOIN_UNDONE)
    }

    // ---- Splitwise imports (docs/splitwise-import.md 5.4) ----

    /**
     * The server took one part of group [groupId]'s import with [items] items. [finished] (no other part waits) marks
     * the import DONE. Call inside the transaction that applies the part.
     */
    suspend fun importPartSent(groupId: String, items: Int, finished: Boolean) {
        extras.addSentItems(groupId, items)
        if (finished) extras.setImportState(groupId, ImportState.DONE)
    }

    /**
     * Group [groupId]'s import cannot finish because the group is gone (not a member, deleted) or its create was
     * refused: marked FAILED, when the group came from an import that was still being sent.
     */
    suspend fun markImportFailed(groupId: String) {
        if (extras.importOf(groupId)?.state == ImportState.SENDING) extras.setImportState(groupId, ImportState.FAILED)
    }

    /**
     * A part of group [groupId]'s import was refused (docs/splitwise-import.md 5.4): none of it is kept. In one
     * transaction the import is marked FAILED, the group leaves this phone with its splits forgotten (mirrors go,
     * linked payments go back to how they were), and `DELETE /v1/groups/{gid}` is queued, which the server allows while
     * nobody else is on the app (docs/server.md 4.6). A pull skips the group while that delete waits.
     */
    suspend fun failImport(groupId: String) = transactions.run {
        extras.setImportState(groupId, ImportState.FAILED)
        removeGroup(groupId, forgetSplits = true)
        outbox.append(groupId, EntityKind.GROUP, groupId, OutboxOp.DELETE, "DELETE", "/v1/groups/$groupId", body = null, restore = null)
    }

    /**
     * Puts back the personal rows of expense creates in [entries] that the server never confirmed (version 0), and
     * forgets the mirrors of imported expenses whose part never reached the server (their links put back as they were).
     */
    private suspend fun restoreNeverSent(entries: List<OutboxEntry>) {
        for (entry in entries) {
            if (entry.op != OutboxOp.CREATE) continue
            when (entry.entityKind) {
                EntityKind.EXPENSE -> {
                    val neverSent = (groups.getExpense(entry.entityId)?.version ?: 0L) == 0L
                    if (neverSent) outbox.restoreOf(entry)?.personal?.let { mirrors.restore(it) }
                }
                EntityKind.IMPORT -> for (item in ImportBodies.itemsOf(entry.bodyJson)) {
                    if (item.kind != EntityKind.EXPENSE) continue
                    val stored = groups.getExpense(item.id) ?: continue
                    if (stored.version == 0L) mirrors.forget(item.id)
                }
                else -> Unit
            }
        }
    }

    /**
     * A different account signed in: everything of [clearAll], the split rules, the old account's mirrors (family
     * mirrors included), and every link to a family expense.
     */
    suspend fun switchAccount() {
        transactions.run {
            clearAll()
            extras.deleteAllRules()
            mirrors.forgetAccount()
            family?.unlinkAll()
        }
    }
}
