package com.expenses.app.groups.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.splits.SplitMethod
import java.time.Instant
import java.time.LocalDate

// The phone's copy of the groups the person is in (docs/server.md 9.3). Ids are the server's lowercase UUID strings,
// money is Long paise, dates are India dates. Every row that the server versions keeps the group version of its last
// change. Enums are stored by name: never rename a constant.

/** Whether a group is a named group, a direct split with one person, or a family. */
enum class GroupKind {
    /** A named group ("Flat 302"). */
    GROUP,

    /** A split with a single friend, outside any named group. */
    DIRECT,

    /**
     * A family (docs/family.md; version 6): a pooled month of two people with no owing. Its expenses are each paid and
     * fully owned by one member, so nets are always 0. Never listed with groups, friends, totals or split labels.
     */
    FAMILY,
}

/** How a row stands against the server. */
enum class SyncState {
    /** The server has this version. */
    SYNCED,

    /** Changed on this phone; the change waits in the outbox. */
    PENDING,

    /** The server refused the change; the row was put back as it was. */
    REJECTED,
}

/** How a settlement was paid. Names are the server's `method`. */
enum class SettleMethod {
    /** Paid by UPI. */
    UPI,

    /** Paid in cash. */
    CASH,

    /** Paid another way (bank transfer, or anything else). */
    OTHER,
}

/**
 * A group the person is an active member of.
 *
 * @param id the group id (client-made UUID; deterministic for direct groups between app users).
 * @param kind group or direct split.
 * @param name the group's name; null for a direct split.
 * @param ownerUserId the user who may delete the group or remove people on the app; null for direct splits.
 * @param myMemberId the person's own member row in this group.
 * @param version the last group version applied on this phone (pulls ask for everything above it).
 * @param latestVersion the newest group version the server reported; the group's data is complete when [version]
 * reached it.
 * @param loadFailed true when the last pull of this group failed (Groups "Could not load").
 * @param syncState PENDING while the group's own create (or rename) waits in the outbox.
 * @param createdAt when the group was made.
 * @param ownerMemberId the owner's active member row, labelled "Owner" on Group settings (docs/server.md 4.6); null
 * when nobody on the app owns the group, or before the first pull (version 3).
 */
@Entity(tableName = "grp_groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val kind: GroupKind,
    val name: String?,
    val ownerUserId: String?,
    val myMemberId: String,
    val version: Long,
    val latestVersion: Long,
    val loadFailed: Boolean = false,
    val syncState: SyncState,
    val createdAt: Instant,
    val ownerMemberId: String? = null,
)

/**
 * A person in a group: an app user, or a friend without the app (name and optional phone typed by a member).
 *
 * @param id the member id.
 * @param groupId the group.
 * @param userId the app user, or null for a friend without the app.
 * @param name the name to show (the user's own name for app users).
 * @param phone E.164 phone number, or null.
 * @param upiId the user's UPI ID for Pay by UPI, or null.
 * @param onApp true for app users.
 * @param removed true once the person left or was removed; old expenses still show their name.
 * @param version the group version of this member's last change.
 * @param joinedAt when someone took this place through the friend's link, while that join can still be undone
 * (version 3; docs/server.md 4.6); null otherwise.
 * @param undoUntil [joinedAt] + 7 days: the group's owner may undo the join before then. The banner rule is
 * [GroupDetail.joinsToUndo]; a stored `canUndo` is never trusted, since rows are not sent again when time passes.
 * @param placeName the friend's name this place had on this phone before someone joined it through a link (kept on
 * this phone only, version 3), so the banner can name both: "Vikram joined · Not Priya?". Null when unknown.
 */
@Entity(
    tableName = "grp_members",
    foreignKeys = [ForeignKey(entity = GroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("groupId"), Index("userId")],
)
data class MemberEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val userId: String?,
    val name: String,
    val phone: String?,
    val upiId: String?,
    val onApp: Boolean,
    val removed: Boolean,
    val version: Long,
    val joinedAt: Instant? = null,
    val undoUntil: Instant? = null,
    val placeName: String? = null,
)

/**
 * A group expense. Its shares are [ExpenseShareEntity] rows. A deleted expense stays as a tombstone ([deleted]) so
 * the delete wins over later edits.
 *
 * @param id the expense id (client-made UUID).
 * @param groupId the group.
 * @param description what it was for ("Swiggy").
 * @param category its category.
 * @param amountMinor the whole expense in paise.
 * @param occurredOn the India date it happened.
 * @param paidByMemberId who paid.
 * @param splitMethod how it is divided.
 * @param auto true when a recurring split rule made it ("splits itself").
 * @param createdByMemberId who added it.
 * @param updatedByMemberId who changed it last.
 * @param updatedAt when it was changed last.
 * @param deleted true once deleted.
 * @param version the group version of its last change (0 before the server has it).
 * @param syncState whether a change waits in the outbox.
 */
@Entity(
    tableName = "grp_expenses",
    foreignKeys = [ForeignKey(entity = GroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["groupId", "occurredOn"])],
)
data class GroupExpenseEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val description: String,
    val category: Category,
    val amountMinor: Long,
    val occurredOn: LocalDate,
    val paidByMemberId: String,
    val splitMethod: SplitMethod,
    val auto: Boolean,
    val createdByMemberId: String,
    val updatedByMemberId: String,
    val updatedAt: Instant,
    val deleted: Boolean,
    val version: Long,
    val syncState: SyncState,
)

/**
 * One person's share of a group expense.
 *
 * @param expenseId the expense.
 * @param memberId the member.
 * @param shareMinor the share in paise.
 * @param input what was typed for the method: null for equal splits, paise for amounts, basis points for percent,
 * the weight for shares.
 */
@Entity(
    tableName = "grp_expense_shares",
    primaryKeys = ["expenseId", "memberId"],
    foreignKeys = [ForeignKey(entity = GroupExpenseEntity::class, parentColumns = ["id"], childColumns = ["expenseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("memberId")],
)
data class ExpenseShareEntity(
    val expenseId: String,
    val memberId: String,
    val shareMinor: Long,
    val input: Long?,
)

/**
 * Money paid back between two members ("Settle up", "Mark as paid", a money-received SMS match).
 *
 * @param id the settlement id (client-made UUID).
 * @param groupId the group.
 * @param fromMemberId who paid.
 * @param toMemberId who received.
 * @param amountMinor the amount in paise.
 * @param method how it was paid.
 * @param matchedFromSms true when the phone matched it to a bank SMS.
 * @param occurredOn the India date it was paid.
 * @param recordedByMemberId who recorded it.
 * @param confirmedAt when the other party confirmed it, or null.
 * @param deleted true once deleted.
 * @param version the group version of its last change.
 * @param syncState whether a change waits in the outbox.
 */
@Entity(
    tableName = "grp_settlements",
    foreignKeys = [ForeignKey(entity = GroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("groupId")],
)
data class SettlementEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val fromMemberId: String,
    val toMemberId: String,
    val amountMinor: Long,
    val method: SettleMethod,
    val matchedFromSms: Boolean,
    val occurredOn: LocalDate,
    val recordedByMemberId: String,
    val confirmedAt: Instant?,
    val deleted: Boolean,
    val version: Long,
    val syncState: SyncState,
)

/** What kind of row an outbox entry changes. */
enum class EntityKind {
    /** A group (create, rename). */
    GROUP,

    /** A member (add, edit a friend without the app). */
    MEMBER,

    /** An expense (create or update, delete). */
    EXPENSE,

    /** A settlement (create, confirm, delete). */
    SETTLEMENT,

    /**
     * One part of a Splitwise import (`POST /v1/groups/{gid}/import`, docs/server.md 4.17; version 5): up to 50
     * expenses and settlements. The entry's `entityId` is the part's own id; the items' ids are in its body.
     */
    IMPORT,
}

/** What an outbox entry does to its row. */
enum class OutboxOp {
    /** Makes the row. */
    CREATE,

    /** Changes the row. */
    UPDATE,

    /** Deletes the row. */
    DELETE,

    /** Confirms a settlement. */
    CONFIRM,
}

/**
 * A change made on this phone that waits to be sent (docs/server.md 5.1). Entries are sent in [seq] order, one at a
 * time.
 *
 * @param seq the order the changes were made in.
 * @param idempotencyKey the `Idempotency-Key` header: the same on every retry, so the server applies it once.
 * @param groupId the group it belongs to; a stuck entry blocks only the later entries of its group.
 * @param entityKind what kind of row it changes.
 * @param entityId the id of that row.
 * @param op what it does to the row.
 * @param method the HTTP method.
 * @param path the API path, from `/v1`.
 * @param bodyJson the request body as sent, or null for none.
 * @param restoreJson what a rejection puts back (a [RestoreSnapshot]): the row as last synced (null for a create)
 * and the linked personal payment as it was.
 * @param createdAt when the change was made.
 * @param attempts how many times it was sent (an attempted entry may already be applied, so it is never coalesced).
 * @param failures server errors (5xx) in a row; at 5 the entry blocks only its own group.
 * @param lastErrorCode the last error `code`, for debugging; never user data.
 */
@Entity(tableName = "grp_outbox", indices = [Index("entityId"), Index("groupId")])
data class OutboxEntry(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val idempotencyKey: String,
    val groupId: String?,
    val entityKind: EntityKind,
    val entityId: String,
    val op: OutboxOp,
    val method: String,
    val path: String,
    val bodyJson: String?,
    val restoreJson: String?,
    val createdAt: Instant,
    val attempts: Int = 0,
    val failures: Int = 0,
    val lastErrorCode: String? = null,
)

/**
 * A pay link this phone made for a friend in a group (docs/server.md 4.10). The phone makes the id and the token, so
 * a lost answer can be sent again.
 *
 * @param memberId the friend the link is for.
 * @param groupId the group.
 * @param linkId the link id.
 * @param token the 24 character token in the link.
 * @param createdAt when it was made.
 * @param expiresAt when the server stops answering it (90 days after it was made).
 */
@Entity(tableName = "grp_links")
data class LinkEntity(
    @PrimaryKey val memberId: String,
    val groupId: String,
    val linkId: String,
    val token: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * "Do this every time": how payments to one merchant are split. Kept on this phone only.
 *
 * @param merchantKey the merchant ([com.expenses.app.data.category.CategoryGuesser.merchantKey]).
 * @param groupId the group to split with.
 * @param splitMethod how to divide it.
 * @param inputsJson the inputs per member, as JSON (member id to input or null).
 * @param auto true when matching payments are split as soon as they arrive.
 */
@Entity(tableName = "grp_split_rules")
data class SplitRuleEntity(
    @PrimaryKey val merchantKey: String,
    val groupId: String,
    val splitMethod: SplitMethod,
    val inputsJson: String,
    val auto: Boolean,
)

/**
 * A change the server refused (docs/server.md 4.1 "roll back"; design 02-everyday/43-group-page-change-not-saved).
 * The change itself was already rolled back when this row was written, so balances and the month are right; the
 * row only lets the Group page say "Not saved" with Try again and Dismiss. Rows of a group go with the group.
 *
 * @param id the row id.
 * @param groupId the group the change was for.
 * @param entityKind what kind of row it changed.
 * @param entityId the id of that row.
 * @param op what it did to the row.
 * @param method the HTTP method that was sent.
 * @param path the API path that was sent.
 * @param bodyJson the request body that was sent, or null: what the person tried (the "Not saved" row is drawn
 * from it, and Try again sends it again).
 * @param restoreJson the outbox entry's [RestoreSnapshot] as it was when refused (the linked personal payment as it
 * was before the change), or null.
 * @param errorCode the server's error `code` (for example `bad_request`, `not_allowed`); Try again is pointless for
 * some codes.
 * @param rejectedAt when the refusal arrived.
 */
@Entity(
    tableName = "grp_rejected",
    foreignKeys = [ForeignKey(entity = GroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("groupId")],
)
data class RejectedChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    val entityKind: EntityKind,
    val entityId: String,
    val op: OutboxOp,
    val method: String,
    val path: String,
    val bodyJson: String?,
    val restoreJson: String?,
    val errorCode: String,
    val rejectedAt: Instant,
)

/**
 * How a personal payment was just before a split linked it to group expense [groupExpenseId] (docs/server.md 9.4,
 * version 4). Only an undone join reads it: that payment goes back to how it was (a payment that was waiting waits
 * again) instead of counting in full. The first link of a payment is kept: a link the phone puts back later (a
 * rollback, the payer changed back to the person) does not replace it. Rows of a group go with the group.
 *
 * @param groupExpenseId the group expense the payment was linked to.
 * @param groupId that expense's group.
 * @param expenseId the personal payment (`expenses.id`); the row applies only while that payment holds the link.
 * @param status the payment's status just before the link.
 */
@Entity(
    tableName = "grp_link_priors",
    foreignKeys = [ForeignKey(entity = GroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("groupId")],
)
data class LinkPriorEntity(
    @PrimaryKey val groupExpenseId: String,
    val groupId: String,
    val expenseId: Long,
    val status: ExpenseStatus,
)

/** How a Splitwise import made on this phone stands (version 5). Stored by name: never rename a constant. */
enum class ImportState {
    /** Parts wait in the outbox ("Bringing Flat 302 across"). */
    SENDING,

    /** The server has every part. */
    DONE,

    /** A part was refused: the group was removed on this phone and its delete queued ("did not come across"). */
    FAILED,
}

/**
 * A Splitwise import made on this phone (docs/server.md 9.3, docs/splitwise-import.md 5.1; version 5). The file
 * itself is never kept. No foreign key: a FAILED row outlives the group it removed, so the screen can still say so.
 *
 * @param groupId the group the import made.
 * @param fileHash the lowercase hex SHA-256 of the file's bytes, for "This file already came across" (137). It stays
 * on this phone.
 * @param groupName the group's name when it was imported: the Failed screen (140) names it after the group is gone.
 * @param expenseCount the Splitwise expense rows imported ("46 past expenses"); payments are not counted.
 * @param itemCount the expenses (parts of rows with several payers counted apart) and payments sent to the server.
 * @param sentItems how many of [itemCount] the server has.
 * @param state how the import stands.
 * @param createdAt when the import was made ("on 18 Nov").
 */
@Entity(tableName = "grp_imports", indices = [Index("fileHash")])
data class ImportEntity(
    @PrimaryKey val groupId: String,
    val fileHash: String,
    val groupName: String,
    val expenseCount: Int,
    val itemCount: Int,
    val sentItems: Int,
    val state: ImportState,
    val createdAt: Instant,
)

/**
 * A Pay by UPI the person started, waiting for the bank SMS that shows it went through (docs/server.md 9.5).
 *
 * @param id the row id.
 * @param groupId the group.
 * @param toMemberId the friend being paid.
 * @param amountMinor the amount in paise.
 * @param startedAt when the UPI app was opened.
 */
@Entity(tableName = "grp_pending_upi")
data class PendingUpiEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val toMemberId: String,
    val amountMinor: Long,
    val startedAt: Instant,
)
