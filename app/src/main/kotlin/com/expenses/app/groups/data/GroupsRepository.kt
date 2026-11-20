package com.expenses.app.groups.data

import android.util.Base64
import com.expenses.app.data.category.Category
import com.expenses.app.data.category.CategoryGuesser
import com.expenses.app.data.db.ExpenseDao
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.repository.TransactionRunner
import com.expenses.app.di.AppContainer
import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.ExpenseInputDto
import com.expenses.app.groups.api.GroupBodies
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.api.MemberBody
import com.expenses.app.groups.api.RenameGroupRequest
import com.expenses.app.groups.api.SettlementInputDto
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.api.map
import com.expenses.app.groups.splitwise.ImportPlanner
import com.expenses.app.groups.sync.LocalGroupData
import com.expenses.app.groups.sync.Outbox
import com.expenses.app.groups.sync.SyncScheduler
import com.expenses.app.ui.components.AvatarColors
import com.expenses.parser.Direction
import com.expenses.splits.DirectIds
import com.expenses.splits.SplitMath
import com.expenses.splits.SplitMethod
import com.expenses.splitwise.SplitwiseFile
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

/**
 * Groups, group expenses and settlements on this phone (docs/server.md 5, 6.4, 9.4, 14 C). Reads come from Room only;
 * every write lands in Room and the outbox in one transaction and is sent by the sync engine later, so it works
 * offline. Leave, remove, delete, links, invites and reminders need the server at once and say so in their result.
 *
 * Only what the person puts in a group goes to the server: its description, category, amount, date and shares.
 * Notes, account, card, "paid with", SMS text and the exact time stay on the phone.
 *
 * A family (docs/family.md 8.2) never shows here: not in Groups, Friends, the totals, split labels, known people or
 * the stored-groups state, and its expenses never take part in balances. [FamilyRepository] is its repository.
 *
 * @param family the family month's sharing: told when a split takes a payment out of it or gives it back.
 */
class GroupsRepository(
    private val api: GroupsApi,
    private val transactions: TransactionRunner,
    private val groups: GroupDao,
    private val extras: GroupExtrasDao,
    private val expenses: ExpenseDao,
    private val outbox: Outbox,
    private val mirrors: MirrorWriter,
    private val local: LocalGroupData,
    private val scheduler: SyncScheduler,
    private val currentUser: suspend () -> UserDto?,
    private val clock: Clock,
    private val linkHost: String,
    private val categoryName: (Category) -> String = { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase) },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val family: FamilyLinker? = null,
) {
    /** The personal state of payments split in this process, for Undo on "Split. Sorted.". */
    private val splitSnapshots = ConcurrentHashMap<String, PersonalSnapshot>()
    private val random = SecureRandom()

    // ---- Reads ----

    /**
     * The group tables at one moment.
     *
     * @param storedMembers every member, by name, each with the order its row was stored in.
     */
    private data class Snapshot(
        val groups: List<GroupEntity>,
        val storedMembers: List<StoredMember>,
        val expenses: List<GroupExpenseWithShares>,
        val settlements: List<SettlementEntity>,
        val imported: Set<String> = emptySet(),
    ) {
        val members: List<MemberEntity> = storedMembers.map { it.member }
        val membersByGroup = members.groupBy { it.groupId }

        /** The groups and direct splits: everything but a family (docs/family.md 8.2). */
        val splitGroups: List<GroupEntity> = groups.filter { it.kind != GroupKind.FAMILY }
        private val familyIds: Set<String> = groups.filter { it.kind == GroupKind.FAMILY }.mapTo(HashSet()) { it.id }

        /** The one source of avatar colours: every read below takes its colours from here. */
        val avatars: AvatarColors by lazy { avatarColorsOf(groups, storedMembers) }
        val balances: Map<String, GroupBalance> = splitGroups.associate { group ->
            group.id to GroupBalance.of(
                expenses.filter { it.expense.groupId == group.id },
                settlements.filter { it.groupId == group.id },
            )
        }

        /** The balance of group [id]; nothing for a family. */
        fun balanceOf(id: String): GroupBalance = balances[id] ?: GroupBalance.of(emptyList(), emptyList())

        /** True for a member of a family who is not on the app: the invite placeholder. */
        fun isFamilyPlaceholder(member: MemberEntity): Boolean = member.groupId in familyIds && !member.onApp
    }

    private val snapshot: Flow<Snapshot> = combine(
        groups.observeGroups(),
        groups.observeMembers(),
        groups.observeActiveExpenses(),
        groups.observeActiveSettlements(),
        extras.observeImportedGroupIds(),
    ) { g, m, e, s, i -> Snapshot(g, m, e, s, i.toSet()) }

    /** Named groups with the person's net in each (Groups, 02/20). */
    fun observeGroups(): Flow<List<GroupSummary>> = snapshot.map(::summariesOf).distinctUntilChanged()

    private fun summariesOf(data: Snapshot): List<GroupSummary> =
        data.groups.filter { it.kind == GroupKind.GROUP }.map { group ->
            GroupSummary(
                id = group.id,
                name = group.name.orEmpty(),
                memberCount = data.membersByGroup[group.id].orEmpty().count { !it.removed },
                myNetMinor = data.balances.getValue(group.id).netOf(group.myMemberId),
                load = loadState(group, group.id in data.imported),
                avatar = data.avatars.ofGroup(group.id),
            )
        }

    /**
     * Everything the Groups tab lists, read together ([GroupsHome]): its parts always belong to the same moment, so
     * the tab never shows the groups of one read with the "nothing is here" of another.
     */
    fun observeHome(): Flow<GroupsHome> = snapshot.map { data ->
        GroupsHome(summariesOf(data), friendsOf(data), totalsOf(data), storedOf(data.splitGroups, data.imported))
    }.distinctUntilChanged()

    /** [observeHome], read now. */
    suspend fun home(): GroupsHome = observeHome().first()

    private fun storedOf(groups: List<GroupEntity>, imported: Set<String>): StoredGroups = when {
        groups.isEmpty() -> StoredGroups.NONE
        groups.all { loadState(it, it.id in imported) == LoadState.READY } -> StoredGroups.COMPLETE
        else -> StoredGroups.INCOMPLETE
    }

    /**
     * The colour behind every initial ([AvatarColors]): everyone and every group on this phone, emitted again on
     * every change. [GroupSummary.avatar], [FriendBalance.avatar] and [MemberBalance.avatar] are read from the same
     * snapshot, so a screen that shows those needs nothing else.
     */
    fun observeAvatarColors(): Flow<AvatarColors> = snapshot.map { it.avatars }.distinctUntilChanged()

    /** Per person, the person's transfers with them over every group (Friends, 6.4), largest first. */
    fun observeFriends(): Flow<List<FriendBalance>> = snapshot.map(::friendsOf).distinctUntilChanged()

    private fun friendsOf(data: Snapshot): List<FriendBalance> {
        val totals = LinkedHashMap<String, FriendBalance>()
        for (group in data.splitGroups) {
            val balance = data.balances.getValue(group.id)
            val members = data.membersByGroup[group.id].orEmpty()
            for (member in members) {
                if (member.id == group.myMemberId) continue
                val pair = balance.pairBalance(group.myMemberId, member.id)
                if (pair == 0L) continue
                val key = personKeyOf(member)
                val before = totals[key]
                totals[key] = FriendBalance(
                    key, member.name, member.userId, member.phone, (before?.netMinor ?: 0L) + pair,
                    avatar = data.avatars.ofPerson(key),
                    directGroupId = before?.directGroupId ?: group.id.takeIf { group.kind == GroupKind.DIRECT },
                )
            }
        }
        return totals.values.filter { it.netMinor != 0L }.sortedByDescending { kotlin.math.abs(it.netMinor) }
    }

    /** The person's totals over every group and direct split (Groups card, Home "Owed to you" / "You owe"). */
    fun observeTotals(): Flow<OwedTotals> = snapshot.map(::totalsOf).distinctUntilChanged()

    private fun totalsOf(data: Snapshot): OwedTotals {
        val nets = data.splitGroups.map { data.balances.getValue(it.id).netOf(it.myMemberId) }
        return OwedTotals(
            owedToMeMinor = nets.filter { it > 0 }.sum(),
            iOweMinor = -nets.filter { it < 0 }.sum(),
            overallMinor = nets.sum(),
            groupCount = data.groups.count { it.kind == GroupKind.GROUP },
        )
    }

    /** The Group page of [id]: members with their balance to the person, expenses and settlements; null once gone. */
    fun observeGroup(id: String): Flow<GroupDetail?> = snapshot.map { data ->
        val group = data.groups.firstOrNull { it.id == id } ?: return@map null
        val balance = data.balanceOf(id)
        val members = data.membersByGroup[id].orEmpty()
        val me = currentUserId(data, group)
        GroupDetail(
            group = group,
            name = displayName(group, members),
            members = members
                .sortedWith(compareBy<MemberEntity> { it.removed }.thenBy { it.id != group.myMemberId }.thenBy { it.name })
                .map { member ->
                    MemberBalance(
                        member = member,
                        isMe = member.id == group.myMemberId,
                        pairMinor = if (member.id == group.myMemberId) 0L else balance.pairBalance(group.myMemberId, member.id),
                        netMinor = balance.netOf(member.id),
                        avatar = data.avatars.ofMember(member.id),
                    )
                },
            expenses = data.expenses.filter { it.expense.groupId == id },
            settlements = data.settlements.filter { it.groupId == id },
            myNetMinor = balance.netOf(group.myMemberId),
            isOwner = me != null && group.ownerUserId == me,
            load = loadState(group, id in data.imported),
            // Until a group is pulled again after server migration 0002 (or while the live server is older), the
            // stored ownerMemberId is empty: the owner is then the active member who is the owning user.
            ownerMemberId = group.ownerMemberId
                ?: members.firstOrNull { it.userId != null && it.userId == group.ownerUserId && !it.removed }?.id,
        )
    }.distinctUntilChanged()

    /** Whether group [id]'s data is complete (02/21-23). */
    fun observeLoad(id: String): Flow<LoadState> =
        combine(groups.observeGroup(id), extras.observeImportedGroupIds()) { group, imported ->
            group?.let { loadState(it, it.id in imported) } ?: LoadState.LOADING
        }.distinctUntilChanged()

    /**
     * What each split payment is part of, by group expense id (Activity "your share of ₹450" with the group name,
     * Home's latest rows, Expense detail "Flat 302 · Split equally · your share ₹150"). Deleted expenses are left out.
     */
    fun observeSplitLabels(): Flow<Map<String, SplitLabel>> = snapshot.map { data ->
        val byId = data.splitGroups.associateBy { it.id }
        data.expenses.mapNotNull { row ->
            val group = byId[row.expense.groupId] ?: return@mapNotNull null
            val members = data.membersByGroup[group.id].orEmpty()
            SplitLabel(
                groupExpenseId = row.expense.id,
                groupId = group.id,
                groupKind = group.kind,
                groupName = displayName(group, members),
                method = row.expense.splitMethod,
                totalMinor = row.expense.amountMinor,
                myShareMinor = row.shares.firstOrNull { it.memberId == group.myMemberId }?.shareMinor ?: 0L,
                paidByMe = row.expense.paidByMemberId == group.myMemberId,
                paidByName = members.firstOrNull { it.id == row.expense.paidByMemberId }?.name.orEmpty(),
                notSent = row.expense.syncState == SyncState.PENDING,
            )
        }.associateBy { it.groupExpenseId }
    }.distinctUntilChanged()

    /** The [SplitLabel] of one group expense, or null when it is not (or no longer) in a group on this phone. */
    fun observeSplitLabel(groupExpenseId: String): Flow<SplitLabel?> =
        observeSplitLabels().map { it[groupExpenseId] }.distinctUntilChanged()

    /** The members of [groupId] that have a link this phone made (Group settings "link sent", the friend's sheet). */
    fun observeLinkedMembers(groupId: String): Flow<Set<String>> =
        extras.observeLinksOf(groupId).map { links -> links.mapTo(HashSet()) { it.memberId } }.distinctUntilChanged()

    /** How many changes wait to be sent ("2 changes are not sent yet" on Sign out; Home's offline banner). */
    fun observeUnsentCount(): Flow<Int> = outbox.observeCount().distinctUntilChanged()

    /**
     * App users and friends from the person's groups (New group, Choose people), each once. A family's partner on the
     * app is someone the person knows; its invite placeholder is nobody.
     */
    fun observeKnownPeople(): Flow<List<Person>> = snapshot.map { data ->
        val mine = data.groups.map { it.myMemberId }.toSet()
        val myUserIds = data.members.filter { it.id in mine }.mapNotNull { it.userId }.toSet()
        data.members
            .filter { !it.removed && it.id !in mine && (it.userId == null || it.userId !in myUserIds) && !data.isFamilyPlaceholder(it) }
            .groupBy(::personKeyOf)
            .map { (key, rows) -> rows.first().let { Person(key, it.name, it.userId, it.phone) } }
            .sortedBy { it.name.lowercase() }
    }.distinctUntilChanged()

    /** The person's "Do this every time" rule for payments to [counterparty], or null. */
    fun splitRuleFor(counterparty: String?): Flow<SplitRule?> {
        val key = counterparty?.let(CategoryGuesser::merchantKey)?.takeIf { it.isNotEmpty() } ?: return flowOf(null)
        return extras.observeRule(key).map { rule ->
            rule?.let { SplitRule(it.groupId, it.splitMethod, decodeInputs(it.inputsJson), it.auto) }
        }
    }

    /**
     * Same split? (02-everyday/14): [auto] true makes payments to [counterparty] split themselves with the rule kept
     * by "Do this every time" ("Yes, every month"); false keeps the one-tap suggestion ("Ask me each time"). Nothing
     * happens when there is no rule. On this phone only.
     */
    suspend fun setSplitRuleAuto(counterparty: String, auto: Boolean) {
        val key = CategoryGuesser.merchantKey(counterparty).takeIf { it.isNotEmpty() } ?: return
        extras.setRuleAuto(key, auto)
    }

    /** Forgets how payments to [counterparty] were split ("Do this every time" switched off). On this phone only. */
    suspend fun forgetSplitRule(counterparty: String) {
        val key = CategoryGuesser.merchantKey(counterparty).takeIf { it.isNotEmpty() } ?: return
        extras.deleteRule(key)
    }

    // ---- Groups and members (queued) ----

    /** Makes a named group with the person as owner and [people] as members. Returns its id. */
    suspend fun createGroup(name: String, people: List<NewPerson>): String {
        val me = signedInUser()
        val groupId = newId()
        val myMemberId = newId()
        val cleanName = GroupText.clean(name, GroupText.MAX_GROUP_NAME)
        require(cleanName.isNotEmpty()) { "group name is blank" }
        val others = people.map { person -> newMember(groupId, person) }
        transactions.run {
            groups.upsertGroup(
                GroupEntity(
                    groupId, GroupKind.GROUP, cleanName, me.id, myMemberId, version = 0, latestVersion = 0, syncState = SyncState.PENDING,
                    createdAt = clock.instant(), ownerMemberId = myMemberId,
                ),
            )
            groups.upsertMembers(listOf(myMember(groupId, myMemberId, me)) + others.map { it.first })
            outbox.append(
                groupId, EntityKind.GROUP, groupId, OutboxOp.CREATE, "POST", "/v1/groups",
                GroupBodies.create(groupId, GroupKind.GROUP.apiName, cleanName, myMemberId, others.map { it.second }),
                restore = null,
            )
        }
        scheduler.requestSync()
        return groupId
    }

    /** Renames a named group or a family (docs/family.md 3.2). Queued: works offline, last writer wins. */
    suspend fun renameGroup(id: String, name: String) {
        val cleanName = GroupText.clean(name, GroupText.MAX_GROUP_NAME)
        require(cleanName.isNotEmpty()) { "group name is blank" }
        transactions.run {
            val group = groups.getGroup(id) ?: return@run
            require(group.kind != GroupKind.DIRECT) { "direct splits have no name" }
            groups.upsertGroup(group.copy(name = cleanName, syncState = SyncState.PENDING))
            outbox.append(
                id, EntityKind.GROUP, id, OutboxOp.UPDATE, "PATCH", "/v1/groups/$id",
                ApiJson.encodeToJsonElement(RenameGroupRequest.serializer(), RenameGroupRequest(cleanName)),
                RestoreSnapshot(group = group.toDto()),
            )
        }
        scheduler.requestSync()
    }

    /** Adds [person] to group [groupId]. Returns the new member id. */
    suspend fun addMember(groupId: String, person: NewPerson): String {
        val (member, body) = newMember(groupId, person)
        transactions.run {
            groups.upsertMembers(listOf(member))
            outbox.append(groupId, EntityKind.MEMBER, member.id, OutboxOp.CREATE, "POST", "/v1/groups/$groupId/members", GroupBodies.member(body), null)
        }
        scheduler.requestSync()
        return member.id
    }

    /** Changes the name and phone of a friend without the app. */
    suspend fun editFriend(groupId: String, memberId: String, name: String, phone: String?) {
        val cleanName = GroupText.clean(name, GroupText.MAX_PERSON_NAME)
        require(cleanName.isNotEmpty()) { "name is blank" }
        val cleanPhone = GroupText.normalizePhone(phone)
        transactions.run {
            val member = groups.getMember(memberId) ?: return@run
            require(member.userId == null) { "only friends without the app can be edited" }
            groups.upsertMembers(listOf(member.copy(name = cleanName, phone = cleanPhone)))
            outbox.append(
                groupId, EntityKind.MEMBER, memberId, OutboxOp.UPDATE, "PATCH", "/v1/groups/$groupId/members/$memberId",
                GroupBodies.editFriend(cleanName, cleanPhone), RestoreSnapshot(member = member.toDto()),
            )
        }
        scheduler.requestSync()
    }

    // ---- Online-only group actions (5.1 step 5) ----

    /** Removes a member whose net is 0 (an app user needs the owner). Needs the server at once. */
    suspend fun removeMember(groupId: String, memberId: String): ActionResult =
        online(api.removeMember(groupId, memberId, newId())) {
            transactions.run {
                groups.getMember(memberId)?.let { groups.upsertMembers(listOf(it.copy(removed = true))) }
                extras.deleteLink(memberId)
            }
        }

    /**
     * Leaves group [id] once the person's net is 0. Needs the server at once. The group leaves this phone with the
     * record of the import that made it, if any ([LocalGroupData.removeOwnGroup]).
     */
    suspend fun leaveGroup(id: String): ActionResult = online(api.leaveGroup(id, newId())) { local.removeOwnGroup(id) }

    /**
     * Deletes group [id] for everyone: the owner, when every net is 0 or nobody else in the group is on the app
     * (docs/server.md 4.6; the second case is how a wrong Splitwise import is removed, design 02-everyday/143). Needs
     * the server at once. The group leaves this phone with the record of the import that made it, also while parts of
     * that import still waited to be sent ([LocalGroupData.removeOwnGroup]).
     */
    suspend fun deleteGroup(id: String): ActionResult = online(api.deleteGroup(id, newId())) { local.removeOwnGroup(id) }

    /**
     * Undoes a join by a friend's link (docs/server.md 4.6; product.md "Forwarded links"): the owner, within 7 days.
     * The place goes back to the friend without the app it was, every link for it stops working, and this phone
     * forgets the link it made for it (the next Remind makes a new one). Needs the server at once.
     *
     * Refusals come back as [ActionResult.Failed] with the server's code, and this phone is put right at once (a pull
     * alone would not do it: time passing sends no row again):
     * - `undo_expired` (the 7 days ended, by the server's clock) and `not_joined_by_link`: the member's join window
     *   is cleared here, so the banner goes now, whatever this phone's clock says.
     * - `not_owner` (ownership passed meanwhile): the group is pulled again.
     * - `not_member` and `group_deleted`: the group leaves this phone.
     */
    suspend fun undoJoin(groupId: String, memberId: String): ActionResult =
        when (val result = api.undoJoin(groupId, memberId, newId())) {
            is ApiResult.Ok -> {
                transactions.run {
                    groups.upsertMembers(listOf(result.value.toEntity()))
                    extras.deleteLink(memberId)
                }
                scheduler.requestSync()
                ActionResult.Done
            }
            is ApiResult.Error -> {
                when (result.error.code) {
                    UNDO_EXPIRED, NOT_JOINED_BY_LINK -> {
                        transactions.run {
                            groups.getMember(memberId)?.let { groups.upsertMembers(listOf(it.copy(joinedAt = null, undoUntil = null))) }
                        }
                        scheduler.requestSync()
                    }
                    NOT_OWNER -> scheduler.requestSync()
                    NOT_MEMBER -> local.removeLostGroup(groupId, result.error.reason)
                    GROUP_DELETED -> local.removeGroup(groupId)
                }
                actionError(result.error)
            }
        }

    /**
     * Turns off every link of the friend [memberId] (02-everyday/46 "Turn off link"): the link stops working, and the
     * next Remind makes a new one. Needs the server at once.
     */
    suspend fun turnOffLinks(groupId: String, memberId: String): ActionResult =
        online(api.deleteMemberLinks(groupId, memberId, newId())) { extras.deleteLink(memberId) }

    /**
     * The direct split with [person]: an existing one with that user or phone (a friend with neither: that name, as
     * [personKeyOf] knows them), else a new one. With an app user its ids are the same on both phones (docs/server.md
     * 4.6), so it can be made offline.
     */
    suspend fun directGroupWith(person: Person): String {
        val me = signedInUser()
        val byNameOnly = person.userId == null && person.phone == null
        val existing = transactions.run {
            groups.allGroups().filter { it.kind == GroupKind.DIRECT }.firstOrNull { group ->
                groups.membersOf(group.id).any { member ->
                    member.id != group.myMemberId &&
                        ((person.userId != null && member.userId == person.userId) || (person.phone != null && member.phone == person.phone) ||
                            (byNameOnly && member.userId == null && member.phone == null && member.name.trim().equals(person.name.trim(), ignoreCase = true)))
                }
            }
        }
        if (existing != null) return existing.id
        val groupId: String
        val myMemberId: String
        val other: Pair<MemberEntity, MemberBody>
        if (person.userId != null) {
            groupId = DirectIds.groupId(me.id, person.userId)
            myMemberId = DirectIds.memberId(groupId, me.id)
            val otherId = DirectIds.memberId(groupId, person.userId)
            other = MemberEntity(otherId, groupId, person.userId, person.name, person.phone, null, onApp = true, removed = false, version = 0) to
                MemberBody.User(otherId, person.userId)
        } else {
            groupId = newId()
            myMemberId = newId()
            other = newMember(groupId, NewPerson.Friend(person.name, person.phone))
        }
        transactions.run {
            groups.upsertGroup(
                GroupEntity(groupId, GroupKind.DIRECT, null, null, myMemberId, version = 0, latestVersion = 0, syncState = SyncState.PENDING, createdAt = clock.instant()),
            )
            groups.upsertMembers(listOf(myMember(groupId, myMemberId, me), other.first))
            outbox.append(
                groupId, EntityKind.GROUP, groupId, OutboxOp.CREATE, "POST", "/v1/groups",
                GroupBodies.create(groupId, GroupKind.DIRECT.apiName, null, myMemberId, listOf(other.second)),
                restore = null,
            )
        }
        scheduler.requestSync()
        return groupId
    }

    // ---- Expenses (queued) ----

    /**
     * Splits the person's own payment [expenseId] in group [groupId] (Split on a payment, docs/server.md 9.4): a
     * group expense paid by the person (or [paidByMemberId]) with shares from [method] and [inputs], and the payment
     * then counts only the person's share. With [rememberRule], payments to the same merchant are offered the same
     * split ("Do this every time"); [auto] makes them split themselves.
     *
     * @return the group expense id, for Undo.
     * @throws IllegalArgumentException when the inputs do not fit the method.
     */
    suspend fun splitPayment(
        expenseId: Long,
        groupId: String,
        method: SplitMethod,
        inputs: Map<String, Long?>,
        paidByMemberId: String? = null,
        rememberRule: Boolean,
        auto: Boolean,
    ): String {
        val id = newId()
        val snapshot = transactions.run {
            val row = expenses.get(expenseId) ?: throw IllegalArgumentException("unknown payment")
            require(row.direction == Direction.DEBIT && row.groupExpenseId == null) { "payment cannot be split" }
            val shares = SplitMath.shares(method, row.amountMinor, inputs)
            val group = groups.getGroup(groupId) ?: throw IllegalArgumentException("unknown group")
            val before = mirrors.snapshotOf(row)
            val description = GroupText.descriptionFor(row.counterparty, categoryName(row.category))
            val expense = GroupExpenseEntity(
                id = id,
                groupId = groupId,
                description = description,
                category = row.category,
                amountMinor = row.amountMinor,
                occurredOn = row.occurredAt.atZone(clock.zone).toLocalDate(),
                paidByMemberId = paidByMemberId ?: group.myMemberId,
                splitMethod = method,
                auto = auto,
                createdByMemberId = group.myMemberId,
                updatedByMemberId = group.myMemberId,
                updatedAt = clock.instant(),
                deleted = false,
                version = 0,
                syncState = SyncState.PENDING,
            )
            val stored = storeLocal(expense, shares, inputs)
            mirrors.rememberLink(groupId, id, row.id, row.status)
            expenses.update(row.copy(status = ExpenseStatus.ADDED, groupExpenseId = id))
            mirrors.sync(id)
            outbox.append(groupId, EntityKind.EXPENSE, id, OutboxOp.CREATE, "PUT", expensePath(groupId, id), encode(stored.toInput(null)), RestoreSnapshot(personal = before))
            // A Family payment shared in the family month leaves it once split (docs/family.md rule 8).
            family?.onChanged(listOf(row.id))
            if (rememberRule) {
                CategoryGuesser.merchantKey(row.counterparty.orEmpty()).takeIf { it.isNotEmpty() }?.let { key ->
                    extras.upsertRule(SplitRuleEntity(key, groupId, method, encodeInputs(inputs), auto))
                }
            }
            before
        }
        splitSnapshots[id] = snapshot
        scheduler.requestSync()
        return id
    }

    /**
     * Adds a group expense typed in (02/15). Paid by the person: a typed personal row linked to it counts their
     * share. Paid by someone else: a mirror row counts the person's share, if any.
     *
     * @return the group expense id.
     */
    suspend fun addGroupExpense(groupId: String, draft: GroupExpenseDraft): String {
        val id = newId()
        val shares = SplitMath.shares(draft.method, draft.amountMinor, draft.inputs)
        val description = GroupText.clean(draft.description, GroupText.MAX_DESCRIPTION).ifEmpty { categoryName(draft.category) }
        transactions.run {
            val group = groups.getGroup(groupId) ?: throw IllegalArgumentException("unknown group")
            val expense = GroupExpenseEntity(
                id, groupId, description, draft.category, draft.amountMinor, draft.occurredOn, draft.paidByMemberId, draft.method,
                draft.auto, group.myMemberId, group.myMemberId, clock.instant(), deleted = false, version = 0, syncState = SyncState.PENDING,
            )
            val stored = storeLocal(expense, shares, draft.inputs)
            val personal = if (draft.paidByMemberId == group.myMemberId) {
                // The person paid with no SMS: a typed payment, linked the same way as an SMS payment.
                val rowId = expenses.insert(
                    Expense(
                        amountMinor = draft.amountMinor,
                        direction = Direction.DEBIT,
                        counterparty = description,
                        category = draft.category,
                        occurredAt = draft.occurredOn.atStartOfDay(clock.zone).toInstant(),
                        source = ExpenseSource.MANUAL,
                        status = ExpenseStatus.ADDED,
                        paidWith = PaidWith.OTHER,
                        myShareMinor = shares[group.myMemberId] ?: 0L,
                        groupExpenseId = id,
                    ),
                )
                // Rolled back, or the person's join undone, it stays as the person's own expense, counted in full.
                mirrors.rememberLink(groupId, id, rowId, ExpenseStatus.ADDED)
                PersonalSnapshot(rowId, ExpenseStatus.ADDED.name, draft.amountMinor, null)
            } else {
                mirrors.sync(id)
                expenses.findByGroupExpense(id)?.let { mirrors.snapshotOf(it, deleteRow = true) }
            }
            outbox.append(groupId, EntityKind.EXPENSE, id, OutboxOp.CREATE, "PUT", expensePath(groupId, id), encode(stored.toInput(null)), RestoreSnapshot(personal = personal))
        }
        scheduler.requestSync()
        return id
    }

    /** Replaces every field and share of group expense [id] (last writer wins on the server). */
    suspend fun updateGroupExpense(id: String, draft: GroupExpenseDraft) {
        val shares = SplitMath.shares(draft.method, draft.amountMinor, draft.inputs)
        transactions.run {
            val before = groups.getExpenseWithShares(id) ?: throw IllegalArgumentException("unknown expense")
            require(!before.expense.deleted) { "expense was deleted" }
            val group = groups.getGroup(before.expense.groupId) ?: throw IllegalArgumentException("unknown group")
            val personal = expenses.findByGroupExpense(id)?.let { mirrors.snapshotOf(it) }
            val description = GroupText.clean(draft.description, GroupText.MAX_DESCRIPTION).ifEmpty { categoryName(draft.category) }
            val expense = before.expense.copy(
                description = description,
                category = draft.category,
                amountMinor = draft.amountMinor,
                occurredOn = draft.occurredOn,
                paidByMemberId = draft.paidByMemberId,
                splitMethod = draft.method,
                auto = draft.auto,
                updatedByMemberId = group.myMemberId,
                updatedAt = clock.instant(),
                syncState = SyncState.PENDING,
            )
            val stored = storeLocal(expense, shares, draft.inputs)
            mirrors.sync(id)
            // The payer changed away from the person: their payment is no longer split, and may be shared again.
            personal?.let { family?.onChanged(listOf(it.expenseId)) }
            val baseVersion = before.expense.version.takeIf { it > 0 }
            outbox.append(
                group.id, EntityKind.EXPENSE, id, OutboxOp.UPDATE, "PUT", expensePath(group.id, id), encode(stored.toInput(baseVersion)),
                RestoreSnapshot(expense = before.toDto(), personal = personal),
            )
        }
        scheduler.requestSync()
    }

    /**
     * Deletes group expense [id] for everyone (Expense detail of a linked payment, Undo). The person's own payment
     * counts in full again; a mirror goes.
     */
    suspend fun deleteGroupExpense(id: String) {
        transactions.run { deleteLocally(id) }
        scheduler.requestSync()
    }

    /**
     * Undo on "Split. Sorted.": deletes the group expense (offline and never sent, nothing reaches the server) and
     * puts the payment back as it was before Split.
     *
     * @param fallback how the payment was, from the screen, for when this process did not make the split (Android
     * stopped the app meanwhile) and its create was already sent: nothing else on the phone remembers it then.
     */
    suspend fun undoSplit(groupExpenseId: String, fallback: PersonalSnapshot? = null) {
        transactions.run {
            val before = splitSnapshots.remove(groupExpenseId)
                ?: outbox.entriesOf(groupExpenseId).firstOrNull { it.op == OutboxOp.CREATE }?.let { outbox.restoreOf(it)?.personal }
                ?: fallback
            deleteLocally(groupExpenseId)
            before?.let {
                mirrors.restore(it)
                // An undone split of a Family payment shares it in the family month again (docs/family.md rule 8).
                family?.onChanged(listOf(it.expenseId))
            }
        }
        scheduler.requestSync()
    }

    // ---- Settlements (queued) ----

    /**
     * Records that [fromMemberId] paid [toMemberId] [amountMinor] today (Settle up, Mark as paid). Partial payments are
     * fine. A match from SMS uses [recordSmsSettlement].
     *
     * @param id the settlement's id: a new one. An id a settlement on this phone already has is refused
     * (IllegalArgumentException), so a stored settlement, the server's copy included, is never replaced.
     * @return the settlement id.
     */
    suspend fun recordSettlement(
        groupId: String,
        fromMemberId: String,
        toMemberId: String,
        amountMinor: Long,
        method: SettleMethod,
        matchedFromSms: Boolean,
        id: String = newId(),
    ): String {
        checkSettlement(fromMemberId, toMemberId, amountMinor)
        transactions.run {
            require(groups.getSettlement(id) == null) { "settlement exists" }
            storeSettlement(id, groupId, fromMemberId, toMemberId, amountMinor, method, matchedFromSms, today())
        }
        scheduler.requestSync()
        return id
    }

    /**
     * Records a settlement matched from SMS (docs/server.md 9.5): [fromMemberId] paid [toMemberId] [amountMinor] on
     * [smsDay], the day the SMS arrived. The payer's phone (its debit after Pay by UPI) and the receiver's (its credit,
     * or "Yes, clear it") both record the same payment, so the id is one both phones work out the same way:
     * [smsSettlementId] with the first `n` (0, 1, 2, …) that no settlement on this phone has, a deleted one included.
     * Both phones hold the same earlier settlements of that pair, amount and day (their own record or the one they
     * pulled), so they pick the same `n`, and the server keeps one settlement per payment. The date is [smsDay] for the
     * same reason: the server compares it.
     *
     * @return the settlement id.
     */
    suspend fun recordSmsSettlement(
        groupId: String,
        fromMemberId: String,
        toMemberId: String,
        amountMinor: Long,
        method: SettleMethod,
        smsDay: LocalDate,
    ): String {
        checkSettlement(fromMemberId, toMemberId, amountMinor)
        val id = transactions.run {
            val id = generateSequence(0) { it + 1 }
                .take(MAX_SMS_SETTLEMENTS_A_DAY)
                .map { n -> smsSettlementId(groupId, fromMemberId, toMemberId, amountMinor, smsDay, n) }
                .firstOrNull { groups.getSettlement(it) == null }
                ?: throw IllegalArgumentException("too many equal settlements")
            storeSettlement(id, groupId, fromMemberId, toMemberId, amountMinor, method, matchedFromSms = true, occurredOn = smsDay)
            id
        }
        scheduler.requestSync()
        return id
    }

    private fun checkSettlement(fromMemberId: String, toMemberId: String, amountMinor: Long) {
        require(fromMemberId != toMemberId) { "a settlement needs two people" }
        require(amountMinor in 1..SplitMath.MAX_AMOUNT_MINOR) { "amount out of range" }
    }

    private fun today(): LocalDate = clock.instant().atZone(clock.zone).toLocalDate()

    /** Writes a new settlement and its outbox entry (inside a transaction). */
    private suspend fun storeSettlement(
        id: String,
        groupId: String,
        fromMemberId: String,
        toMemberId: String,
        amountMinor: Long,
        method: SettleMethod,
        matchedFromSms: Boolean,
        occurredOn: LocalDate,
    ) {
        val group = groups.getGroup(groupId) ?: throw IllegalArgumentException("unknown group")
        val me = group.myMemberId
        require(me == fromMemberId || me == toMemberId) { "only a party can record a settlement" }
        val other = groups.getMember(if (me == fromMemberId) toMemberId else fromMemberId)
        // The server confirms at once when the other party is not on the app, or the receiver matched it to an SMS.
        val confirmedAt = if (other?.onApp != true || (matchedFromSms && me == toMemberId)) clock.instant() else null
        val settlement = SettlementEntity(
            id, groupId, fromMemberId, toMemberId, amountMinor, method, matchedFromSms,
            occurredOn, me, confirmedAt, deleted = false, version = 0, syncState = SyncState.PENDING,
        )
        groups.upsertSettlement(settlement)
        outbox.append(
            groupId, EntityKind.SETTLEMENT, id, OutboxOp.CREATE, "PUT", "/v1/groups/$groupId/settlements/$id",
            ApiJson.encodeToJsonElement(SettlementInputDto.serializer(), settlement.toInput()), null,
        )
    }

    /** Confirms a settlement the other party recorded. */
    suspend fun confirmSettlement(id: String) {
        transactions.run {
            val settlement = groups.getSettlement(id) ?: return@run
            if (settlement.confirmedAt != null || settlement.deleted) return@run
            groups.upsertSettlement(settlement.copy(confirmedAt = clock.instant(), syncState = SyncState.PENDING))
            outbox.append(
                settlement.groupId, EntityKind.SETTLEMENT, id, OutboxOp.CONFIRM, "POST", "/v1/groups/${settlement.groupId}/settlements/$id/confirm",
                JsonObject(emptyMap()), RestoreSnapshot(settlement = settlement.toDto()),
            )
        }
        scheduler.requestSync()
    }

    /**
     * Deletes settlement [id] for everyone (docs/server.md 4.8): allowed to its recorder, `from` or `to`
     * ([canBeDeletedBy]), so the owner who imported a Splitwise payment between two friends can remove a wrong one
     * (4.17). Queued; a refusal puts it back.
     *
     * @throws IllegalArgumentException when the person may not delete it.
     */
    suspend fun deleteSettlement(id: String) {
        transactions.run {
            val settlement = groups.getSettlement(id) ?: return@run
            if (settlement.deleted) return@run
            val group = groups.getGroup(settlement.groupId) ?: return@run
            require(settlement.canBeDeletedBy(group.myMemberId)) { "not allowed to delete this settlement" }
            groups.upsertSettlement(settlement.copy(deleted = true, syncState = SyncState.PENDING))
            outbox.append(
                settlement.groupId, EntityKind.SETTLEMENT, id, OutboxOp.DELETE, "DELETE", "/v1/groups/${settlement.groupId}/settlements/$id",
                null, RestoreSnapshot(settlement = settlement.toDto()),
            )
        }
        scheduler.requestSync()
    }

    // ---- Import from Splitwise (docs/splitwise-import.md 5.3) ----

    /**
     * Imports a read Splitwise [file] as a new group named [groupName], with column [me] as the person
     * ([ImportPlanner]). Offline-first: in one transaction the group, its members (friends without the app, names
     * only), every expense with its shares and every payment land in Room as waiting, with the person's months kept
     * right (9.4 mirrors, or their own matching payment linked), followed in the outbox by the group's create and then
     * one `IMPORT` entry per part. [fileHash] is kept on this phone only, for "already here". Signed in only.
     *
     * @return the new group's id.
     * @throws IllegalArgumentException when [groupName] is blank once cleaned or [me] is not a column.
     * @throws IllegalStateException when nobody is signed in.
     */
    suspend fun importSplitwise(file: SplitwiseFile, me: Int, groupName: String, fileHash: String): String {
        val user = signedInUser()
        val now = clock.instant()
        val plan = ImportPlanner.plan(file, me, groupName, user, today(), now, newId, categoryName)
        val group = plan.group
        val groupId = group.id
        transactions.run {
            groups.upsertGroup(group)
            groups.upsertMembers(plan.members)
            for (row in plan.expenses) {
                groups.upsertExpense(row.expense)
                groups.insertShares(row.shares)
            }
            plan.settlements.forEach { groups.upsertSettlement(it) }
            plan.expenses.forEach { mirrors.sync(it.expense.id) }
            outbox.append(
                groupId, EntityKind.GROUP, groupId, OutboxOp.CREATE, "POST", "/v1/groups",
                GroupBodies.create(groupId, GroupKind.GROUP.apiName, group.name, group.myMemberId, plan.friends),
                restore = null,
            )
            for (part in plan.parts) {
                outbox.append(groupId, EntityKind.IMPORT, part.partId, OutboxOp.CREATE, "POST", "/v1/groups/$groupId/import", part.body, restore = null)
            }
            extras.upsertImport(
                ImportEntity(
                    groupId = groupId,
                    fileHash = fileHash,
                    groupName = group.name.orEmpty(),
                    expenseCount = plan.expenseCount,
                    itemCount = plan.itemCount,
                    sentItems = 0,
                    state = ImportState.SENDING,
                    createdAt = now,
                ),
            )
        }
        scheduler.requestSync()
        return groupId
    }

    /**
     * How the import of group [groupId] stands (Importing, 138 and 139), emitted again as parts are sent; null when no
     * import made this group (or the person signed out).
     */
    fun observeImport(groupId: String): Flow<ImportProgress?> =
        combine(extras.observeImport(groupId), groups.observeGroup(groupId), outbox.observeStatus(groupId)) { row, group, entries ->
            row?.let {
                ImportProgress(
                    groupId = groupId,
                    groupName = group?.name ?: it.groupName,
                    sent = it.sentItems,
                    total = it.itemCount,
                    state = it.state,
                    problem = entries.any { entry -> entry.failures > 0 && entry.lastErrorCode != Outbox.OFFLINE },
                )
            }
        }.distinctUntilChanged()

    /**
     * The newest import of the file with [hash] whose group is still here and did not fail, or null (137). Its day
     * ("on 18 Nov") is India's ([AppContainer.ZONE]), whatever zone the phone or [clock] is set to.
     */
    suspend fun importedFile(hash: String): PastImport? {
        val row = extras.latestImportByHash(hash) ?: return null
        val group = groups.getGroup(row.groupId) ?: return null
        return PastImport(row.groupId, group.name ?: row.groupName, row.createdAt.atZone(AppContainer.ZONE).toLocalDate(), row.expenseCount)
    }

    /** Group [groupId] as Group imported shows it (31): its name, the expenses that came, and its friends; null once gone. */
    fun observeImported(groupId: String): Flow<ImportedGroup?> =
        combine(groups.observeGroup(groupId), groups.observeMembers(), extras.observeImport(groupId)) { group, members, row ->
            group ?: return@combine null
            val mine = members.filter { it.member.groupId == groupId }
            ImportedGroup(
                groupId = groupId,
                groupName = group.name.orEmpty(),
                expenseCount = row?.expenseCount ?: 0,
                friends = mine
                    .filter { it.member.id != group.myMemberId && !it.member.onApp && !it.member.removed }
                    .sortedBy { it.storedOrder }
                    .map { it.member },
                memberNames = mine.map { it.member.name },
            )
        }.distinctUntilChanged()

    // ---- Links, invites, reminders (online) ----

    /**
     * The pay link for [memberId] in [groupId] (Remind on WhatsApp, 03/05). The phone reuses its link, and makes a
     * new one (revoking the old) once its link is over [renewAfter] old, so a reminder never carries one about to
     * expire. The id and token are made here and stored before sending, so a lost answer is simply sent again. A
     * family's invite link lives 7 days and is renewed after 3 ([FamilyRepository.inviteLink]); a group's lives 90
     * days and is renewed after 60.
     *
     * While a Splitwise import of the group still waits to be sent, no link is made and the answer is the error
     * `import_pending` (not from the server): a friend who joined then would make the remaining parts fail
     * (docs/splitwise-import.md decision 10).
     *
     * @return the link (`https://<LINK_HOST>/pay/<token>`).
     */
    suspend fun shareLink(
        groupId: String,
        memberId: String,
        renewAfter: Duration = LINK_RENEW_AFTER,
        lifetime: Duration = LINK_LIFETIME,
    ): ApiResult<String> {
        if (outbox.hasImportPart(groupId)) return ApiResult.Error(ApiError(IMPORT_PENDING_STATUS, IMPORT_PENDING, fromServer = false))
        val now = clock.instant()
        val stored = extras.linkFor(memberId)?.takeIf { it.groupId == groupId }
        val reusable = stored?.takeIf { Duration.between(it.createdAt, now) < renewAfter }
        val link = reusable ?: freshLink(groupId, memberId, now, lifetime, revoke = stored)
        val sent = api.putLink(groupId, memberId, link.linkId, link.token)
        if (reusable != null && sent is ApiResult.Error && sent.error.code == LINK_NOT_FOUND) {
            // The stored link was turned off elsewhere (another phone's Turn off link, an undo-join, the friend
            // joined): forget it and make a new one, once.
            val fresh = freshLink(groupId, memberId, now, lifetime, revoke = null)
            return api.putLink(groupId, memberId, fresh.linkId, fresh.token).map { "https://$linkHost/pay/${fresh.token}" }
        }
        return sent.map { "https://$linkHost/pay/${link.token}" }
    }

    /** A new link for [memberId], stored before it is sent; [revoke] is turned off on the server first (best effort). */
    private suspend fun freshLink(groupId: String, memberId: String, now: Instant, lifetime: Duration, revoke: LinkEntity?): LinkEntity {
        val fresh = LinkEntity(memberId, groupId, newId(), newToken(), now, now.plus(lifetime))
        extras.upsertLink(fresh)
        if (revoke != null) api.deleteLink(groupId, revoke.linkId, newId())
        return fresh
    }

    /**
     * The Join group preview of an invite link's [token]; for a family link ([JoinPreview.kind] [GroupKind.FAMILY]),
     * Join Family's (docs/family.md 1.4).
     */
    suspend fun previewInvite(token: String): ApiResult<JoinPreview> = api.invite(token).map {
        JoinPreview(
            it.groupId, it.groupName.orEmpty(), it.inviterName, it.memberNames, it.memberCount, it.youOwePaise, it.owedToYouPaise,
            it.expensesThisMonth, it.alreadyMember, kind = groupKindOf(it.kind), alreadyInFamily = it.alreadyInFamily,
        )
    }

    /**
     * Joins the group of an invite link: the person takes the friend's place. Returns the group id. A family link
     * the person may not take because they are in another family is refused with the error
     * [FamilyRepository.ALREADY_IN_FAMILY]; after joining a family, the sync pulls it and shares the earlier Family
     * rows.
     */
    suspend fun acceptInvite(token: String): ApiResult<String> {
        val result = api.acceptInvite(token, newId()).map { it.groupId }
        if (result is ApiResult.Ok) scheduler.requestSync()
        return result
    }

    /** Reminds [memberIds] (app users who owe the person) by push. */
    suspend fun remind(groupId: String, memberIds: List<String>): ApiResult<RemindResult> =
        api.remind(groupId, memberIds, newId()).map { answer -> RemindResult(answer.sent, answer.skipped.associate { it.memberId to it.reason }) }

    // ---- Helpers ----

    private suspend fun signedInUser(): UserDto = currentUser() ?: throw IllegalStateException("not signed in")

    private suspend fun online(result: ApiResult<Unit>, onDone: suspend () -> Unit): ActionResult = when (result) {
        is ApiResult.Ok -> {
            onDone()
            scheduler.requestSync()
            ActionResult.Done
        }
        is ApiResult.Error -> actionError(result.error)
    }

    private fun actionError(error: ApiError): ActionResult = when {
        error.code == ApiError.SIGNED_OUT -> ActionResult.SignedOut
        error.isOffline -> ActionResult.Offline
        error.code == "balances_not_settled" -> ActionResult.NotSettled
        else -> ActionResult.Failed(error.code)
    }

    /** Writes an expense and its shares as given (inside a transaction). */
    private suspend fun storeLocal(expense: GroupExpenseEntity, shares: Map<String, Long>, inputs: Map<String, Long?>): GroupExpenseWithShares {
        groups.upsertExpense(expense)
        groups.deleteShares(expense.id)
        val rows = shares.map { (member, share) -> ExpenseShareEntity(expense.id, member, share, inputs[member]) }
        groups.insertShares(rows)
        return GroupExpenseWithShares(expense, rows)
    }

    /** Tombstones an expense, queues the delete, and puts the personal rows right (inside a transaction). */
    private suspend fun deleteLocally(id: String) {
        val before = groups.getExpenseWithShares(id) ?: return
        if (before.expense.deleted) return
        val personal = expenses.findByGroupExpense(id)?.let { mirrors.snapshotOf(it) }
        groups.upsertExpense(before.expense.copy(deleted = true, syncState = SyncState.PENDING))
        groups.deleteShares(id)
        mirrors.sync(id)
        personal?.let { family?.onChanged(listOf(it.expenseId)) }
        outbox.append(
            before.expense.groupId, EntityKind.EXPENSE, id, OutboxOp.DELETE, "DELETE", expensePath(before.expense.groupId, id), null,
            RestoreSnapshot(expense = before.toDto(), personal = personal),
        )
    }

    private fun newMember(groupId: String, person: NewPerson): Pair<MemberEntity, MemberBody> {
        val id = newId()
        return when (person) {
            is NewPerson.KnownUser -> MemberEntity(id, groupId, person.userId, person.name, null, null, onApp = true, removed = false, version = 0) to
                MemberBody.User(id, person.userId)
            is NewPerson.Friend -> {
                val name = GroupText.clean(person.name, GroupText.MAX_PERSON_NAME)
                require(name.isNotEmpty()) { "name is blank" }
                val phone = GroupText.normalizePhone(person.phone)
                MemberEntity(id, groupId, null, name, phone, null, onApp = false, removed = false, version = 0) to MemberBody.Friend(id, name, phone)
            }
        }
    }

    private fun myMember(groupId: String, memberId: String, me: UserDto) =
        MemberEntity(memberId, groupId, me.id, me.name, me.phone, me.upiId, onApp = true, removed = false, version = 0)

    /**
     * [LoadState.LOADING] only until the first page of the group's first pull is in. `latestVersion` is raised before
     * every pull (and after a pushed rename), so "behind the server" would also cover each later pull, and a pull cut
     * off offline would hide the stored balances until the next sync.
     */
    private fun loadState(group: GroupEntity, imported: Boolean): LoadState = when {
        group.syncState == SyncState.PENDING && group.latestVersion == 0L -> LoadState.READY
        group.loadFailed -> LoadState.FAILED
        // A group imported on this phone holds all its data from the start: its first pull may wait for minutes while
        // the import's parts are sent (429 waits), and the page must not show placeholders meanwhile.
        group.version == 0L && group.latestVersion > 0L && !imported -> LoadState.LOADING
        else -> LoadState.READY
    }

    private fun displayName(group: GroupEntity, members: List<MemberEntity>): String =
        if (group.kind != GroupKind.DIRECT) group.name.orEmpty() else members.firstOrNull { it.id != group.myMemberId }?.name.orEmpty()

    private fun currentUserId(data: Snapshot, group: GroupEntity): String? =
        data.members.firstOrNull { it.id == group.myMemberId }?.userId

    private fun expensePath(groupId: String, id: String) = "/v1/groups/$groupId/expenses/$id"

    private fun encode(input: ExpenseInputDto) = ApiJson.encodeToJsonElement(ExpenseInputDto.serializer(), input)

    private fun encodeInputs(inputs: Map<String, Long?>): String = ApiJson.encodeToString(INPUTS, inputs)

    private fun decodeInputs(json: String): Map<String, Long?> = runCatching { ApiJson.decodeFromString(INPUTS, json) }.getOrDefault(emptyMap())

    /** 18 random bytes as base64url: 24 characters, 144 bits (4.10). */
    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Constants, and the id of a settlement matched from SMS. */
    companion object {
        private val INPUTS = MapSerializer(String.serializer(), Long.serializer().nullable)
        private val LINK_RENEW_AFTER: Duration = Duration.ofDays(60)
        private val LINK_LIFETIME: Duration = Duration.ofDays(90)
        private const val TOKEN_BYTES = 18
        private const val UNDO_EXPIRED = "undo_expired"
        private const val NOT_JOINED_BY_LINK = "not_joined_by_link"
        private const val NOT_OWNER = "not_owner"
        private const val NOT_MEMBER = "not_member"
        private const val GROUP_DELETED = "group_deleted"
        private const val LINK_NOT_FOUND = "link_not_found"
        private const val IMPORT_PENDING_STATUS = 409

        /** The error [shareLink] gives while the group's import waits to be sent (made on the phone, never sent). */
        const val IMPORT_PENDING: String = "import_pending"

        /** How many equal SMS settlements of one pair, amount and day [recordSmsSettlement] looks through. */
        private const val MAX_SMS_SETTLEMENTS_A_DAY = 100

        /**
         * The id of the [n]-th settlement matched from SMS in which [fromMemberId] paid [toMemberId] [amountMinor] in
         * [groupId] on [smsDate] (docs/server.md 9.5): a name-based UUID (version 3, lower case) of
         * `sms-settlement/{groupId}/{fromMemberId}/{toMemberId}/{amountPaise}/{smsDate}`, with `/{n}` added for n ≥ 1.
         * Both phones of a payment work it out the same way, so the server keeps one settlement.
         */
        fun smsSettlementId(groupId: String, fromMemberId: String, toMemberId: String, amountMinor: Long, smsDate: LocalDate, n: Int = 0): String {
            val base = "sms-settlement/$groupId/$fromMemberId/$toMemberId/$amountMinor/$smsDate"
            val name = if (n == 0) base else "$base/$n"
            return UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
        }
    }
}
