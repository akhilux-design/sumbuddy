package com.expenses.app.groups.splitwise

import com.expenses.app.data.category.Category
import com.expenses.app.groups.api.MemberBody
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.ExpenseShareEntity
import com.expenses.app.groups.data.GroupEntity
import com.expenses.app.groups.data.GroupExpenseEntity
import com.expenses.app.groups.data.GroupExpenseWithShares
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupText
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.groups.data.SettlementEntity
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.data.toInput
import com.expenses.splits.SplitMath
import com.expenses.splits.SplitMethod
import com.expenses.splitwise.ImportParts
import com.expenses.splitwise.SplitwiseFile
import com.expenses.splitwise.SwItem
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject

/**
 * One request of an import: its own id (the outbox entry's `entityId`), its body and the items it names, in body
 * order.
 */
data class ImportPart(val partId: String, val body: JsonObject, val items: List<ImportItemRef>)

/**
 * Everything one import writes to this phone and sends (docs/splitwise-import.md 5.2).
 *
 * @param group the new group, owned by the person, waiting to be sent.
 * @param members everyone in the file's column order: the person's own member at their column, friends without the
 * app (names only) elsewhere. Stored in this order, which gives the avatar colours.
 * @param friends the friends as `POST /v1/groups` takes them, in column order.
 * @param expenses the expenses with their shares, in the order they are sent.
 * @param settlements the payments, in the order they are sent.
 * @param parts the import requests, in order; each holds at most 50 items and 30,000 bytes.
 * @param expenseCount Splitwise expense rows ("46 past expenses").
 * @param itemCount expenses and settlements sent.
 */
data class ImportPlan(
    val group: GroupEntity,
    val members: List<MemberEntity>,
    val friends: List<MemberBody.Friend>,
    val expenses: List<GroupExpenseWithShares>,
    val settlements: List<SettlementEntity>,
    val parts: List<ImportPart>,
    val expenseCount: Int,
    val itemCount: Int,
)

/**
 * Turns a read Splitwise file into a group of this phone (docs/splitwise-import.md 5.2): new v4 ids for the group, its
 * members, expenses, settlements and parts; expenses with the person's column as their own member; dates moved into
 * what the server takes ([ImportParts.clampDate]); categories from [ImportCategories]; the requests in date order,
 * then file order, in parts. Pure: it writes nothing and logs nothing.
 */
object ImportPlanner {
    /**
     * The plan of importing [file] as [groupName], with column [me] as the person [user].
     *
     * @param today India's date: later dates become it.
     * @param now when the import is made (`updatedAt`, settlements' `confirmedAt`).
     * @param newId makes a new lowercase UUID v4.
     * @param categoryName the name of a category, used as the description of a row with none.
     * @throws IllegalArgumentException when [me] is not a column or [groupName] is blank once cleaned.
     */
    fun plan(
        file: SplitwiseFile,
        me: Int,
        groupName: String,
        user: UserDto,
        today: LocalDate,
        now: Instant,
        newId: () -> String,
        categoryName: (Category) -> String,
    ): ImportPlan {
        require(me in file.people.indices) { "unknown column" }
        val name = GroupText.clean(groupName, GroupText.MAX_GROUP_NAME)
        require(name.isNotEmpty()) { "group name is blank" }
        val groupId = newId()
        val myMemberId = newId()
        val memberIds = file.people.indices.map { if (it == me) myMemberId else newId() }
        val members = file.people.mapIndexed { index, person ->
            if (index == me) {
                MemberEntity(myMemberId, groupId, user.id, user.name, user.phone, user.upiId, onApp = true, removed = false, version = 0)
            } else {
                val friendName = GroupText.clean(person, GroupText.MAX_PERSON_NAME)
                require(friendName.isNotEmpty()) { "name is blank" }
                MemberEntity(memberIds[index], groupId, null, friendName, null, null, onApp = false, removed = false, version = 0)
            }
        }
        val friends = members.filter { it.id != myMemberId }.map { MemberBody.Friend(it.id, it.name, null) }
        val group = GroupEntity(
            groupId, GroupKind.GROUP, name, user.id, myMemberId, version = 0, latestVersion = 0, syncState = SyncState.PENDING,
            createdAt = now, ownerMemberId = myMemberId,
        )

        val expenses = ArrayList<GroupExpenseWithShares>()
        val settlements = ArrayList<SettlementEntity>()
        val sent = ArrayList<Pair<ImportItemRef, JsonObject>>()
        for (item in ImportParts.items(file, today)) {
            when (item) {
                is SwItem.Expense -> {
                    val row = expense(item, groupId, myMemberId, memberIds, now, newId(), categoryName)
                    expenses += row
                    sent += ImportItemRef(EntityKind.EXPENSE, row.expense.id) to ImportBodies.expenseItem(row.expense.id, row.toInput(null))
                }
                is SwItem.Payment -> {
                    val payment = item.payment
                    val settlement = SettlementEntity(
                        id = newId(),
                        groupId = groupId,
                        fromMemberId = memberIds[payment.from],
                        toMemberId = memberIds[payment.to],
                        amountMinor = payment.amountPaise,
                        method = SettleMethod.OTHER,
                        matchedFromSms = false,
                        occurredOn = item.occurredOn,
                        recordedByMemberId = myMemberId,
                        // A Splitwise payment is already settled: the server confirms it at once (4.17).
                        confirmedAt = now,
                        deleted = false,
                        version = 0,
                        syncState = SyncState.PENDING,
                    )
                    settlements += settlement
                    sent += ImportItemRef(EntityKind.SETTLEMENT, settlement.id) to ImportBodies.settlementItem(settlement)
                }
            }
        }
        val parts = ImportParts.chunk(sent) { ImportBodies.itemBytes(it.second) }.map { chunk ->
            ImportPart(newId(), ImportBodies.part(chunk.map { it.second }), chunk.map { it.first })
        }
        return ImportPlan(group, members, friends, expenses, settlements, parts, file.expenseCount, sent.size)
    }

    /**
     * One expense part: paid by its payer, shared as the file says. The split method is EQUAL when [SplitMath] gives
     * exactly these shares to these people (Splitwise's extra paisa may have gone to someone else, docs Risks), else
     * AMOUNTS with each share as its input.
     */
    private fun expense(
        item: SwItem.Expense,
        groupId: String,
        myMemberId: String,
        memberIds: List<String>,
        now: Instant,
        id: String,
        categoryName: (Category) -> String,
    ): GroupExpenseWithShares {
        val source = item.expense
        val part = item.part
        val category = ImportCategories.toCategory(source.category, source.description)
        val description = GroupText.clean(source.description, GroupText.MAX_DESCRIPTION)
            .ifEmpty { GroupText.clean(categoryName(category), GroupText.MAX_DESCRIPTION) }
        val shares = part.sharesPaise.entries.associate { (person, share) -> memberIds[person] to share }
        val equal = SplitMath.shares(SplitMethod.EQUAL, part.amountPaise, shares.keys.associateWith { null }) == shares
        val method = if (equal) SplitMethod.EQUAL else SplitMethod.AMOUNTS
        val expense = GroupExpenseEntity(
            id = id,
            groupId = groupId,
            description = description,
            category = category,
            amountMinor = part.amountPaise,
            occurredOn = item.occurredOn,
            paidByMemberId = memberIds[part.payer],
            splitMethod = method,
            auto = false,
            createdByMemberId = myMemberId,
            updatedByMemberId = myMemberId,
            updatedAt = now,
            deleted = false,
            version = 0,
            syncState = SyncState.PENDING,
        )
        val rows = shares.map { (member, share) -> ExpenseShareEntity(id, member, share, if (equal) null else share) }
        return GroupExpenseWithShares(expense, rows)
    }
}
