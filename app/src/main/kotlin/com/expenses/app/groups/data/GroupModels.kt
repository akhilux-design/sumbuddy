package com.expenses.app.groups.data

import com.expenses.app.data.category.Category
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.components.AvatarColors
import com.expenses.app.ui.components.AvatarGroup
import com.expenses.app.ui.components.AvatarPlace
import com.expenses.splits.SplitMethod
import java.time.Instant
import java.time.LocalDate

// Read models and inputs of GroupsRepository (docs/server.md 14 C). Money in paise; a positive balance means the
// person is owed.

/** Whether a group's data is complete on this phone (02/21-23). */
enum class LoadState {
    /**
     * Nothing of the group has been pulled yet (Join group, a new phone, signing in again): balances are not shown. A
     * later pull keeps what the phone has on show, also when it is cut off.
     */
    LOADING,

    /** Complete. */
    READY,

    /** The last pull failed ("Could not load"); "Nothing is lost". */
    FAILED,
}

/**
 * A row of Groups (02/20).
 *
 * @param id the group.
 * @param name its name.
 * @param memberCount active people in it, the person included.
 * @param myNetMinor the person's net in it: positive when the group owes them.
 * @param load whether its data is complete.
 * @param avatar the colour behind its initial ([AvatarColors.ofGroup]).
 */
data class GroupSummary(
    val id: String,
    val name: String,
    val memberCount: Int,
    val myNetMinor: Long,
    val load: LoadState,
    val avatar: AvatarColor,
)

/**
 * What one friend owes the person in one group for some of the person's split payments (the card bill view,
 * docs/card-bills.md 2.1): one per (group, other member with a share above 0) of those payments.
 *
 * @param groupId the group or direct split.
 * @param groupName the group's name; null for a direct split.
 * @param memberId the friend's member in that group.
 * @param personKey the friend across groups ([personKeyOf]).
 * @param name the friend's name.
 * @param onApp true when the friend is on the app (a push can remind them).
 * @param removed true when the friend was removed from the group; such a part never counts.
 * @param avatar the colour behind their initial ([AvatarColors.ofPerson]).
 * @param shareMinor the sum of their shares of those payments, in paise.
 * @param expenseCount how many of those payments they have a share of.
 * @param pairMinor what they owe the person in that group now ([GroupBalance.pairBalance]); negative when the person
 * owes them.
 */
data class OwedPart(
    val groupId: String,
    val groupName: String?,
    val memberId: String,
    val personKey: String,
    val name: String,
    val onApp: Boolean,
    val removed: Boolean,
    val avatar: AvatarColor,
    val shareMinor: Long,
    val expenseCount: Int,
    val pairMinor: Long,
)

/**
 * One person under Friends (02/20): the sum of the person's transfers with them over every group (6.4).
 *
 * @param key the person across groups ([personKeyOf]).
 * @param name their name.
 * @param userId their user id when on the app.
 * @param phone their phone number, or null.
 * @param netMinor positive when they owe the person, negative when the person owes them.
 * @param avatar the colour behind their initial ([AvatarColors.ofPerson]).
 * @param directGroupId the direct split with them on this phone (Friends row tap opens its Group page), or null when
 * every balance with them is in named groups.
 */
data class FriendBalance(
    val key: String,
    val name: String,
    val userId: String?,
    val phone: String?,
    val netMinor: Long,
    val avatar: AvatarColor,
    val directGroupId: String? = null,
)

/**
 * The person's totals over every group and direct split (6.4, Home "Owed to you" / "You owe").
 *
 * @param owedToMeMinor the sum of the person's positive nets.
 * @param iOweMinor the sum of the person's negative nets, as a positive amount.
 * @param overallMinor the sum of every net ("OVERALL YOU ARE OWED").
 * @param groupCount named groups listed ("across N groups").
 */
data class OwedTotals(val owedToMeMinor: Long, val iOweMinor: Long, val overallMinor: Long, val groupCount: Int)

/**
 * What the Groups tab lists (02/20), all from one read of this phone's data.
 *
 * @param groups the named groups.
 * @param friends the people with a balance, over every group.
 * @param totals the person's totals.
 * @param stored what this phone holds of the person's groups and direct splits, listed or not (a settled split with
 * one friend is not listed).
 */
data class GroupsHome(val groups: List<GroupSummary>, val friends: List<FriendBalance>, val totals: OwedTotals, val stored: StoredGroups) {
    /** True when the tab has no row to show: no named group, and no friend with a balance. */
    val nothingToList: Boolean get() = groups.isEmpty() && friends.isEmpty()
}

/** What this phone holds of the person's groups and direct splits ([GroupsHome.stored]). */
enum class StoredGroups {
    /** None. */
    NONE,

    /** Some, and the data of every one is complete ([LoadState.READY]): a split with one friend that lists nothing is settled. */
    COMPLETE,

    /**
     * Some, and the data of at least one is not complete ([LoadState.LOADING] or [LoadState.FAILED]): its first pull has
     * not ended, or its last pull failed. A split with one friend that lists nothing may then still have a balance.
     */
    INCOMPLETE,
}

/**
 * A member on the Group page with the balance between them and the person.
 *
 * @param member the member.
 * @param isMe true for the person.
 * @param pairMinor positive when they owe the person, negative when the person owes them, 0 when no transfer joins
 * them.
 * @param netMinor their own net in the group.
 * @param avatar the colour behind their initial ([AvatarColors.ofMember]): the same on every screen and in every
 * group, and the person's own colour for the person.
 */
data class MemberBalance(val member: MemberEntity, val isMe: Boolean, val pairMinor: Long, val netMinor: Long, val avatar: AvatarColor)

/**
 * The Group page (02/24).
 *
 * @param group the group row.
 * @param name its name (for a direct split, the other person's name).
 * @param members active members first, each with the balance to the person.
 * @param expenses expenses that are not deleted, newest first.
 * @param settlements settlements that are not deleted, newest first.
 * @param myNetMinor the person's net.
 * @param isOwner true when the person may delete the group or remove people on the app.
 * @param load whether its data is complete.
 * @param ownerMemberId the owner's member (Group settings' "Owner" tag, 45's "{Owner} is the owner"): the stored
 * `GroupEntity.ownerMemberId`, or, while that is still empty (a group not pulled since server migration 0002), the
 * active member who is `GroupEntity.ownerUserId`. Null when the owner is no longer in the group. Use this, never
 * `group.ownerMemberId`.
 */
data class GroupDetail(
    val group: GroupEntity,
    val name: String,
    val members: List<MemberBalance>,
    val expenses: List<GroupExpenseWithShares>,
    val settlements: List<SettlementEntity>,
    val myNetMinor: Long,
    val isOwner: Boolean,
    val load: LoadState,
    val ownerMemberId: String? = null,
)

/**
 * The joins by a friend's link that the person, as the group's owner, may still undo at [now] (the banner "{name}
 * joined · Not {name}? Undo", docs/server.md 4.6 and product.md "Forwarded links"): members on the app, not removed,
 * not the person, whose [MemberEntity.undoUntil] is after [now]; newest join first. Empty unless the person owns the
 * group. Decided from the phone's own data and clock: a pulled row is not sent again when time passes or when the
 * owner changes, so the server's `canUndo` is never stored.
 */
fun GroupDetail.joinsToUndo(now: Instant): List<MemberEntity> {
    if (!isOwner) return emptyList()
    val myUserId = members.firstOrNull { it.isMe }?.member?.userId ?: return emptyList()
    return members.map { it.member }
        .filter { it.onApp && !it.removed && it.userId != null && it.userId != myUserId && it.undoUntil?.isAfter(now) == true }
        .sortedByDescending { it.joinedAt }
}

/**
 * What a split payment is part of (Activity, Home's latest rows, Expense detail).
 *
 * @param groupExpenseId the group expense.
 * @param groupId its group.
 * @param groupKind a named group or a direct split.
 * @param groupName the group's name; for a direct split, the other person's name.
 * @param method how it is divided.
 * @param totalMinor the whole expense in paise ("your share of ₹450").
 * @param myShareMinor the person's share in paise.
 * @param paidByMe true when the person paid.
 * @param paidByName the payer's name.
 * @param notSent true while the expense's latest change waits in the outbox.
 */
data class SplitLabel(
    val groupExpenseId: String,
    val groupId: String,
    val groupKind: GroupKind,
    val groupName: String,
    val method: SplitMethod,
    val totalMinor: Long,
    val myShareMinor: Long,
    val paidByMe: Boolean,
    val paidByName: String,
    val notSent: Boolean,
)

/**
 * Someone the person already splits with (New group, Choose people).
 *
 * @param key the same key as [FriendBalance.key].
 * @param name their name.
 * @param userId their user id when on the app.
 * @param phone their phone, or null.
 */
data class Person(val key: String, val name: String, val userId: String?, val phone: String?) {
    /** True for an app user. */
    val onApp: Boolean get() = userId != null
}

/**
 * Who [member] is across groups ([Person.key], [FriendBalance.key]): their user id when on the app, else their phone
 * number, else "name:" and their name in lower case. A friend added by name only is known only by name, so the same
 * friend in two groups is one person (two such friends with the same name are one person too).
 */
fun personKeyOf(member: MemberEntity): String = member.userId ?: member.phone ?: ("name:" + member.name.trim().lowercase())

/**
 * The avatar colours of everyone and every group on this phone ([AvatarColors], which has the rule): groups oldest
 * first (creation time, then id), each group's people in the order their rows were first stored.
 *
 * A person is one across groups by [personKeyOf]. Someone on the app is also known by their phone number, so a friend
 * added by phone in one group and on the app in another keeps one colour. The person's own places are their member in
 * each group, and any other row that is their user.
 *
 * A family (docs/family.md 8.2) comes after every group and direct split whatever its age, so it changes no other
 * colour: its partner takes the colour they already have in a group, or the next one after everyone else's. Its
 * places not on the app (the invite placeholder) take no colour. A family is not named, so it takes no group colour.
 *
 * @param groups every group, direct split and family on this phone, in any order.
 * @param members every member of them, each with its stored order, in any order.
 */
fun avatarColorsOf(groups: List<GroupEntity>, members: List<StoredMember>): AvatarColors {
    val mine = groups.mapTo(HashSet()) { it.myMemberId }
    val myUserIds = members.filter { it.member.id in mine }.mapNotNullTo(HashSet()) { it.member.userId }
    val byGroup = members.sortedBy { it.storedOrder }.groupBy({ it.member.groupId }, { it.member })
    val order = compareBy<GroupEntity> { it.kind == GroupKind.FAMILY }.thenBy { it.createdAt }.thenBy { it.id }
    return AvatarColors.of(
        groups.sortedWith(order).map { group ->
            val people = byGroup[group.id].orEmpty()
            AvatarGroup(
                id = group.id,
                named = group.kind == GroupKind.GROUP,
                places = (if (group.kind == GroupKind.FAMILY) people.filter { it.onApp || it.id in mine } else people).map { member ->
                    AvatarPlace(
                        memberId = member.id,
                        personKeys = listOfNotNull(personKeyOf(member), member.phone.takeIf { member.userId != null }),
                        isSelf = member.id in mine || (member.userId != null && member.userId in myUserIds),
                    )
                },
            )
        },
    )
}

/** Someone to put in a new group or add to one. */
sealed interface NewPerson {
    /** An app user the person already shares a group with. */
    data class KnownUser(val userId: String, val name: String) : NewPerson

    /** A friend without the app; [phone] is optional. */
    data class Friend(val name: String, val phone: String?) : NewPerson
}

/**
 * A group expense being added or edited (02/15, Expense detail).
 *
 * @param description what it was for.
 * @param category its category.
 * @param amountMinor the whole amount in paise.
 * @param occurredOn its date.
 * @param paidByMemberId who paid.
 * @param method how it is divided.
 * @param inputs member id to input for [method] (see [com.expenses.splits.SplitMath.shares]).
 * @param auto true when a recurring rule made it.
 */
data class GroupExpenseDraft(
    val description: String,
    val category: Category,
    val amountMinor: Long,
    val occurredOn: LocalDate,
    val paidByMemberId: String,
    val method: SplitMethod,
    val inputs: Map<String, Long?>,
    val auto: Boolean = false,
)

/**
 * "Do this every time": how payments to a merchant are split.
 *
 * @param groupId the group.
 * @param method how to divide.
 * @param inputs the inputs per member.
 * @param auto true when matching payments split themselves.
 */
data class SplitRule(val groupId: String, val method: SplitMethod, val inputs: Map<String, Long?>, val auto: Boolean)

/** The outcome of an action that needs the server at once (leave, remove, delete, invites). */
sealed interface ActionResult {
    /** Done. */
    data object Done : ActionResult

    /** Balances are not settled (`balances_not_settled`). */
    data object NotSettled : ActionResult

    /** No connection: "check the connection". */
    data object Offline : ActionResult

    /** Nobody is signed in. */
    data object SignedOut : ActionResult

    /** The server refused it with [code]. */
    data class Failed(val code: String) : ActionResult
}

/**
 * Join group (02/32).
 *
 * @param groupId the group.
 * @param groupName its name.
 * @param inviterName who sent the link.
 * @param memberNames first names of the other members.
 * @param memberCount how many people are in it.
 * @param youOweMinor what the invited place owes so far.
 * @param owedToYouMinor what the invited place is owed.
 * @param expensesThisMonth expenses this month that involve the invited place.
 * @param alreadyMember true when the person is already in the group.
 * @param kind what the link joins: a named group or direct split (Join group, 02/32), or a family (Join Family,
 * 06-family/01; docs/family.md 1.4). A family's amounts are always 0.
 * @param alreadyInFamily true when the person is active in another family: "You're already in a family" (accepting
 * would be refused with `409 already_in_family`, [com.expenses.app.groups.data.FamilyRepository.ALREADY_IN_FAMILY]).
 */
data class JoinPreview(
    val groupId: String,
    val groupName: String,
    val inviterName: String,
    val memberNames: List<String>,
    val memberCount: Int,
    val youOweMinor: Long,
    val owedToYouMinor: Long,
    val expensesThisMonth: Int,
    val alreadyMember: Boolean,
    val kind: GroupKind = GroupKind.GROUP,
    val alreadyInFamily: Boolean = false,
)

/**
 * What a reminder did.
 *
 * @param sent members who got a push.
 * @param skipped member id to why not (`not_on_app`, `too_soon`, `nothing_owed`).
 */
data class RemindResult(val sent: List<String>, val skipped: Map<String, String>)

/**
 * Who may delete settlement [this] (docs/server.md 4.8): its recorder, `from` or `to`. An imported payment between two
 * friends (4.17) is recorded by the importing owner, so the owner may delete it though neither party.
 */
fun SettlementEntity.canBeDeletedBy(memberId: String): Boolean =
    memberId == recordedByMemberId || memberId == fromMemberId || memberId == toMemberId

/**
 * How a Splitwise import stands (Importing, design 02-everyday/138 and 139; docs/splitwise-import.md 5.3).
 *
 * @param groupId the group the import made.
 * @param groupName its name: the group's own while it is here, else the name it was imported with.
 * @param sent items the server has.
 * @param total items to send.
 * @param state sending, done or failed.
 * @param problem true when a part of the group failed on the server (not offline) and waits to be tried again:
 * "Could not do that now" (139's alternative).
 */
data class ImportProgress(
    val groupId: String,
    val groupName: String,
    val sent: Int,
    val total: Int,
    val state: ImportState,
    val problem: Boolean,
)

/**
 * An import of the same file made before, whose group is still here ("This file already came across as Flat 302 on 18
 * Nov.", 02-everyday/137).
 *
 * @param groupId the group.
 * @param groupName its name now.
 * @param on the India date of the import.
 * @param expenseCount the expenses it brought.
 */
data class PastImport(val groupId: String, val groupName: String, val on: LocalDate, val expenseCount: Int)

/**
 * A group just imported, for Group imported (02-everyday/31, 141).
 *
 * @param groupId the group.
 * @param groupName its name.
 * @param expenseCount the past expenses that came across.
 * @param friends the friends without the app still in it, in the order they were stored (the file's column order):
 * one link each in the invite message.
 * @param memberNames everyone's name in the group, removed people included, for first names that stay clear.
 */
data class ImportedGroup(
    val groupId: String,
    val groupName: String,
    val expenseCount: Int,
    val friends: List<MemberEntity>,
    val memberNames: List<String>,
)
