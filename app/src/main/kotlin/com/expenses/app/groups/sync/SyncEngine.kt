package com.expenses.app.groups.sync

import com.expenses.app.data.repository.TransactionRunner
import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.ChangeDto
import com.expenses.app.groups.api.ChangesPage
import com.expenses.app.groups.api.CreateGroupResponse
import com.expenses.app.groups.api.ExpenseDto
import com.expenses.app.groups.api.GroupDto
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.api.ImportResponse
import com.expenses.app.groups.api.MemberDto
import com.expenses.app.groups.api.SettlementDto
import com.expenses.app.groups.api.VersionResponse
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.FamilyLinker
import com.expenses.app.groups.data.GroupBalance
import com.expenses.app.groups.data.GroupDao
import com.expenses.app.groups.data.GroupEntity
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.MirrorWriter
import com.expenses.app.groups.data.OutboxEntry
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.data.RejectedChangeEntity
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.data.groupKindOf
import com.expenses.app.groups.data.placeNameAfter
import com.expenses.app.groups.data.shareEntities
import com.expenses.app.groups.data.toEntity
import com.expenses.app.groups.splitwise.ImportBodies
import com.expenses.app.ui.format.shortName
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** How a sync run ended. */
enum class SyncOutcome {
    /**
     * Everything was sent and pulled, except entries parked as stuck (5 server errors in a row, docs/server.md 5.1):
     * those wait for the next sync, whatever starts it, and do not make WorkManager back off.
     */
    DONE,

    /** Something must be tried again soon (offline, a server error, a failed pull): WorkManager backs off. */
    RETRY,

    /** The session is gone; nothing more can be done until the person signs in. Nothing was lost. */
    SIGNED_OUT,
}

/**
 * Sends the outbox and pulls group changes (docs/server.md 5.1 and 5.2). One run at a time. Runs from
 * [SyncWorker]; the live room asks for a pull of one group; sign-out flushes the outbox.
 *
 * @param visibleGroup the group whose page is open now: no notifications for it.
 * @param sleep waits for a 429's `Retry-After`; tests pass a fake.
 * @param family the family month's sharing (docs/family.md 7.4): reconciled before each push and after each pull, and
 * told about its refused changes; null for none.
 */
class SyncEngine(
    private val api: GroupsApi,
    private val transactions: TransactionRunner,
    private val groups: GroupDao,
    private val outbox: Outbox,
    private val local: LocalGroupData,
    private val mirrors: MirrorWriter,
    private val notifier: GroupNotifier,
    private val clock: Clock,
    private val visibleGroup: () -> String? = { null },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val json: Json = ApiJson,
    private val family: FamilyLinker? = null,
) {
    private val runLock = Mutex()

    /**
     * Sends the outbox, then pulls every group whose version moved. It runs each time the app comes to the front, so
     * it also removes the notifications' bundle summary when a tap on the last notification under it opened the app.
     * The tidy may follow this sync's own notifications at once: [GroupNotifications] counts what it has just posted
     * even while the system does not list it yet.
     */
    suspend fun sync(): SyncOutcome = runLock.withLock { syncLocked() }.also { notifier.tidy() }

    /** Sends the outbox only. */
    suspend fun push(): SyncOutcome = runLock.withLock { pushLocked() }

    /** Pulls one group whatever its version (its live room's socket opened). */
    suspend fun pullGroup(groupId: String): SyncOutcome = runLock.withLock { pullGroupLocked(groupId) }

    /**
     * A `changed` push (docs/server.md 4.14) or live-room message (5.3) said group [groupId] is at [version]: pull that
     * group only, and only when the phone is behind. The stored version is read inside the run, after any sync in
     * progress has ended, so many messages that arrived during one long push cost one pull. A group this phone does
     * not know yet gets a full sync.
     */
    suspend fun pullChanged(groupId: String, version: Long): SyncOutcome = runLock.withLock {
        val stored = groups.getGroup(groupId)
        when {
            stored == null -> syncLocked()
            version <= stored.version -> SyncOutcome.DONE
            else -> pullGroupLocked(groupId)
        }
    }

    /**
     * Sends the outbox for up to [timeout] (sign-out). True when nothing is left waiting.
     */
    suspend fun flush(timeout: Duration = Duration.ofSeconds(10)): Boolean {
        withTimeoutOrNull(timeout.toMillis()) { push() }
        return outbox.seqs().isEmpty()
    }

    /**
     * The reminder of a `remind` push (docs/server.md 4.11): what the person owes [memberId] in [groupId], from the
     * phone's own data, or null when nothing is owed or the group is unknown.
     */
    suspend fun reminderEvent(groupId: String, memberId: String): GroupEvent.Reminded? {
        val group = groups.getGroup(groupId) ?: return null
        val member = groups.getMember(memberId) ?: return null
        val balance = GroupBalance.of(groups.activeExpensesOf(groupId), groups.activeSettlementsOf(groupId))
        val owed = -balance.pairBalance(group.myMemberId, memberId)
        if (owed <= 0) return null
        return GroupEvent.Reminded(groupId, shortName(member.name, memberNames(groupId)), owed, displayName(group))
    }

    private suspend fun syncLocked(): SyncOutcome {
        // Family rows whose eligibility changed without a write of this phone (a split deleted elsewhere, a day
        // passing for a dated row) are shared or taken out before the push (docs/family.md 7.3).
        family?.let { linker -> transactions.run { linker.reconcile() } }
        val pushed = pushLocked()
        if (pushed == SyncOutcome.SIGNED_OUT) return pushed
        val pulled = pullLocked()
        if (pulled != SyncOutcome.SIGNED_OUT) {
            // A family just joined, or pulled again after a sign-in: earlier Family rows are shared now.
            family?.let { linker -> transactions.run { linker.reconcileAndRequestSync() } }
        }
        return if (pulled == SyncOutcome.DONE) pushed else pulled
    }

    // ---- Push (5.1) ----

    private suspend fun pushLocked(): SyncOutcome {
        transactions.run {
            // A create and a delete of the same expense that never left the phone: the server never knew it.
            outbox.coalesce().forEach { groups.deleteExpense(it) }
        }
        val blocked = HashSet<String>()
        val tried = HashSet<Long>()
        // A refused import part queues its group's delete while this run goes on (docs/splitwise-import.md 5.4): a
        // second look sends entries added meanwhile, so the delete does not wait for the next sync.
        repeat(MAX_PUSH_PASSES) {
            val waiting = outbox.seqs().filter { it !in tried }
            if (waiting.isEmpty()) return SyncOutcome.DONE
            for (seq in waiting) {
                tried += seq
                val entry = outbox.get(seq) ?: continue // removed by an earlier rollback of its group
                val groupId = entry.groupId
                if (groupId != null && groupId in blocked) continue
                val attempted = outbox.markAttempt(entry)
                val result = sendWithRateLimit(attempted) ?: return SyncOutcome.RETRY
                when (result) {
                    is ApiResult.Ok -> transactions.run {
                        applyAccepted(attempted, result.value)
                        outbox.remove(attempted)
                    }
                    is ApiResult.Error -> {
                        val error = result.error
                        when {
                            error.isOffline -> {
                                outbox.markOffline(attempted)
                                return SyncOutcome.RETRY
                            }
                            error.needsSignIn -> return SyncOutcome.SIGNED_OUT
                            error.isTransient || error.isRateLimited -> {
                                val failed = outbox.markFailure(attempted, error.code)
                                if (failed.failures < Outbox.STUCK_AFTER_FAILURES) return SyncOutcome.RETRY
                                // Stuck: it holds back only the later changes of its own group, and it is tried again
                                // on the next sync, whatever starts it. It does not make WorkManager back off: that
                                // would hold every other group's changes too (requests wait while one sync is queued).
                                if (groupId != null) blocked += groupId
                            }
                            else -> reject(attempted, error)
                        }
                    }
                }
            }
        }
        return SyncOutcome.DONE
    }

    /** Sends [entry], waiting out our own 429s (up to a minute each). Null when the wait would be longer. */
    private suspend fun sendWithRateLimit(entry: OutboxEntry): ApiResult<JsonElement>? {
        var result = api.send(entry.method, entry.path, entry.bodyJson, entry.idempotencyKey)
        var waits = 0
        while (result is ApiResult.Error && result.error.isRateLimited && waits < MAX_RATE_LIMIT_WAITS) {
            val seconds = result.error.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS
            if (seconds > MAX_RETRY_AFTER_SECONDS) return null
            sleep(seconds * MILLIS_PER_SECOND)
            result = api.send(entry.method, entry.path, entry.bodyJson, entry.idempotencyKey)
            waits++
        }
        return result
    }

    /**
     * A refused change (4.1): drop it and put things back as they were, or apply what the server says is gone. A
     * refused import part (any code but `not_member` and `group_deleted`) takes the whole import back
     * ([LocalGroupData.failImport]); a group that is gone fails the import it came from.
     */
    private suspend fun reject(entry: OutboxEntry, error: ApiError) {
        val groupId = entry.groupId
        when {
            groupId != null && error.code == NOT_MEMBER -> {
                local.removeLostGroup(groupId, error.reason)
                local.markImportFailed(groupId)
            }
            groupId != null && error.code == GROUP_DELETED -> {
                local.removeGroup(groupId)
                local.markImportFailed(groupId)
            }
            groupId != null && entry.entityKind == EntityKind.IMPORT -> local.failImport(groupId)
            error.code == "expense_deleted" -> transactions.run {
                outbox.remove(entry)
                applyExpenseTombstone(entry.entityId, version = null)
            }
            family != null && entry.entityKind == EntityKind.EXPENSE && isFamily(groupId) -> transactions.run {
                // A refused family expense change leaves the payment as Just me; no "Not saved" row (7.3).
                outbox.remove(entry)
                family.onRejected(entry, error)
            }
            else -> transactions.run {
                rollback(entry)
                outbox.remove(entry)
                keepRejected(entry, error)
            }
        }
    }

    /**
     * Keeps what the server refused for the Group page's "Not saved" row (02-everyday/43), after the rollback. A
     * refused group create removed the group, so it keeps nothing.
     */
    private suspend fun keepRejected(entry: OutboxEntry, error: ApiError) {
        val groupId = entry.groupId ?: return
        if (entry.entityKind == EntityKind.GROUP && entry.op == OutboxOp.CREATE) return
        // A family has no Group page, so nothing would show the row (docs/family.md 7.4).
        val group = groups.getGroup(groupId) ?: return
        if (group.kind == GroupKind.FAMILY) return
        groups.insertRejected(
            RejectedChangeEntity(
                groupId = groupId,
                entityKind = entry.entityKind,
                entityId = entry.entityId,
                op = entry.op,
                method = entry.method,
                path = entry.path,
                bodyJson = entry.bodyJson,
                restoreJson = entry.restoreJson,
                errorCode = error.code,
                rejectedAt = clock.instant(),
            ),
        )
    }

    private suspend fun applyAccepted(entry: OutboxEntry, body: JsonElement) {
        // Another change of the same row still waits: the phone's copy stays as it is (the outbox wins).
        val newer = outbox.hasOtherPending(entry.entityId, except = entry.seq)
        when (entry.entityKind) {
            EntityKind.GROUP -> acceptGroup(entry, body, newer)
            EntityKind.MEMBER -> if (!newer) {
                decode(MemberDto.serializer(), body)?.let { groups.upsertMembers(listOf(it.toEntity(placeNameAfter(groups.getMember(it.id), it)))) }
            }
            EntityKind.EXPENSE -> when (entry.op) {
                OutboxOp.DELETE -> applyExpenseTombstone(entry.entityId, decode(VersionResponse.serializer(), body)?.version)
                else -> if (!newer) decode(ExpenseDto.serializer(), body)?.let { storeExpense(it, SyncState.SYNCED) }
            }
            EntityKind.SETTLEMENT -> when (entry.op) {
                OutboxOp.DELETE -> applySettlementTombstone(entry.entityId, decode(VersionResponse.serializer(), body)?.version)
                else -> if (!newer) decode(SettlementDto.serializer(), body)?.toEntity(SyncState.SYNCED)?.let { groups.upsertSettlement(it) }
            }
            EntityKind.IMPORT -> acceptImport(entry, body)
        }
    }

    /**
     * The server took an import part (docs/server.md 4.17): each of its items is marked sent, at version
     * `firstVersion + i` when the part was applied now (an `applied: false` answer wrote nothing, so the versions stay
     * as they are until the next pull). An item with another change waiting is left alone (the outbox wins). The
     * import counts the items, and is DONE once no other part of its group waits.
     */
    private suspend fun acceptImport(entry: OutboxEntry, body: JsonElement) {
        val groupId = entry.groupId ?: return
        val answer = decode(ImportResponse.serializer(), body)
        val first = answer?.firstVersion?.takeIf { answer.applied }
        val items = ImportBodies.itemsOf(entry.bodyJson)
        items.forEachIndexed { index, item ->
            if (outbox.hasPending(item.id)) return@forEachIndexed
            val version = first?.plus(index)
            when (item.kind) {
                EntityKind.EXPENSE -> groups.getExpense(item.id)?.let {
                    groups.upsertExpense(it.copy(version = version ?: it.version, syncState = SyncState.SYNCED))
                }
                EntityKind.SETTLEMENT -> groups.getSettlement(item.id)?.let {
                    groups.upsertSettlement(it.copy(version = version ?: it.version, syncState = SyncState.SYNCED))
                }
                else -> Unit
            }
        }
        local.importPartSent(groupId, items.size, finished = !outbox.hasOtherImportPart(groupId, except = entry.seq))
    }

    private suspend fun acceptGroup(entry: OutboxEntry, body: JsonElement, newer: Boolean) {
        val stored = groups.getGroup(entry.entityId) ?: return
        val group: GroupDto = if (entry.op == OutboxOp.CREATE) {
            val created = decode(CreateGroupResponse.serializer(), body) ?: return
            groups.upsertMembers(created.members.map { it.toEntity() })
            created.group
        } else {
            decode(GroupDto.serializer(), body) ?: return
        }
        groups.upsertGroup(
            stored.copy(
                name = if (newer) stored.name else group.name,
                ownerUserId = group.ownerUserId ?: stored.ownerUserId,
                ownerMemberId = group.ownerMemberId ?: stored.ownerMemberId,
                latestVersion = maxOf(stored.latestVersion, group.version),
                syncState = if (newer) SyncState.PENDING else SyncState.SYNCED,
            ),
        )
    }

    /**
     * Puts back what [entry] changed (docs/server.md 4.1 "roll back", 5.1 `restoreJson`). The snapshot holds the row
     * as last synced, or the server's newer copy when a pull skipped it while the entry waited ([apply]): the pull
     * has moved the group's version past that copy, so this is the only way it reaches the phone. Null (a create the
     * server never had) removes the row.
     */
    private suspend fun rollback(entry: OutboxEntry) {
        val snapshot = outbox.restoreOf(entry)
        val id = entry.entityId
        when (entry.entityKind) {
            EntityKind.GROUP -> if (entry.op == OutboxOp.CREATE) {
                entry.groupId?.let {
                    local.removeGroup(it)
                    // An imported group whose create was refused: the import did not come across.
                    local.markImportFailed(it)
                }
            } else {
                val before = snapshot?.group
                val stored = groups.getGroup(id)
                if (before != null && stored != null) {
                    groups.upsertGroup(
                        stored.copy(
                            name = before.name,
                            ownerUserId = before.ownerUserId ?: stored.ownerUserId,
                            ownerMemberId = before.ownerMemberId ?: stored.ownerMemberId,
                            syncState = SyncState.REJECTED,
                        ),
                    )
                }
            }
            EntityKind.MEMBER -> {
                val before = snapshot?.member
                if (before == null) groups.deleteMember(id) else groups.upsertMembers(listOf(before.toEntity()))
            }
            EntityKind.EXPENSE -> {
                // The personal row first, then the expense: mirrors.sync then makes the row match the expense that
                // stays (a newer copy, or a tombstone that unlinks it).
                snapshot?.personal?.let { mirrors.restore(it) }
                val before = snapshot?.expense
                if (before == null) groups.deleteExpense(id) else storeExpense(before, SyncState.REJECTED)
                mirrors.sync(id)
            }
            EntityKind.SETTLEMENT -> {
                val before = snapshot?.settlement
                when {
                    before == null -> groups.deleteSettlement(id)
                    before.deleted -> applySettlementTombstone(id, before.version)
                    else -> before.toEntity(SyncState.REJECTED)?.let { groups.upsertSettlement(it) }
                }
            }
            // A refused import part takes the whole import back instead ([reject], LocalGroupData.failImport).
            EntityKind.IMPORT -> Unit
        }
    }

    // ---- Pull (5.2) ----

    private suspend fun pullLocked(): SyncOutcome {
        val mine = when (val result = api.meGroups()) {
            is ApiResult.Ok -> result.value
            is ApiResult.Error -> return if (result.error.needsSignIn) SyncOutcome.SIGNED_OUT else SyncOutcome.RETRY
        }
        val listed = mine.groups.associateBy { it.id }
        val lost = mine.lost.associate { it.id to it.reason }
        // A group made a second ago is not on the server yet: its own create still waits. The groups are read first:
        // a group made between the two reads is then missing from the list, rather than listed without its create.
        val known = groups.allGroups()
        val pendingCreates = outbox.groupsWaiting(OutboxOp.CREATE)
        // A refused import left this phone and its delete waits (docs/splitwise-import.md 5.4): the server still
        // lists it until the delete is sent, and bringing the partial copy back would undo the clean-up.
        val pendingDeletes = outbox.groupsWaiting(OutboxOp.DELETE)
        for (group in known) {
            // Left, removed, deleted, or the person's join undone: the server says which (docs/server.md 4.5, 9.4).
            if (group.id !in listed && group.id !in pendingCreates) local.removeLostGroup(group.id, lost[group.id])
        }
        var outcome = SyncOutcome.DONE
        for (server in mine.groups) {
            if (server.id in pendingDeletes) continue
            val stored = groups.getGroup(server.id)
            transactions.run {
                if (stored == null) {
                    groups.upsertGroup(
                        GroupEntity(
                            id = server.id,
                            kind = groupKindOf(server.kind),
                            name = server.name,
                            ownerUserId = server.ownerUserId,
                            myMemberId = server.myMemberId,
                            version = 0,
                            latestVersion = server.version,
                            syncState = SyncState.SYNCED,
                            createdAt = clock.instant(),
                        ),
                    )
                } else {
                    groups.upsertGroup(stored.copy(myMemberId = server.myMemberId, ownerUserId = server.ownerUserId, latestVersion = server.version))
                }
            }
            if (server.version != (stored?.version ?: 0L)) {
                when (pullGroupLocked(server.id)) {
                    SyncOutcome.SIGNED_OUT -> return SyncOutcome.SIGNED_OUT
                    SyncOutcome.RETRY -> outcome = SyncOutcome.RETRY
                    SyncOutcome.DONE -> Unit
                }
            }
        }
        return outcome
    }

    private suspend fun pullGroupLocked(groupId: String): SyncOutcome {
        var since = groups.getGroup(groupId)?.version ?: 0L
        // A pull from version 0 (Join group, new phone, sign-in again) posts nothing: it would post the whole history.
        var fromZero = since == 0L
        val changes = ArrayList<ChangeDto>()
        while (true) {
            val page: ChangesPage = when (val result = api.changes(groupId, since)) {
                is ApiResult.Ok -> result.value
                is ApiResult.Error -> {
                    val error = result.error
                    return when {
                        error.code == NOT_MEMBER -> {
                            local.removeLostGroup(groupId, error.reason)
                            SyncOutcome.DONE
                        }
                        error.code == GROUP_DELETED -> {
                            local.removeGroup(groupId)
                            SyncOutcome.DONE
                        }
                        error.needsSignIn -> SyncOutcome.SIGNED_OUT
                        else -> {
                            groups.setLoadFailed(groupId, true)
                            SyncOutcome.RETRY
                        }
                    }
                }
            }
            if (since > page.latestVersion) {
                // Only after the server's database was restored from a backup: drop this copy and pull from 0.
                transactions.run {
                    groups.deleteSyncedExpensesOf(groupId)
                    groups.deleteSyncedSettlementsOf(groupId)
                    groups.setVersion(groupId, 0, page.latestVersion)
                }
                since = 0
                fromZero = true
                changes.clear()
                continue
            }
            transactions.run {
                apply(groupId, page)
                groups.setVersion(groupId, page.toVersion, page.latestVersion)
            }
            changes += page.changes
            val advanced = page.toVersion > since
            since = page.toVersion
            if (!page.hasMore || !advanced) break
        }
        if (!fromZero && groupId != visibleGroup()) {
            val events = eventsOf(groupId, changes)
            if (events.isNotEmpty()) notifier.post(events)
        }
        return SyncOutcome.DONE
    }

    /**
     * Applies one page (one transaction). Rows with a change waiting are skipped: the outbox wins. The server's copy
     * of a skipped row goes into the waiting entries' restore snapshot instead, so a rollback puts back the newest
     * state rather than the state the person started from (the group's version moves past this page either way).
     */
    private suspend fun apply(groupId: String, page: ChangesPage) {
        val pending = outbox.pendingEntityIds()
        val stored = groups.getGroup(groupId) ?: return
        if (groupId !in pending) {
            groups.upsertGroup(
                stored.copy(
                    kind = groupKindOf(page.group.kind),
                    name = page.group.name,
                    ownerUserId = page.group.ownerUserId,
                    ownerMemberId = page.group.ownerMemberId,
                    createdAt = if (page.group.createdAt > 0) Instant.ofEpochMilli(page.group.createdAt) else stored.createdAt,
                ),
            )
        } else {
            outbox.updateRestore(groupId) { it.copy(group = page.group) }
        }
        for (member in page.members) {
            if (member.id in pending) outbox.updateRestore(member.id) { it.copy(member = member) }
        }
        // The friend's name stays on this phone when someone takes the place through a link, for the owner's banner.
        val storedMembers = groups.membersOf(groupId).associateBy { it.id }
        val pulled = page.members.filter { it.id !in pending }
        groups.upsertMembers(pulled.map { it.toEntity(placeNameAfter(storedMembers[it.id], it)) })
        // The server turns a place's links off when someone joins it, when an undo-join puts the friend back and when
        // the member is removed: this phone's stored link for it is dead then ("link sent" would be wrong).
        for (member in pulled) {
            val before = storedMembers[member.id] ?: continue
            if (before.userId != member.userId || before.onApp != member.onApp || (!before.removed && member.removed)) {
                local.forgetLink(member.id)
            }
        }
        for (expense in page.expenses) {
            if (expense.id in pending) {
                outbox.updateRestore(expense.id) { it.copy(expense = expense) }
                continue
            }
            if (expense.deleted) applyExpenseTombstone(expense.id, expense.version) else storeExpense(expense, SyncState.SYNCED)
        }
        for (settlement in page.settlements) {
            if (settlement.id in pending) {
                outbox.updateRestore(settlement.id) { it.copy(settlement = settlement) }
                continue
            }
            val row = settlement.toEntity(SyncState.SYNCED)
            if (row == null) applySettlementTombstone(settlement.id, settlement.version) else groups.upsertSettlement(row)
        }
    }

    private suspend fun storeExpense(expense: ExpenseDto, state: SyncState) {
        val row = expense.toEntity(state, clock.instant()) ?: return applyExpenseTombstone(expense.id, expense.version)
        groups.upsertExpense(row)
        groups.deleteShares(expense.id)
        groups.insertShares(expense.shareEntities())
        mirrors.sync(expense.id)
    }

    private suspend fun applyExpenseTombstone(expenseId: String, version: Long?) {
        val stored = groups.getExpense(expenseId) ?: return
        groups.upsertExpense(stored.copy(deleted = true, version = version ?: stored.version, syncState = SyncState.SYNCED))
        groups.deleteShares(expenseId)
        mirrors.sync(expenseId)
    }

    private suspend fun applySettlementTombstone(settlementId: String, version: Long?) {
        val stored = groups.getSettlement(settlementId) ?: return
        groups.upsertSettlement(stored.copy(deleted = true, version = version ?: stored.version, syncState = SyncState.SYNCED))
    }

    /**
     * Notifications for changes by others that involve the person and are under 24 hours old (9.7). People are named
     * as the screens name them in sentences: the first name, or the whole name when another member shares it
     * ([shortName]).
     */
    private suspend fun eventsOf(groupId: String, changes: List<ChangeDto>): List<GroupEvent> {
        val group = groups.getGroup(groupId) ?: return emptyList()
        val me = group.myMemberId
        val oldest = clock.millis() - DAY_MS
        val names = memberNames(groupId)
        val events = ArrayList<GroupEvent>()
        for (change in changes) {
            val actorId = change.actorMemberId ?: continue
            if (actorId == me || change.at < oldest) continue
            val actor = groups.getMember(actorId)?.name?.let { shortName(it, names) } ?: continue
            when (change.kind to change.op) {
                // A family expense never makes a notification (docs/family.md decision 10): nobody owes anybody.
                "expense" to "create" -> if (group.kind != GroupKind.FAMILY) {
                    val stored = groups.getExpenseWithShares(change.entityId) ?: continue
                    if (stored.expense.deleted || stored.expense.paidByMemberId == me) continue
                    val myShare = stored.shares.firstOrNull { it.memberId == me }?.shareMinor ?: 0L
                    if (myShare > 0) events += GroupEvent.ExpenseAdded(groupId, stored.expense.id, actor, stored.expense.description, myShare)
                }
                "settlement" to "create" -> {
                    val settlement = groups.getSettlement(change.entityId) ?: continue
                    if (settlement.deleted || (settlement.fromMemberId != me && settlement.toMemberId != me)) continue
                    events += GroupEvent.SettlementRecorded(groupId, settlement.id, actor, settlement.amountMinor, settlement.confirmedAt == null)
                }
                "settlement" to "confirm" -> {
                    val settlement = groups.getSettlement(change.entityId) ?: continue
                    if (settlement.deleted || settlement.recordedByMemberId != me) continue
                    events += GroupEvent.SettlementConfirmed(groupId, settlement.id, actor, settlement.amountMinor)
                }
                "member" to "join" -> {
                    val member = groups.getMember(change.entityId) ?: continue
                    events += GroupEvent.MemberJoined(
                        groupId, member.id, joinerName(member, names), displayName(group), family = group.kind == GroupKind.FAMILY,
                    )
                }
            }
        }
        return events
    }

    /** The names of every member of [groupId], removed ones included: whom a first name must not be mistaken for. */
    private suspend fun memberNames(groupId: String): List<String> = groups.membersOf(groupId).map { it.name }

    /**
     * "{name} joined Flat 302" names the joining account the way the owner's banner does (02-everyday/40): in first
     * names, except when someone with another whole name behind the same first name took a friend's place ("Rahul
     * Kumar" in "Rahul Verma"'s): then whole, which is how a wrong join shows. "Priya Nair" in the place of "Priya" is
     * "Priya".
     */
    private fun joinerName(member: MemberEntity, names: List<String>): String {
        val joiner = member.name.trim()
        val place = (member.placeName ?: member.name).trim()
        val twoPeople = !place.equals(joiner, ignoreCase = true) && place.contains(' ') && joiner.contains(' ')
        return shortName(joiner, names + listOfNotNull(place.takeIf { twoPeople }))
    }

    /** A group's or a family's name; for a direct split, the other person's name. */
    private suspend fun displayName(group: GroupEntity): String =
        if (group.kind != GroupKind.DIRECT) {
            group.name.orEmpty()
        } else {
            groups.membersOf(group.id).firstOrNull { it.id != group.myMemberId }?.name.orEmpty()
        }

    private suspend fun isFamily(groupId: String?): Boolean = groupId != null && groups.getGroup(groupId)?.kind == GroupKind.FAMILY

    private fun <T> decode(serializer: KSerializer<T>, body: JsonElement): T? =
        runCatching { json.decodeFromJsonElement(serializer, body) }.getOrNull()

    private companion object {
        const val NOT_MEMBER = "not_member"
        const val GROUP_DELETED = "group_deleted"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MAX_RATE_LIMIT_WAITS = 3
        const val MAX_PUSH_PASSES = 2
        const val DEFAULT_RETRY_AFTER_SECONDS = 60L
        const val MAX_RETRY_AFTER_SECONDS = 60L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
