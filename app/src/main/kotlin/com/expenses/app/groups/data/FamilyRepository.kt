package com.expenses.app.groups.data

import com.expenses.app.data.db.ExpenseDao
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.data.model.FamilyLink
import com.expenses.app.data.repository.TransactionRunner
import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.GroupBodies
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.sync.LocalGroupData
import com.expenses.app.groups.sync.SyncScheduler
import com.expenses.app.ui.components.AvatarColors
import com.expenses.app.ui.components.SelfAvatarColor
import com.expenses.app.ui.format.shortName
import com.expenses.splits.SplitMath
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * The family on this phone (docs/family.md 8.1): a pooled month of two people with no owing, stored as a group of kind
 * [GroupKind.FAMILY]. Reads come from Room only and emit again on every change. Setting up, inviting, removing and
 * leaving need the server at once and say so in their result (decision 20); renaming is queued and works offline.
 * Which payments are shared is [FamilyLinker]'s job.
 *
 * @param zone India time: family dates and months are India's, as the server's.
 * @param newId makes ids (UUID v4) and idempotency keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FamilyRepository(
    private val api: GroupsApi,
    private val transactions: TransactionRunner,
    private val groups: GroupDao,
    private val expenses: ExpenseDao,
    private val linker: FamilyLinker,
    private val groupsRepository: GroupsRepository,
    private val local: LocalGroupData,
    private val scheduler: SyncScheduler,
    private val currentUser: suspend () -> UserDto?,
    private val clock: Clock,
    private val zone: ZoneId,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    // ---- Reads ----

    /**
     * The family on this phone, or null when there is none (Home's "Set up Family" card, You's "Share a monthly total
     * with your partner"). [FamilyInfo.state] says whether an invite is out, the person is alone, or the partner is in.
     */
    fun observeFamily(): Flow<FamilyInfo?> = groups.observeFamilyGroup().distinctUntilChanged().flatMapLatest { family ->
        if (family == null) {
            flowOf(null)
        } else {
            combine(groups.observeMembersOf(family.id), avatarColors()) { members, colors -> infoOf(family, members, colors) }
        }
    }.distinctUntilChanged()

    /**
     * The family month of [month] (Home's Family side with a family, Month view (Family)), or null when there is no
     * family on this phone. See [FamilyMonth].
     */
    fun observeMonth(month: YearMonth): Flow<FamilyMonth?> = groups.observeFamilyGroup().distinctUntilChanged().flatMapLatest { family ->
        if (family == null) {
            flowOf(null)
        } else {
            combine(
                groups.observeFamilyExpenses(family.id, month.minusMonths(1).atDay(1), month.plusMonths(1).atDay(1)),
                groups.observeMembersOf(family.id),
                expenses.observeFamilyLinks(),
                avatarColors(),
            ) { rows, members, links, colors -> monthOf(month, family, rows, members, links, colors) }
        }
    }.distinctUntilChanged()

    /**
     * How many of this phone's Family expenses setting up or joining a family would share now: Set up Family's and
     * Join Family's "Your 12 Family expenses on this phone are shared too." (shown only above 0; decision 3).
     */
    fun observeShareableEarlier(): Flow<Int> {
        val today = clock.instant().atZone(zone).toLocalDate()
        return expenses.observeShareableFamilyCount(
            maxMinor = SplitMath.MAX_AMOUNT_MINOR,
            from = EARLIEST_DATE.atStartOfDay(zone).toInstant(),
            to = today.plusDays(2).atStartOfDay(zone).toInstant(),
        ).distinctUntilChanged()
    }

    // ---- Online actions ----

    /**
     * Sets up a family named [name] with the person as owner and a placeholder for the partner (Set up Family's
     * "Invite partner", step 1 of 2: then [inviteLink]). On success the family is stored as complete, and this phone's
     * earlier Family expenses are queued to be shared.
     *
     * @return the family's id; [FamilyResult.AlreadyInFamily] when the person already has one (on this phone, or the
     * server says so: a sync is asked for); [FamilyResult.Offline], [FamilyResult.SignedOut] or
     * [FamilyResult.Failed] when nothing was made.
     * @throws IllegalArgumentException when [name] is blank once cleaned ("That does not look right.").
     */
    suspend fun create(name: String): FamilyResult<String> {
        val cleanName = GroupText.clean(name, GroupText.MAX_GROUP_NAME)
        require(cleanName.isNotEmpty()) { "family name is blank" }
        val me = currentUser() ?: return FamilyResult.SignedOut
        if (groups.familyGroup() != null) return FamilyResult.AlreadyInFamily
        val id = newId()
        val myMemberId = newId()
        val placeholderId = newId()
        val answer = api.createGroup(GroupBodies.createFamily(id, cleanName, myMemberId, placeholderId), newId())
        val created = when (answer) {
            is ApiResult.Ok -> answer.value
            is ApiResult.Error -> {
                if (answer.error.code == ALREADY_IN_FAMILY) scheduler.requestSync()
                return failure(answer.error)
            }
        }
        val group = created.group
        transactions.run {
            groups.upsertGroup(
                GroupEntity(
                    id = id,
                    kind = GroupKind.FAMILY,
                    name = group.name ?: cleanName,
                    ownerUserId = group.ownerUserId ?: me.id,
                    myMemberId = myMemberId,
                    version = group.version,
                    latestVersion = group.version,
                    syncState = SyncState.SYNCED,
                    createdAt = if (group.createdAt > 0) Instant.ofEpochMilli(group.createdAt) else clock.instant(),
                    ownerMemberId = group.ownerMemberId ?: myMemberId,
                ),
            )
            val members = created.members.map { it.toEntity() }.ifEmpty {
                listOf(
                    MemberEntity(myMemberId, id, me.id, me.name, me.phone, me.upiId, onApp = true, removed = false, version = group.version),
                    MemberEntity(placeholderId, id, null, GroupBodies.PLACEHOLDER_NAME, null, null, onApp = false, removed = false, version = group.version),
                )
            }
            groups.upsertMembers(members)
            linker.reconcile()
        }
        scheduler.requestSync()
        return FamilyResult.Ok(id)
    }

    /**
     * The partner's invite link (`https://<LINK_HOST>/pay/<token>`; Invite partner, Send link again): the link of the
     * placeholder, made again when it is over 3 days old (it lives 7 days, decision 12). With no placeholder (the
     * partner left, or the invite was cancelled) a new one is added first. A partner who deleted their account left a
     * "Former member" place that still counts toward the 2 members: it is removed first (its links ended with the
     * account), so the invite gets a placeholder of its own. Needs the server at once.
     *
     * @return the link; [FamilyResult.Failed] with `member_on_app` or `limit_reached` when the partner joined
     * meanwhile (a sync was asked for), or [NO_FAMILY] when there is no family.
     */
    suspend fun inviteLink(): FamilyResult<String> {
        val family = groups.familyGroup() ?: return FamilyResult.Failed(NO_FAMILY)
        val active = groups.membersOf(family.id).filter { !it.removed && it.id != family.myMemberId }
        var placeholder = active.firstOrNull(::isInvitePlace)
        if (placeholder == null) {
            if (active.any { it.onApp }) return FamilyResult.Failed(MEMBER_ON_APP)
            for (former in active) {
                when (val removed = api.removeMember(family.id, former.id, newId())) {
                    is ApiResult.Ok -> transactions.run { groups.upsertMembers(listOf(former.copy(removed = true))) }
                    is ApiResult.Error -> return failed(family.id, removed.error)
                }
            }
            placeholder = when (val added = api.addMember(family.id, GroupBodies.placeholder(newId()), newId())) {
                is ApiResult.Ok -> added.value.toEntity().also { member -> transactions.run { groups.upsertMembers(listOf(member)) } }
                is ApiResult.Error -> return failed(family.id, added.error)
            }
        }
        return when (val link = groupsRepository.shareLink(family.id, placeholder.id, LINK_RENEW_AFTER, LINK_LIFETIME)) {
            is ApiResult.Ok -> FamilyResult.Ok(link.value)
            is ApiResult.Error -> failed(family.id, link.error)
        }
    }

    /**
     * Joins the family of invite link [token] (Join Family's "Join Family"; docs/family.md 1.4), named [familyName] as
     * its preview said. Needs the server at once. On success the family is on this phone at once, [LoadState.LOADING]
     * until the sync that is asked for has pulled it (Home's placeholders); that sync then shares this phone's earlier
     * Family expenses.
     *
     * @return the family's id; [FamilyResult.AlreadyInFamily] for `409 already_in_family`; [FamilyResult.Failed] with
     * `link_not_found` or `already_claimed` when the link no longer works ("This link no longer works"), or with
     * `already_member` when the person is in this family already (joined on another phone): a sync is asked for, so the
     * family comes to this phone as after a join, and the caller opens it as joined.
     */
    suspend fun join(token: String, familyName: String): FamilyResult<String> {
        val accepted = when (val answer = api.acceptInvite(token, newId())) {
            is ApiResult.Ok -> answer.value
            is ApiResult.Error -> {
                if (answer.error.code == ALREADY_MEMBER) scheduler.requestSync()
                return failure(answer.error)
            }
        }
        transactions.run {
            if (groups.getGroup(accepted.groupId) == null) {
                groups.upsertGroup(
                    GroupEntity(
                        id = accepted.groupId,
                        kind = GroupKind.FAMILY,
                        name = GroupText.clean(familyName, GroupText.MAX_GROUP_NAME),
                        ownerUserId = null,
                        myMemberId = accepted.memberId,
                        version = 0,
                        latestVersion = maxOf(accepted.version, 1),
                        syncState = SyncState.SYNCED,
                        createdAt = clock.instant(),
                    ),
                )
            }
        }
        scheduler.requestSync()
        return FamilyResult.Ok(accepted.groupId)
    }

    /**
     * Asks for a sync, so a family the person is in already comes to this phone: a family link's preview said they
     * are a member (joined on another phone), and Join Family opens Home's Family side at once.
     */
    fun refresh() {
        scheduler.requestSync()
    }

    /** Renames the family (Family settings' "Family name"): queued, works offline, last writer wins. Nothing without one. */
    suspend fun rename(name: String) {
        val family = groups.familyGroup() ?: return
        groupsRepository.renameGroup(family.id, name)
    }

    /**
     * Removes the partner [memberId] (the owner only; "Remove Meera from Home?"). Their family expenses leave the
     * family month at once; they stay on their phone. Needs the server at once.
     */
    suspend fun removePartner(memberId: String): ActionResult {
        val family = groups.familyGroup() ?: return ActionResult.Done
        val result = groupsRepository.removeMember(family.id, memberId)
        if (result == ActionResult.Done) transactions.run { groups.markDeletedPaidBy(family.id, memberId) }
        return result
    }

    /** Cancels the invite: the placeholder [memberId] goes and its link stops working ("Cancel invite"). Needs the server. */
    suspend fun cancelInvite(memberId: String): ActionResult {
        val family = groups.familyGroup() ?: return ActionResult.Done
        return groupsRepository.removeMember(family.id, memberId)
    }

    /**
     * Leaves the family ("Leave Family"): the person's family expenses leave it and stay on this phone, still tagged
     * Family; the family leaves this phone. Needs the server at once. A family the server says is already gone is
     * removed here too, and counts as done.
     */
    suspend fun leave(): ActionResult {
        val family = groups.familyGroup() ?: return ActionResult.Done
        return when (val result = api.leaveGroup(family.id, newId())) {
            is ApiResult.Ok -> {
                transactions.run {
                    linker.unlinkFamily(family.id)
                    local.removeOwnGroup(family.id)
                }
                scheduler.requestSync()
                ActionResult.Done
            }
            is ApiResult.Error -> when (result.error.code) {
                NOT_MEMBER, GROUP_DELETED -> {
                    transactions.run {
                        linker.unlinkFamily(family.id)
                        local.removeGroup(family.id)
                    }
                    ActionResult.Done
                }
                else -> actionResult(result.error)
            }
        }
    }

    // ---- Helpers ----

    private fun avatarColors(): Flow<AvatarColors> =
        combine(groups.observeGroups(), groups.observeMembers()) { all, members -> avatarColorsOf(all, members) }.distinctUntilChanged()

    private fun infoOf(family: GroupEntity, stored: List<StoredMember>, colors: AvatarColors): FamilyInfo {
        val members = stored.map { it.member }
        val names = members.map { it.name }
        fun person(member: MemberEntity) = FamilyMember(member.id, member.name, shortName(member.name, names), colors.ofMember(member.id))
        val active = members.filter { !it.removed }
        val meRow = members.firstOrNull { it.id == family.myMemberId }
        val me = meRow?.let(::person) ?: FamilyMember(family.myMemberId, "", "", SelfAvatarColor)
        val myUserId = meRow?.userId
        val ownerMemberId = family.ownerMemberId
            ?: active.firstOrNull { it.userId != null && it.userId == family.ownerUserId }?.id
        return FamilyInfo(
            groupId = family.id,
            name = family.name.orEmpty(),
            myMemberId = family.myMemberId,
            isOwner = ownerMemberId == family.myMemberId || (myUserId != null && myUserId == family.ownerUserId),
            me = me,
            partner = active.firstOrNull { it.id != family.myMemberId && it.onApp }?.let(::person),
            // Only the invite's own place: a partner who deleted their account stays as a "Former member" with no
            // link, which is "Invite your partner" (just you), not "Invite sent" (docs/family.md 1.5).
            placeholderId = active.firstOrNull { it.id != family.myMemberId && isInvitePlace(it) }?.id,
            load = familyLoadState(family),
        )
    }

    private fun monthOf(
        month: YearMonth,
        family: GroupEntity,
        rows: List<GroupExpenseEntity>,
        stored: List<StoredMember>,
        links: List<FamilyLink>,
        colors: AvatarColors,
    ): FamilyMonth {
        val info = infoOf(family, stored, colors)
        val start = month.atDay(1)
        val (thisMonth, lastMonth) = rows.partition { it.occurredOn >= start }
        val names = stored.map { it.member.name }
        val byId = stored.associate { it.member.id to it.member }
        val personal = links.associate { it.familyExpenseId to it.id }
        val all = thisMonth.map { row ->
            val mine = row.paidByMemberId == family.myMemberId
            FamilyRow(
                groupExpenseId = row.id,
                title = row.description,
                category = row.category,
                amountMinor = row.amountMinor,
                paidByMe = mine,
                paidByName = byId[row.paidByMemberId]?.name?.let { shortName(it, names) }.orEmpty(),
                occurredOn = row.occurredOn,
                personalExpenseId = if (mine) personal[row.id] else null,
            )
        }
        val people = listOfNotNull(info.me, info.partner).map { member ->
            val paid = thisMonth.filter { it.paidByMemberId == member.memberId }
            FamilyPersonTotal(member, isMe = member.memberId == family.myMemberId, count = paid.size, totalMinor = paid.sumOf { it.amountMinor })
        }
        val categories = thisMonth.groupBy { it.category }
            .map { (category, list) -> CategoryTotal(category, list.size, list.sumOf { it.amountMinor }) }
            .sortedWith(compareByDescending<CategoryTotal> { it.totalMinor }.thenBy { it.category.name })
        return FamilyMonth(
            month = month,
            familyName = family.name.orEmpty(),
            totalMinor = thisMonth.sumOf { it.amountMinor },
            count = thisMonth.size,
            people = people,
            topCategories = categories.take(TOP_CATEGORIES),
            latest = all.take(LATEST),
            lastMonthTotalMinor = lastMonth.sumOf { it.amountMinor },
            load = info.load,
            expenses = all,
            categories = categories,
        )
    }

    /**
     * True for the invite's place: a member not on the app with the fixed name every phone gives it
     * ([PLACEHOLDER_NAME]; a family's members cannot be renamed). Not for a partner who deleted their account, whose
     * place the server renamed "Former member" and whose links it turned off.
     */
    private fun isInvitePlace(member: MemberEntity): Boolean = !member.onApp && member.name == PLACEHOLDER_NAME

    /** [error] of a family action: a family the server says is gone leaves this phone. */
    private suspend fun failed(familyId: String, error: ApiError): FamilyResult<Nothing> {
        when (error.code) {
            NOT_MEMBER -> local.removeLostGroup(familyId, error.reason)
            GROUP_DELETED -> local.removeGroup(familyId)
            MEMBER_ON_APP, LIMIT_REACHED -> scheduler.requestSync()
        }
        return failure(error)
    }

    private fun failure(error: ApiError): FamilyResult<Nothing> = when {
        error.code == ApiError.SIGNED_OUT -> FamilyResult.SignedOut
        error.isOffline -> FamilyResult.Offline
        error.code == ALREADY_IN_FAMILY -> FamilyResult.AlreadyInFamily
        else -> FamilyResult.Failed(error.code)
    }

    private fun actionResult(error: ApiError): ActionResult = when {
        error.code == ApiError.SIGNED_OUT -> ActionResult.SignedOut
        error.isOffline -> ActionResult.Offline
        else -> ActionResult.Failed(error.code)
    }

    /** Constants of the family. */
    companion object {
        /** `409 already_in_family` (docs/family.md 3): the person is active in another family. Never queued. */
        const val ALREADY_IN_FAMILY: String = "already_in_family"

        /** `409 already_member` of an accept: the person is in this family already ([join]). */
        const val ALREADY_MEMBER: String = "already_member"

        /** The error [inviteLink] gives when there is no family on this phone (made on the phone, never sent). */
        const val NO_FAMILY: String = "no_family"

        /** The name of a family's placeholder member: never shown ([GroupBodies.PLACEHOLDER_NAME]). */
        const val PLACEHOLDER_NAME: String = GroupBodies.PLACEHOLDER_NAME

        /** How long a family invite link works (decision 12). */
        val LINK_LIFETIME: Duration = Duration.ofDays(7)

        /** After this, Send link again makes a new link rather than sending one about to expire. */
        val LINK_RENEW_AFTER: Duration = Duration.ofDays(3)

        private const val MEMBER_ON_APP = "member_on_app"
        private const val LIMIT_REACHED = "limit_reached"
        private const val NOT_MEMBER = "not_member"
        private const val GROUP_DELETED = "group_deleted"
        private const val TOP_CATEGORIES = 2
        private const val LATEST = 5
        private val EARLIEST_DATE: LocalDate = LocalDate.of(2000, 1, 1)
    }
}
