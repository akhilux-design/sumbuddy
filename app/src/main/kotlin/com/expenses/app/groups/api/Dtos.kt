package com.expenses.app.groups.api

import kotlinx.serialization.Serializable

// The JSON of docs/server.md section 4, camelCase, money in paise, times in epoch milliseconds, dates as
// "YYYY-MM-DD" in India time. Unknown fields are ignored, so the server can add fields without breaking old apps.
// Nothing here carries SMS text, account, card, note or "paid with" data (docs/server.md 10); DtoPrivacyTest checks
// the field names.

/** `User` (4.5): the signed-in person's profile. */
@Serializable
data class UserDto(val id: String, val name: String, val phone: String? = null, val upiId: String? = null)

/** `POST /v1/auth/nonce` answer. */
@Serializable
data class NonceResponse(val nonce: String, val expiresAt: Long = 0)

/** `POST /v1/auth/google` body. */
@Serializable
data class GoogleSignInRequest(val idToken: String, val nonce: String, val installationId: String)

/** `POST /v1/auth/dev` body (debug builds only send it). */
@Serializable
data class DevSignInRequest(val devUser: String, val name: String, val installationId: String)

/** The answer to a sign-in (`/auth/google`, `/auth/dev`). */
@Serializable
data class AuthResponse(
    val user: UserDto,
    val isNew: Boolean = false,
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
)

/** `POST /v1/auth/refresh` body. */
@Serializable
data class RefreshRequest(val refreshToken: String)

/** `POST /v1/auth/refresh` answer: the new pair. */
@Serializable
data class TokenPair(
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
)

/** One row of `GET /v1/me/groups`. */
@Serializable
data class MeGroupDto(
    val id: String,
    val kind: String,
    val name: String? = null,
    val version: Long,
    val myMemberId: String,
    val ownerUserId: String? = null,
)

/**
 * A group the person lost in the last 90 days and is not in again (`GET /v1/me/groups` `lost`, docs/server.md 4.5).
 *
 * @param id the group.
 * @param reason why: [LostReason.JOIN_UNDONE], `removed`, `left` or `deleted`.
 * @param at when (epoch ms).
 */
@Serializable
data class LostGroupDto(val id: String, val reason: String, val at: Long = 0)

/**
 * `GET /v1/me/groups` answer: the groups the person is active in, and those lost recently with why ([lost]; empty
 * from a server without it).
 */
@Serializable
data class MeGroupsResponse(val groups: List<MeGroupDto>, val serverTime: Long = 0, val lost: List<LostGroupDto> = emptyList())

/** Why a person lost a group (docs/server.md 4.1 `not_member` `reason`, 4.5 `lost`). */
object LostReason {
    /** The owner undid the person's join by a friend's link: they were never that friend (9.4: forget the splits). */
    const val JOIN_UNDONE: String = "join_undone"

    /** The owner removed them. */
    const val REMOVED: String = "removed"

    /** They left. */
    const val LEFT: String = "left"

    /** The group was deleted. */
    const val DELETED: String = "deleted"
}

/** `PUT /v1/devices/{installationId}` body. */
@Serializable
data class DeviceRequest(val fcmToken: String)

/**
 * `Group` (4.6). [ownerMemberId] is the owner's active member row (the "Owner" label on Group settings), null when
 * nobody on the app owns the group; it comes in every Group answer and pull page, so it follows ownership.
 */
@Serializable
data class GroupDto(
    val id: String,
    val kind: String,
    val name: String? = null,
    val ownerUserId: String? = null,
    val version: Long = 0,
    val createdAt: Long = 0,
    val ownerMemberId: String? = null,
)

/**
 * `Member` (4.6). [joinedAt] and [undoUntil] (epoch ms) are set only for a person who took a friend's place through
 * the friend's link, is still in it, and joined less than 7 days before the answer. [canUndo] is right only at the
 * moment of the answer: the phone decides with [undoUntil], its clock and the group's owner (docs/server.md 4.6).
 */
@Serializable
data class MemberDto(
    val id: String,
    val groupId: String,
    val userId: String? = null,
    val name: String,
    val phone: String? = null,
    val upiId: String? = null,
    val onApp: Boolean = false,
    val removed: Boolean = false,
    val version: Long = 0,
    val joinedAt: Long? = null,
    val undoUntil: Long? = null,
    val canUndo: Boolean = false,
)

/** `POST /v1/groups` answer. */
@Serializable
data class CreateGroupResponse(val group: GroupDto, val members: List<MemberDto> = emptyList())

/** One share of an expense. `input` is null for equal splits. */
@Serializable
data class ShareDto(val memberId: String, val sharePaise: Long, val input: Long? = null)

/** `ExpenseInput` (4.7), the body of `PUT .../expenses/{eid}`. */
@Serializable
data class ExpenseInputDto(
    val description: String,
    val category: String,
    val amountPaise: Long,
    val occurredOn: String,
    val paidByMemberId: String,
    val splitMethod: String,
    val auto: Boolean,
    val shares: List<ShareDto>,
    val baseVersion: Long?,
)

/**
 * `Expense` (4.7). A deleted expense in a pull has only [id], [groupId], [deleted] and [version], so the other fields
 * are optional here.
 */
@Serializable
data class ExpenseDto(
    val id: String,
    val groupId: String,
    val deleted: Boolean = false,
    val version: Long = 0,
    val description: String? = null,
    val category: String? = null,
    val amountPaise: Long? = null,
    val currency: String? = null,
    val occurredOn: String? = null,
    val paidByMemberId: String? = null,
    val splitMethod: String? = null,
    val auto: Boolean = false,
    val shares: List<ShareDto> = emptyList(),
    val createdByMemberId: String? = null,
    val updatedByMemberId: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val overwrote: Boolean = false,
)

/** The answer to a delete that bumps the group version. */
@Serializable
data class VersionResponse(val version: Long)

/** `SettlementInput` (4.8), the body of `PUT .../settlements/{sid}`. */
@Serializable
data class SettlementInputDto(
    val fromMemberId: String,
    val toMemberId: String,
    val amountPaise: Long,
    val method: String,
    val matchedFromSms: Boolean,
    val occurredOn: String,
)

/** `Settlement` (4.8). A deleted one in a pull may carry only its ids and version. */
@Serializable
data class SettlementDto(
    val id: String,
    val groupId: String,
    val deleted: Boolean = false,
    val version: Long = 0,
    val fromMemberId: String? = null,
    val toMemberId: String? = null,
    val amountPaise: Long? = null,
    val method: String? = null,
    val matchedFromSms: Boolean = false,
    val occurredOn: String? = null,
    val recordedByMemberId: String? = null,
    val confirmedAt: Long? = null,
    val createdAt: Long? = null,
)

/** One row of the change log in a pull (4.9). */
@Serializable
data class ChangeDto(
    val version: Long,
    val kind: String,
    val op: String,
    val entityId: String,
    val actorMemberId: String? = null,
    val at: Long = 0,
)

/** `GET /v1/groups/{gid}/changes?since=N` answer (4.9). */
@Serializable
data class ChangesPage(
    val groupId: String,
    val fromVersion: Long,
    val toVersion: Long,
    val latestVersion: Long,
    val hasMore: Boolean = false,
    val group: GroupDto,
    val members: List<MemberDto> = emptyList(),
    val expenses: List<ExpenseDto> = emptyList(),
    val settlements: List<SettlementDto> = emptyList(),
    val changes: List<ChangeDto> = emptyList(),
)

/** A member's net in `GET .../balances`. */
@Serializable
data class NetDto(val memberId: String, val netPaise: Long)

/** A transfer in `GET .../balances`. */
@Serializable
data class TransferDto(val fromMemberId: String, val toMemberId: String, val amountPaise: Long)

/** `GET /v1/groups/{gid}/balances` answer (for tests and debugging; screens compute their own). */
@Serializable
data class BalancesResponse(val version: Long, val nets: List<NetDto> = emptyList(), val transfers: List<TransferDto> = emptyList())

/** `PUT .../links/{linkId}` body: the token the phone made. */
@Serializable
data class LinkRequest(val token: String)

/** `PUT .../links/{linkId}` answer. */
@Serializable
data class LinkResponse(val linkId: String, val url: String, val createdAt: Long = 0, val expiresAt: Long = 0)

/**
 * `GET /v1/invites/{token}` answer: the Join group screen (02/32), or Join Family (06/01) when [kind] is `family`
 * (docs/family.md 3.10). [alreadyInFamily] is true when the caller is active in another family (accepting would be
 * refused with `409 already_in_family`). An older server sends neither: read as a group link.
 */
@Serializable
data class InvitePreviewDto(
    val groupId: String,
    val groupName: String? = null,
    val inviterName: String,
    val memberNames: List<String> = emptyList(),
    val memberCount: Int = 0,
    val youOwePaise: Long = 0,
    val owedToYouPaise: Long = 0,
    val expensesThisMonth: Int = 0,
    val alreadyMember: Boolean = false,
    val kind: String = "group",
    val alreadyInFamily: Boolean = false,
)

/** `POST /v1/invites/{token}/accept` answer. */
@Serializable
data class AcceptInviteResponse(val groupId: String, val memberId: String, val version: Long = 0)

/** `POST /v1/groups/{gid}/reminders` body. */
@Serializable
data class RemindRequest(val memberIds: List<String>)

/** A member who was not reminded, and why (`not_on_app`, `too_soon`, `nothing_owed`). */
@Serializable
data class RemindSkippedDto(val memberId: String, val reason: String)

/** `POST /v1/groups/{gid}/reminders` answer. */
@Serializable
data class RemindResponse(val sent: List<String> = emptyList(), val skipped: List<RemindSkippedDto> = emptyList())

/**
 * `POST /v1/groups/{gid}/import` answer (4.17): item `i` of the part was written at version [firstVersion] + `i`.
 * [applied] false (every id was already in the group: an old retry whose key was purged) wrote nothing, and both
 * versions are null.
 */
@Serializable
data class ImportResponse(val applied: Boolean, val firstVersion: Long? = null, val lastVersion: Long? = null)

/** `PATCH /v1/groups/{gid}` body. */
@Serializable
data class RenameGroupRequest(val name: String)

/** The error body of every failed request. */
@Serializable
data class ErrorBody(val error: ErrorDetail)

/**
 * What went wrong: a fixed `code`, for validation errors the JSON path of the bad `field`, and for `not_member` the
 * [reason] the person lost the group, when the server knows ([LostReason]).
 */
@Serializable
data class ErrorDetail(val code: String, val message: String? = null, val field: String? = null, val reason: String? = null)
