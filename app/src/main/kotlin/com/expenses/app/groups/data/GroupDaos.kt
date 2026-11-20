package com.expenses.app.groups.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * A group expense with its shares.
 *
 * @param expense the expense.
 * @param shares one row per person with a share.
 */
data class GroupExpenseWithShares(
    @Embedded val expense: GroupExpenseEntity,
    @Relation(parentColumn = "id", entityColumn = "expenseId") val shares: List<ExpenseShareEntity>,
)

/**
 * A member with the place of its row among all member rows.
 *
 * @param member the member.
 * @param storedOrder the row's SQLite rowid: rows written to this phone earlier have a smaller one. A row keeps it
 * for as long as it exists (members are written with `@Upsert`, which updates a row in place), so within a group it
 * is the order the group's people were first stored in: the order they were typed in on this phone, or the order the
 * server gave them in. The avatar colours are given out in this order ([avatarColorsOf]).
 */
data class StoredMember(
    @Embedded val member: MemberEntity,
    val storedOrder: Long,
)

// Parent rows are written with @Upsert or @Update, never with REPLACE: REPLACE deletes the old row first, and the
// foreign keys would then delete its members, expenses and shares with it.

/** Database access for the group tables. Use [GroupsRepository] instead; the sync engine uses it directly. */
@Dao
interface GroupDao {
    /** Inserts or updates a group. */
    @Upsert
    suspend fun upsertGroup(group: GroupEntity)

    /** The group with this id, or null. */
    @Query("SELECT * FROM grp_groups WHERE id = :id")
    suspend fun getGroup(id: String): GroupEntity?

    /** Every group on this phone. */
    @Query("SELECT * FROM grp_groups ORDER BY createdAt, id")
    suspend fun allGroups(): List<GroupEntity>

    /** Every group on this phone; emits again on every change. */
    @Query("SELECT * FROM grp_groups ORDER BY createdAt, id")
    fun observeGroups(): Flow<List<GroupEntity>>

    /** The group with this id; null once it is gone. */
    @Query("SELECT * FROM grp_groups WHERE id = :id")
    fun observeGroup(id: String): Flow<GroupEntity?>

    /** Deletes a group with its members, expenses, shares and settlements (foreign keys cascade). */
    @Query("DELETE FROM grp_groups WHERE id = :id")
    suspend fun deleteGroup(id: String)

    /** Deletes every group. */
    @Query("DELETE FROM grp_groups")
    suspend fun deleteAllGroups()

    /** Stores the version the phone has applied and the newest one the server reported. */
    @Query("UPDATE grp_groups SET version = :version, latestVersion = MAX(latestVersion, :latestVersion), loadFailed = 0 WHERE id = :id")
    suspend fun setVersion(id: String, version: Long, latestVersion: Long)

    /** Stores the newest version the server reported for a group. */
    @Query("UPDATE grp_groups SET latestVersion = :latestVersion WHERE id = :id")
    suspend fun setLatestVersion(id: String, latestVersion: Long)

    /** Marks whether the last pull of a group failed. */
    @Query("UPDATE grp_groups SET loadFailed = :failed WHERE id = :id")
    suspend fun setLoadFailed(id: String, failed: Boolean)

    /** Sets a group's sync state. */
    @Query("UPDATE grp_groups SET syncState = :state WHERE id = :id")
    suspend fun setGroupState(id: String, state: SyncState)

    /** Inserts or updates members. */
    @Upsert
    suspend fun upsertMembers(members: List<MemberEntity>)

    /** The member with this id, or null. */
    @Query("SELECT * FROM grp_members WHERE id = :id")
    suspend fun getMember(id: String): MemberEntity?

    /** Every member of a group, removed ones included. */
    @Query("SELECT * FROM grp_members WHERE groupId = :groupId ORDER BY name, id")
    suspend fun membersOf(groupId: String): List<MemberEntity>

    /** Every member of every group, by name, each with its stored order; emits again on every change. */
    @Query("SELECT *, rowid AS storedOrder FROM grp_members ORDER BY name, id")
    fun observeMembers(): Flow<List<StoredMember>>

    /** Deletes a member row. */
    @Query("DELETE FROM grp_members WHERE id = :id")
    suspend fun deleteMember(id: String)

    /** Inserts or updates an expense row (its shares are separate). */
    @Upsert
    suspend fun upsertExpense(expense: GroupExpenseEntity)

    /** The expense with this id, or null. */
    @Query("SELECT * FROM grp_expenses WHERE id = :id")
    suspend fun getExpense(id: String): GroupExpenseEntity?

    /** The expense with its shares, or null. */
    @Transaction
    @Query("SELECT * FROM grp_expenses WHERE id = :id")
    suspend fun getExpenseWithShares(id: String): GroupExpenseWithShares?

    /** Deletes an expense row and its shares. */
    @Query("DELETE FROM grp_expenses WHERE id = :id")
    suspend fun deleteExpense(id: String)

    /** Every expense of a group that is not deleted, with shares, newest first. */
    @Transaction
    @Query("SELECT * FROM grp_expenses WHERE groupId = :groupId AND deleted = 0 ORDER BY occurredOn DESC, updatedAt DESC")
    suspend fun activeExpensesOf(groupId: String): List<GroupExpenseWithShares>

    /**
     * Every expense that is not deleted, in every group and direct split, with shares; emits again on every change.
     * Family expenses are left out (docs/family.md 6.2): they never take part in balances, and a long family history
     * would be loaded on every change of the groups.
     */
    @Transaction
    @Query(
        "SELECT * FROM grp_expenses WHERE deleted = 0 AND groupId NOT IN (SELECT id FROM grp_groups WHERE kind = 'FAMILY') " +
            "ORDER BY occurredOn DESC, updatedAt DESC",
    )
    fun observeActiveExpenses(): Flow<List<GroupExpenseWithShares>>

    // ---- Family (version 6, docs/family.md 6.2) ----

    /** The family on this phone (the oldest, should there be two for a moment), or null. */
    @Query("SELECT * FROM grp_groups WHERE kind = 'FAMILY' ORDER BY createdAt, id LIMIT 1")
    suspend fun familyGroup(): GroupEntity?

    /** The family on this phone, or null; emits again on every change. */
    @Query("SELECT * FROM grp_groups WHERE kind = 'FAMILY' ORDER BY createdAt, id LIMIT 1")
    fun observeFamilyGroup(): Flow<GroupEntity?>

    /**
     * The expenses of group [groupId] that are not deleted, dated from [from] (included) to [to] (excluded), newest
     * first; emits again on every change. Uses the index on (groupId, occurredOn).
     */
    @Query(
        "SELECT * FROM grp_expenses WHERE groupId = :groupId AND deleted = 0 AND occurredOn >= :from AND occurredOn < :to " +
            "ORDER BY occurredOn DESC, updatedAt DESC, id",
    )
    fun observeFamilyExpenses(groupId: String, from: LocalDate, to: LocalDate): Flow<List<GroupExpenseEntity>>

    /** Every member of a group, removed ones included, each with its stored order; emits again on every change. */
    @Query("SELECT *, rowid AS storedOrder FROM grp_members WHERE groupId = :groupId ORDER BY rowid")
    fun observeMembersOf(groupId: String): Flow<List<StoredMember>>

    /** The ids of every expense of group [groupId], deleted ones included. */
    @Query("SELECT id FROM grp_expenses WHERE groupId = :groupId")
    suspend fun expenseIdsOf(groupId: String): List<String>

    /**
     * Marks the expenses that [memberId] paid in group [groupId] deleted on this phone: a family member who was
     * removed, so the family month drops them at once. The pull that follows brings the server's tombstones, their
     * versions, and drops their shares.
     */
    @Query("UPDATE grp_expenses SET deleted = 1 WHERE groupId = :groupId AND paidByMemberId = :memberId AND deleted = 0")
    suspend fun markDeletedPaidBy(groupId: String, memberId: String)

    /** The shares of one expense. */
    @Query("SELECT * FROM grp_expense_shares WHERE expenseId = :expenseId ORDER BY memberId")
    suspend fun sharesOf(expenseId: String): List<ExpenseShareEntity>

    /** Inserts shares. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShares(shares: List<ExpenseShareEntity>)

    /** Deletes the shares of one expense. */
    @Query("DELETE FROM grp_expense_shares WHERE expenseId = :expenseId")
    suspend fun deleteShares(expenseId: String)

    /** Inserts or updates a settlement. */
    @Upsert
    suspend fun upsertSettlement(settlement: SettlementEntity)

    /** The settlement with this id, or null. */
    @Query("SELECT * FROM grp_settlements WHERE id = :id")
    suspend fun getSettlement(id: String): SettlementEntity?

    /** Deletes a settlement row. */
    @Query("DELETE FROM grp_settlements WHERE id = :id")
    suspend fun deleteSettlement(id: String)

    /** Every settlement of a group that is not deleted. */
    @Query("SELECT * FROM grp_settlements WHERE groupId = :groupId AND deleted = 0 ORDER BY occurredOn DESC, id")
    suspend fun activeSettlementsOf(groupId: String): List<SettlementEntity>

    /** Deletes a group's expenses that have no change waiting (the server's copy is pulled again from 0). */
    @Query("DELETE FROM grp_expenses WHERE groupId = :groupId AND syncState != 'PENDING'")
    suspend fun deleteSyncedExpensesOf(groupId: String)

    /** Deletes a group's settlements that have no change waiting. */
    @Query("DELETE FROM grp_settlements WHERE groupId = :groupId AND syncState != 'PENDING'")
    suspend fun deleteSyncedSettlementsOf(groupId: String)

    /** Every settlement that is not deleted, in every group; emits again on every change. */
    @Query("SELECT * FROM grp_settlements WHERE deleted = 0 ORDER BY occurredOn DESC, id")
    fun observeActiveSettlements(): Flow<List<SettlementEntity>>

    /** Keeps a refused change for the Group page's "Not saved" row (version 3). */
    @Insert
    suspend fun insertRejected(change: RejectedChangeEntity): Long

    /** The refused changes of a group, newest first; emits again on every change. */
    @Query("SELECT * FROM grp_rejected WHERE groupId = :groupId ORDER BY rejectedAt DESC, id DESC")
    fun observeRejected(groupId: String): Flow<List<RejectedChangeEntity>>

    /** The refused change with this id, or null. */
    @Query("SELECT * FROM grp_rejected WHERE id = :id")
    suspend fun getRejected(id: Long): RejectedChangeEntity?

    /** Forgets a refused change (Dismiss, or after Try again). */
    @Query("DELETE FROM grp_rejected WHERE id = :id")
    suspend fun deleteRejected(id: Long)

    /** How the payment linked to [groupExpenseId] was before the split linked it, or null (version 4). */
    @Query("SELECT * FROM grp_link_priors WHERE groupExpenseId = :groupExpenseId")
    suspend fun getLinkPrior(groupExpenseId: String): LinkPriorEntity?

    /** Remembers how a payment was before a split linked it. */
    @Upsert
    suspend fun upsertLinkPrior(prior: LinkPriorEntity)

    /**
     * True while the create of group [groupId] waits in the outbox, so the server does not know the group yet; emits
     * again on every change of the outbox.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM grp_outbox WHERE entityId = :groupId AND entityKind = 'GROUP' AND op = 'CREATE')")
    fun observeCreateWaiting(groupId: String): Flow<Boolean>
}

/** Database access for the outbox. Used by [com.expenses.app.groups.sync.Outbox] only. */
@Dao
interface OutboxDao {
    /** Appends an entry and returns its seq. */
    @Insert
    suspend fun insert(entry: OutboxEntry): Long

    /** Replaces an entry (same seq). */
    @Update
    suspend fun update(entry: OutboxEntry)

    /** Every entry, oldest first. */
    @Query("SELECT * FROM grp_outbox ORDER BY seq")
    suspend fun all(): List<OutboxEntry>

    /**
     * The entries that change rows of one [kind], oldest first. The outbox merges unsent expense changes with it on
     * every push without loading the bodies of waiting import parts (up to 30 KB each, 100 parts an import).
     */
    @Query("SELECT * FROM grp_outbox WHERE entityKind = :kind ORDER BY seq")
    suspend fun ofKind(kind: EntityKind): List<OutboxEntry>

    /** The seq of every entry, oldest first (without loading bodies). */
    @Query("SELECT seq FROM grp_outbox ORDER BY seq")
    suspend fun allSeqs(): List<Long>

    /** The ids of groups with a waiting group entry doing [op] (a create, or the delete of a refused import). */
    @Query("SELECT entityId FROM grp_outbox WHERE entityKind = 'GROUP' AND op = :op")
    suspend fun groupIdsWith(op: OutboxOp): List<String>

    /** How many import parts of group [groupId] wait, the entry [exceptSeq] left out. */
    @Query("SELECT COUNT(*) FROM grp_outbox WHERE groupId = :groupId AND entityKind = 'IMPORT' AND seq != :exceptSeq")
    suspend fun countImportParts(groupId: String, exceptSeq: Long): Int

    /** The entry with this seq, or null. */
    @Query("SELECT * FROM grp_outbox WHERE seq = :seq")
    suspend fun get(seq: Long): OutboxEntry?

    /** The entries that change one row, oldest first. */
    @Query("SELECT * FROM grp_outbox WHERE entityId = :entityId ORDER BY seq")
    suspend fun forEntity(entityId: String): List<OutboxEntry>

    /** The entries of one group, oldest first. */
    @Query("SELECT * FROM grp_outbox WHERE groupId = :groupId ORDER BY seq")
    suspend fun forGroup(groupId: String): List<OutboxEntry>

    /** The ids of every row that has a change waiting. */
    @Query("SELECT DISTINCT entityId FROM grp_outbox")
    suspend fun pendingEntityIds(): List<String>

    /** Deletes one entry. */
    @Query("DELETE FROM grp_outbox WHERE seq = :seq")
    suspend fun delete(seq: Long)

    /** Deletes every entry of a group. */
    @Query("DELETE FROM grp_outbox WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    /** Deletes every entry. */
    @Query("DELETE FROM grp_outbox")
    suspend fun deleteAll()

    /** How many entries wait; emits again on every change. */
    @Query("SELECT COUNT(*) FROM grp_outbox")
    fun observeCount(): Flow<Int>

    /**
     * How the entries of one group stand, oldest first, without their bodies (Importing's progress and Problem
     * banner, docs/splitwise-import.md 5.3); emits again on every change of the outbox.
     */
    @Query("SELECT seq, entityKind, op, attempts, failures, lastErrorCode FROM grp_outbox WHERE groupId = :groupId ORDER BY seq")
    fun observeStatusForGroup(groupId: String): Flow<List<OutboxStatus>>
}

/**
 * How one outbox entry stands, without its body ([OutboxDao.observeStatusForGroup]).
 *
 * @param seq the entry.
 * @param entityKind what it changes.
 * @param op what it does.
 * @param attempts how many times it was sent.
 * @param failures server errors in a row.
 * @param lastErrorCode the last error `code` (`offline` when no answer came), or null.
 */
data class OutboxStatus(
    val seq: Long,
    val entityKind: EntityKind,
    val op: OutboxOp,
    val attempts: Int,
    val failures: Int,
    val lastErrorCode: String?,
)

/** Database access for links, split rules, pending UPI payments and Splitwise imports. Use [GroupsRepository] instead. */
@Dao
interface GroupExtrasDao {
    /** The link this phone made for a member, or null. */
    @Query("SELECT * FROM grp_links WHERE memberId = :memberId")
    suspend fun linkFor(memberId: String): LinkEntity?

    /** Stores a link (one per member). */
    @Upsert
    suspend fun upsertLink(link: LinkEntity)

    /** Forgets the link of a member. */
    @Query("DELETE FROM grp_links WHERE memberId = :memberId")
    suspend fun deleteLink(memberId: String)

    /** Forgets the links of a group. */
    @Query("DELETE FROM grp_links WHERE groupId = :groupId")
    suspend fun deleteLinksOf(groupId: String)

    /** The links this phone made in a group (Group settings "link sent", the friend's link sheet); emits on change. */
    @Query("SELECT * FROM grp_links WHERE groupId = :groupId")
    fun observeLinksOf(groupId: String): Flow<List<LinkEntity>>

    /** Forgets every link. */
    @Query("DELETE FROM grp_links")
    suspend fun deleteAllLinks()

    /** The split rule of a merchant, or null; emits again on every change. */
    @Query("SELECT * FROM grp_split_rules WHERE merchantKey = :merchantKey")
    fun observeRule(merchantKey: String): Flow<SplitRuleEntity?>

    /** Stores a split rule. */
    @Upsert
    suspend fun upsertRule(rule: SplitRuleEntity)

    /** Sets whether a merchant's split rule splits payments by itself; nothing when there is no rule. */
    @Query("UPDATE grp_split_rules SET auto = :auto WHERE merchantKey = :merchantKey")
    suspend fun setRuleAuto(merchantKey: String, auto: Boolean)

    /** Forgets the split rule of one merchant. */
    @Query("DELETE FROM grp_split_rules WHERE merchantKey = :merchantKey")
    suspend fun deleteRule(merchantKey: String)

    /** Forgets every split rule. */
    @Query("DELETE FROM grp_split_rules")
    suspend fun deleteAllRules()

    /** Forgets the split rules of a group. */
    @Query("DELETE FROM grp_split_rules WHERE groupId = :groupId")
    suspend fun deleteRulesOf(groupId: String)

    /** Forgets every pending UPI payment. */
    @Query("DELETE FROM grp_pending_upi")
    suspend fun deleteAllPendingUpi()

    /** Forgets the pending UPI payments of a group. */
    @Query("DELETE FROM grp_pending_upi WHERE groupId = :groupId")
    suspend fun deletePendingUpiOf(groupId: String)

    /** Stores a pending UPI payment. */
    @Upsert
    suspend fun upsertPendingUpi(row: PendingUpiEntity)

    /** Every pending UPI payment, oldest first (docs/server.md 9.5 matching). */
    @Query("SELECT * FROM grp_pending_upi ORDER BY startedAt, id")
    suspend fun allPendingUpi(): List<PendingUpiEntity>

    /** Forgets one pending UPI payment (matched, or too old). */
    @Query("DELETE FROM grp_pending_upi WHERE id = :id")
    suspend fun deletePendingUpi(id: String)

    // ---- Splitwise imports (version 5, docs/splitwise-import.md 5.1) ----

    /** Stores an import (one per group). */
    @Upsert
    suspend fun upsertImport(row: ImportEntity)

    /** The import that made group [groupId], or null. */
    @Query("SELECT * FROM grp_imports WHERE groupId = :groupId")
    suspend fun importOf(groupId: String): ImportEntity?

    /** The import that made group [groupId], or null; emits again on every change. */
    @Query("SELECT * FROM grp_imports WHERE groupId = :groupId")
    fun observeImport(groupId: String): Flow<ImportEntity?>

    /**
     * The newest import of the file with [hash] that did not fail and whose group is still on this phone, or null
     * ("This file already came across", 137).
     */
    @Query(
        "SELECT i.* FROM grp_imports i JOIN grp_groups g ON g.id = i.groupId " +
            "WHERE i.fileHash = :hash AND i.state != 'FAILED' ORDER BY i.createdAt DESC LIMIT 1",
    )
    suspend fun latestImportByHash(hash: String): ImportEntity?

    /** The groups made by an import that did not fail; emits again on every change (their data is all here). */
    @Query("SELECT groupId FROM grp_imports WHERE state != 'FAILED'")
    fun observeImportedGroupIds(): Flow<List<String>>

    /** Counts [n] more items of group [groupId]'s import as sent (never more than its item count). */
    @Query("UPDATE grp_imports SET sentItems = MIN(itemCount, sentItems + :n) WHERE groupId = :groupId")
    suspend fun addSentItems(groupId: String, n: Int)

    /** Sets how group [groupId]'s import stands; nothing when it has none. */
    @Query("UPDATE grp_imports SET state = :state WHERE groupId = :groupId")
    suspend fun setImportState(groupId: String, state: ImportState)

    /** Forgets the import that made group [groupId], however it stands; nothing when it has none. */
    @Query("DELETE FROM grp_imports WHERE groupId = :groupId")
    suspend fun deleteImport(groupId: String)

    /** Forgets every import (sign out, another account). */
    @Query("DELETE FROM grp_imports")
    suspend fun deleteAllImports()
}
