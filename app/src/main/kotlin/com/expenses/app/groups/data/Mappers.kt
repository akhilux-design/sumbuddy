package com.expenses.app.groups.data

import com.expenses.app.data.category.Category
import com.expenses.app.groups.api.ExpenseDto
import com.expenses.app.groups.api.ExpenseInputDto
import com.expenses.app.groups.api.GroupDto
import com.expenses.app.groups.api.MemberDto
import com.expenses.app.groups.api.SettlementDto
import com.expenses.app.groups.api.SettlementInputDto
import com.expenses.app.groups.api.ShareDto
import com.expenses.splits.SplitMethod
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable

// Conversions between the API's JSON and the phone's rows. The server's names are the enum names, except group kinds,
// which are lower case.

/** The API name of a group kind. */
val GroupKind.apiName: String get() = name.lowercase()

/** The group kind of an API name (`group`, `direct`, `family`); unknown names are read as a named group. */
fun groupKindOf(apiName: String): GroupKind = when (apiName) {
    "direct" -> GroupKind.DIRECT
    "family" -> GroupKind.FAMILY
    else -> GroupKind.GROUP
}

/** The category of an API name; an unknown one (a newer server) reads as Other. */
fun categoryOf(apiName: String?): Category = Category.entries.firstOrNull { it.name == apiName } ?: Category.OTHER

/** The split method of an API name; an unknown one reads as Amounts, which keeps the shares as given. */
fun splitMethodOf(apiName: String?): SplitMethod = SplitMethod.entries.firstOrNull { it.name == apiName } ?: SplitMethod.AMOUNTS

/** The settle method of an API name; an unknown one reads as Other. */
fun settleMethodOf(apiName: String?): SettleMethod = SettleMethod.entries.firstOrNull { it.name == apiName } ?: SettleMethod.OTHER

/**
 * A member row from the API. [placeName] is the phone's own memory of the friend's name before a join by link (see
 * [MemberEntity.placeName]); the API never sends it.
 */
fun MemberDto.toEntity(placeName: String? = null): MemberEntity = MemberEntity(
    id = id,
    groupId = groupId,
    userId = userId,
    name = name,
    phone = phone,
    upiId = upiId,
    onApp = onApp,
    removed = removed,
    version = version,
    joinedAt = joinedAt?.let(Instant::ofEpochMilli),
    undoUntil = undoUntil?.let(Instant::ofEpochMilli),
    placeName = placeName,
)

/**
 * The [MemberEntity.placeName] to keep when [incoming] replaces [stored]: the friend's name when an app user has just
 * taken a friend's place, the name kept before while the same person stays in the place, and null once the place is
 * a friend without the app again (an undo) or nobody joined it.
 */
fun placeNameAfter(stored: MemberEntity?, incoming: MemberDto): String? = when {
    stored == null || incoming.userId == null -> null
    stored.userId == null -> stored.name
    stored.userId == incoming.userId -> stored.placeName
    else -> null
}

/** A member row as the API writes it. */
fun MemberEntity.toDto(): MemberDto = MemberDto(
    id, groupId, userId, name, phone, upiId, onApp, removed, version,
    joinedAt = joinedAt?.toEpochMilli(),
    undoUntil = undoUntil?.toEpochMilli(),
)

/** A group row as the API writes it. */
fun GroupEntity.toDto(): GroupDto =
    GroupDto(id, kind.apiName, name, ownerUserId, version, createdAt.toEpochMilli(), ownerMemberId = ownerMemberId)

/**
 * A group expense row from the API, or null for a tombstone (a deleted expense carries no fields). [fallback] fills
 * fields the API left out (an older server).
 */
fun ExpenseDto.toEntity(syncState: SyncState, fallbackTime: Instant): GroupExpenseEntity? {
    if (deleted) return null
    return GroupExpenseEntity(
        id = id,
        groupId = groupId,
        description = description.orEmpty(),
        category = categoryOf(category),
        amountMinor = amountPaise ?: 0,
        occurredOn = occurredOn?.let(LocalDate::parse) ?: LocalDate.ofEpochDay(0),
        paidByMemberId = paidByMemberId.orEmpty(),
        splitMethod = splitMethodOf(splitMethod),
        auto = auto,
        createdByMemberId = createdByMemberId.orEmpty(),
        updatedByMemberId = updatedByMemberId ?: createdByMemberId.orEmpty(),
        updatedAt = updatedAt?.let(Instant::ofEpochMilli) ?: fallbackTime,
        deleted = false,
        version = version,
        syncState = syncState,
    )
}

/** The share rows of an expense from the API. */
fun ExpenseDto.shareEntities(): List<ExpenseShareEntity> =
    shares.map { ExpenseShareEntity(expenseId = id, memberId = it.memberId, shareMinor = it.sharePaise, input = it.input) }

/** An expense with its shares as the API writes it. */
fun GroupExpenseWithShares.toDto(): ExpenseDto = ExpenseDto(
    id = expense.id,
    groupId = expense.groupId,
    deleted = expense.deleted,
    version = expense.version,
    description = expense.description,
    category = expense.category.name,
    amountPaise = expense.amountMinor,
    currency = "INR",
    occurredOn = expense.occurredOn.toString(),
    paidByMemberId = expense.paidByMemberId,
    splitMethod = expense.splitMethod.name,
    auto = expense.auto,
    shares = shares.sortedBy { it.memberId }.map { ShareDto(it.memberId, it.shareMinor, it.input) },
    createdByMemberId = expense.createdByMemberId,
    updatedByMemberId = expense.updatedByMemberId,
    updatedAt = expense.updatedAt.toEpochMilli(),
)

/** The `ExpenseInput` body for an expense and its shares. */
fun GroupExpenseWithShares.toInput(baseVersion: Long?): ExpenseInputDto = ExpenseInputDto(
    description = expense.description,
    category = expense.category.name,
    amountPaise = expense.amountMinor,
    occurredOn = expense.occurredOn.toString(),
    paidByMemberId = expense.paidByMemberId,
    splitMethod = expense.splitMethod.name,
    auto = expense.auto,
    shares = shares.sortedBy { it.memberId }.map { ShareDto(it.memberId, it.shareMinor, it.input) },
    baseVersion = baseVersion,
)

/** A settlement row from the API, or null for a tombstone. */
fun SettlementDto.toEntity(syncState: SyncState): SettlementEntity? {
    if (deleted) return null
    return SettlementEntity(
        id = id,
        groupId = groupId,
        fromMemberId = fromMemberId.orEmpty(),
        toMemberId = toMemberId.orEmpty(),
        amountMinor = amountPaise ?: 0,
        method = settleMethodOf(method),
        matchedFromSms = matchedFromSms,
        occurredOn = occurredOn?.let(LocalDate::parse) ?: LocalDate.ofEpochDay(0),
        recordedByMemberId = recordedByMemberId.orEmpty(),
        confirmedAt = confirmedAt?.let(Instant::ofEpochMilli),
        deleted = false,
        version = version,
        syncState = syncState,
    )
}

/** A settlement as the API writes it. */
fun SettlementEntity.toDto(): SettlementDto = SettlementDto(
    id = id,
    groupId = groupId,
    deleted = deleted,
    version = version,
    fromMemberId = fromMemberId,
    toMemberId = toMemberId,
    amountPaise = amountMinor,
    method = method.name,
    matchedFromSms = matchedFromSms,
    occurredOn = occurredOn.toString(),
    recordedByMemberId = recordedByMemberId,
    confirmedAt = confirmedAt?.toEpochMilli(),
)

/** The `SettlementInput` body of a settlement. */
fun SettlementEntity.toInput(): SettlementInputDto =
    SettlementInputDto(fromMemberId, toMemberId, amountMinor, method.name, matchedFromSms, occurredOn.toString())

/**
 * What rolling back an outbox entry puts back (docs/server.md 5.1, 4.1 "roll back"): the row as last synced (null
 * for a create) and the linked personal payment as it was before the change.
 */
@Serializable
data class RestoreSnapshot(
    val group: GroupDto? = null,
    val member: MemberDto? = null,
    val expense: ExpenseDto? = null,
    val settlement: SettlementDto? = null,
    val personal: PersonalSnapshot? = null,
)

/**
 * A personal payment's split fields before a change (docs/server.md 9.4).
 *
 * @param expenseId the personal row.
 * @param status its status before ([com.expenses.app.data.model.ExpenseStatus] name).
 * @param myShareMinor its share before.
 * @param groupExpenseId its link before.
 * @param deleteRow true when the change made this row (a mirror of an expense someone else paid): rolling back
 * deletes it.
 */
@Serializable
data class PersonalSnapshot(
    val expenseId: Long,
    val status: String,
    val myShareMinor: Long,
    val groupExpenseId: String?,
    val deleteRow: Boolean = false,
)
