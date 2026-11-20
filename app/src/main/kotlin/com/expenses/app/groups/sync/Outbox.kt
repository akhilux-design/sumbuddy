package com.expenses.app.groups.sync

import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.OutboxDao
import com.expenses.app.groups.data.OutboxEntry
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.data.OutboxStatus
import com.expenses.app.groups.data.RestoreSnapshot
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The queue of group changes made on this phone (docs/server.md 5.1). Every write lands in Room first, together with
 * an entry here, in one transaction; [SyncEngine] sends the entries later, in order, each with its own idempotency
 * key, so a retry after a lost answer is applied once.
 */
class Outbox(private val dao: OutboxDao, private val clock: Clock, private val json: Json = ApiJson) {
    /**
     * Appends a change. Call inside the transaction that writes the change to the `grp_*` tables.
     *
     * @return the new entry.
     */
    suspend fun append(
        groupId: String?,
        kind: EntityKind,
        entityId: String,
        op: OutboxOp,
        method: String,
        path: String,
        body: JsonElement?,
        restore: RestoreSnapshot?,
    ): OutboxEntry {
        val entry = OutboxEntry(
            idempotencyKey = UUID.randomUUID().toString(),
            groupId = groupId,
            entityKind = kind,
            entityId = entityId,
            op = op,
            method = method,
            path = path,
            bodyJson = body?.let { json.encodeToString(JsonElement.serializer(), it) },
            restoreJson = restore?.let { json.encodeToString(RestoreSnapshot.serializer(), it) },
            createdAt = clock.instant(),
        )
        return entry.copy(seq = dao.insert(entry))
    }

    /** What rolling back [entry] puts back, or null when it carries nothing. */
    fun restoreOf(entry: OutboxEntry): RestoreSnapshot? =
        entry.restoreJson?.let { runCatching { json.decodeFromString(RestoreSnapshot.serializer(), it) }.getOrNull() }

    /**
     * Changes the restore snapshot of every waiting entry of [entityId] (call inside a transaction). A pull that
     * skips a row because a change of it waits stores the server's copy here, so a rollback puts back the newest
     * state (docs/server.md 5.1, 5.2).
     */
    suspend fun updateRestore(entityId: String, change: (RestoreSnapshot) -> RestoreSnapshot) {
        for (entry in dao.forEntity(entityId)) {
            val next = change(restoreOf(entry) ?: RestoreSnapshot())
            dao.update(entry.copy(restoreJson = json.encodeToString(RestoreSnapshot.serializer(), next)))
        }
    }

    /** How many entries wait; emits again on every change. */
    fun observeCount(): Flow<Int> = dao.observeCount()

    /** How the entries of group [groupId] stand, without their bodies; emits again on every change. */
    fun observeStatus(groupId: String): Flow<List<OutboxStatus>> = dao.observeStatusForGroup(groupId)

    /** Every entry waiting, oldest first, bodies included: use [seqs] or [hasPending] where the bodies are not needed. */
    suspend fun entries(): List<OutboxEntry> = dao.all()

    /** The waiting entries that change [entityId], oldest first. */
    suspend fun entriesOf(entityId: String): List<OutboxEntry> = dao.forEntity(entityId)

    /** The seq of every entry waiting, oldest first. */
    suspend fun seqs(): List<Long> = dao.allSeqs()

    /** The ids of groups whose own create ([OutboxOp.CREATE]) or delete ([OutboxOp.DELETE]) waits. */
    suspend fun groupsWaiting(op: OutboxOp): Set<String> = dao.groupIdsWith(op).toSet()

    /** True while an import part of [groupId] other than [except] waits. */
    suspend fun hasOtherImportPart(groupId: String, except: Long): Boolean = dao.countImportParts(groupId, except) > 0

    /** True while an import part of [groupId] waits. */
    suspend fun hasImportPart(groupId: String): Boolean = dao.countImportParts(groupId, exceptSeq = NO_SEQ) > 0

    /** The entry with [seq], or null once it is gone. */
    suspend fun get(seq: Long): OutboxEntry? = dao.get(seq)

    /** The ids of every row with a change waiting. */
    suspend fun pendingEntityIds(): Set<String> = dao.pendingEntityIds().toSet()

    /** True while a change of [entityId] other than [except] waits. */
    suspend fun hasOtherPending(entityId: String, except: Long): Boolean = dao.forEntity(entityId).any { it.seq != except }

    /** True while a change of [entityId] waits. */
    suspend fun hasPending(entityId: String): Boolean = dao.forEntity(entityId).isNotEmpty()

    /**
     * Merges changes of one expense that were never sent (docs/server.md 5.1, step 4). Only entries with
     * `attempts = 0` merge: an attempted one may already be applied on the server even without an answer.
     *
     * - A create followed by a delete: both are dropped (Undo on "Split. Sorted." offline sends nothing).
     * - A create followed by updates: one create with the last body.
     * - Updates (and a delete after them): only the last, with the first entry's restore snapshot.
     *
     * The merged entry keeps the position and key of the last one, so it is sent after anything it may depend on.
     * Call inside a transaction. It runs at the start of every push, so it reads the expense entries only: the bodies
     * of waiting import parts (about 3 MB for a large Splitwise import) are never loaded for it.
     *
     * @return ids of expenses whose create and delete were both dropped: the server never knew them, so their local
     * tombstones can go.
     */
    suspend fun coalesce(): Set<String> {
        val dropped = HashSet<String>()
        val expenseEntries = dao.ofKind(EntityKind.EXPENSE).groupBy { it.entityId }
        for ((expenseId, entries) in expenseEntries) {
            val lastAttempted = entries.indexOfLast { it.attempts > 0 }
            val run = entries.drop(lastAttempted + 1)
            if (run.size < 2) continue
            val first = run.first()
            val last = run.last()
            if (first.op == OutboxOp.CREATE && last.op == OutboxOp.DELETE) {
                run.forEach { dao.delete(it.seq) }
                dropped += expenseId
                continue
            }
            run.dropLast(1).forEach { dao.delete(it.seq) }
            val op = if (first.op == OutboxOp.CREATE) OutboxOp.CREATE else last.op
            dao.update(last.copy(op = op, restoreJson = first.restoreJson))
        }
        return dropped
    }

    /** Marks [entry] as about to be sent. Returns the stored copy. */
    suspend fun markAttempt(entry: OutboxEntry): OutboxEntry {
        val attempted = entry.copy(attempts = entry.attempts + 1)
        dao.update(attempted)
        return attempted
    }

    /** Records a server error (5xx) of [entry]: one more failure in a row. */
    suspend fun markFailure(entry: OutboxEntry, code: String): OutboxEntry {
        val failed = entry.copy(failures = entry.failures + 1, lastErrorCode = code)
        dao.update(failed)
        return failed
    }

    /** Records a failure that is not the server's fault (offline), without counting it. */
    suspend fun markOffline(entry: OutboxEntry): OutboxEntry {
        val kept = entry.copy(lastErrorCode = OFFLINE)
        dao.update(kept)
        return kept
    }

    /** Removes [entry] (sent, or dropped). */
    suspend fun remove(entry: OutboxEntry) = dao.delete(entry.seq)

    /** Constants. */
    companion object {
        /** After this many server errors in a row, an entry blocks only its own group. */
        const val STUCK_AFTER_FAILURES: Int = 5

        /** The [OutboxEntry.lastErrorCode] of an entry that got no answer (offline); not a failure. */
        const val OFFLINE: String = "offline"

        /** A seq no entry has (seqs start at 1). */
        private const val NO_SEQ = 0L
    }
}
