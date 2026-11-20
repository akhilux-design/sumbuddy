package com.expenses.app.groups.splitwise

import com.expenses.app.data.category.Category
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.GroupBalance
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.groups.data.SyncState
import com.expenses.splits.SplitMethod
import com.expenses.splitwise.ImportParts
import com.expenses.splitwise.SplitwiseCsv
import com.expenses.splitwise.SplitwiseFile
import com.expenses.splitwise.SplitwiseResult
import com.expenses.splitwise.SwExpense
import com.expenses.splitwise.SwPart
import com.expenses.splitwise.SwPayment
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plan of an import (docs/splitwise-import.md 5.2): ids, members, methods, dates, items and parts. */
class ImportPlannerTest {
    private val user = UserDto("a0000000-0000-4000-8000-000000000001", "Arjun Mehta", "+919000000001", "arjun@okaxis")
    private val today = LocalDate.of(2026, 11, 16)
    private val now = Instant.parse("2026-11-16T06:30:00Z")
    private val v4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val names: (Category) -> String = { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase) }

    /** Ids e0000000-…-000000000001, 2, … in the order they are asked for (UUID v4 shaped). */
    private fun counter(): () -> String {
        var n = 0
        return { "e0000000-0000-4000-8000-%012d".format(++n) }
    }

    private fun file(
        people: List<String> = listOf("Arjun Mehta", "Priya Nair", "Kiran Rao"),
        expenses: List<SwExpense> = emptyList(),
        payments: List<SwPayment> = emptyList(),
    ) = SplitwiseFile(people, expenses, payments, List(people.size) { 0L }, emptyMap(), 0)

    private fun expense(row: Int, date: LocalDate, part: SwPart, description: String = "Rent", category: String = "Rent") =
        SwExpense(row, date, description, category, listOf(part))

    private fun plan(file: SplitwiseFile, me: Int = 0, newId: () -> String = counter()) =
        ImportPlanner.plan(file, me, "Flat 302", user, today, now, newId, names)

    @Test fun idsAreVersion4AndTheChosenColumnIsTheUsersOwnMember() {
        val payment = SwPayment(3, LocalDate.of(2025, 4, 3), from = 1, to = 2, amountPaise = 50_000)
        val rent = expense(2, LocalDate.of(2025, 4, 1), SwPart(0, 3_000_000, mapOf(0 to 1_000_000L, 1 to 1_000_000L, 2 to 1_000_000L)))
        val plan = plan(file(expenses = listOf(rent), payments = listOf(payment)), me = 1, newId = { UUID.randomUUID().toString() })

        val ids = listOf(plan.group.id) + plan.members.map { it.id } + plan.expenses.map { it.expense.id } +
            plan.settlements.map { it.id } + plan.parts.map { it.partId }
        assertTrue(ids.all { v4.matches(it) })
        assertEquals(ids.size, ids.toSet().size)
        // Members in column order; column 1 is the person: their own name, user and details.
        assertEquals(listOf("Arjun Mehta", "Arjun Mehta", "Kiran Rao"), plan.members.map { it.name })
        val mine = plan.members[1]
        assertEquals(plan.group.myMemberId, mine.id)
        assertEquals(plan.group.ownerMemberId, mine.id)
        assertEquals(user.id, mine.userId)
        assertEquals(user.id, plan.group.ownerUserId)
        assertTrue(mine.onApp)
        // Everyone else is a friend without the app: name only, no phone, never matched to anyone.
        val friends = plan.members.filter { it.id != mine.id }
        assertTrue(friends.all { it.userId == null && it.phone == null && it.upiId == null && !it.onApp })
        assertEquals(friends.map { it.id }, plan.friends.map { it.id })
        assertTrue(plan.friends.all { it.phone == null })
        assertEquals("Flat 302", plan.group.name)
        assertEquals(SyncState.PENDING, plan.group.syncState)
    }

    @Test fun equalSharesAreEqualOtherwiseAmounts() {
        // Ids: group 1, my member 2 (column 0), then 3 and 4. EQUAL gives the extra paisa to the lowest id (column 0).
        val even = expense(2, LocalDate.of(2025, 4, 1), SwPart(0, 1_000, mapOf(0 to 334L, 1 to 333L, 2 to 333L)))
        val other = expense(3, LocalDate.of(2025, 4, 2), SwPart(0, 1_000, mapOf(0 to 333L, 1 to 334L, 2 to 333L)))
        val paidForOthers = expense(4, LocalDate.of(2025, 4, 3), SwPart(0, 600, mapOf(1 to 300L, 2 to 300L)))
        val plan = plan(file(expenses = listOf(even, other, paidForOthers)))

        val (first, second, third) = plan.expenses
        assertEquals(SplitMethod.EQUAL, first.expense.splitMethod)
        assertTrue(first.shares.all { it.input == null })
        assertEquals(SplitMethod.AMOUNTS, second.expense.splitMethod)
        assertTrue(second.shares.all { it.input == it.shareMinor })
        // The payer was not in the split: only the two who owe have shares, split equally between them.
        assertEquals(SplitMethod.EQUAL, third.expense.splitMethod)
        assertEquals(setOf(plan.members[1].id, plan.members[2].id), third.shares.map { it.memberId }.toSet())
        assertEquals(plan.members[0].id, third.expense.paidByMemberId)
        plan.expenses.forEach { assertEquals(it.expense.amountMinor, it.shares.sumOf { share -> share.shareMinor }) }
    }

    @Test fun datesAfterTodayBecomeTodayAndBefore2000Become2000() {
        val planned = expense(2, LocalDate.of(2027, 1, 5), SwPart(0, 600, mapOf(0 to 300L, 1 to 300L)))
        val ancient = expense(3, LocalDate.of(1999, 12, 31), SwPart(0, 600, mapOf(0 to 300L, 1 to 300L)))
        val plan = plan(file(expenses = listOf(planned, ancient)))
        // Sent in date order: the clamped 1999 row first.
        assertEquals(listOf(ImportParts.FIRST_DATE, today), plan.expenses.map { it.expense.occurredOn })
    }

    @Test fun aBlankDescriptionBecomesTheCategoryNameAndCategoriesAreMapped() {
        val blank = expense(2, LocalDate.of(2025, 4, 1), SwPart(0, 600, mapOf(0 to 300L, 1 to 300L)), description = "", category = "Rent")
        val swiggy = expense(3, LocalDate.of(2025, 4, 2), SwPart(0, 600, mapOf(0 to 300L, 1 to 300L)), description = "Swiggy", category = "General")
        val unknown = expense(4, LocalDate.of(2025, 4, 3), SwPart(0, 600, mapOf(0 to 300L, 1 to 300L)), description = "Thing", category = "Mystery")
        val plan = plan(file(expenses = listOf(blank, swiggy, unknown)))
        val (first, second, third) = plan.expenses.map { it.expense }
        assertEquals(Category.RENT_AND_BILLS, first.category)
        assertEquals("Rent and bills", first.description)
        assertEquals(Category.FOOD, second.category)
        assertEquals("Swiggy", second.description)
        assertEquals(Category.OTHER, third.category)
        assertTrue(plan.expenses.all { !it.expense.auto && it.expense.version == 0L && it.expense.syncState == SyncState.PENDING })
        assertTrue(plan.expenses.all { it.expense.createdByMemberId == plan.group.myMemberId && it.expense.updatedByMemberId == plan.group.myMemberId })
    }

    @Test fun aPaymentBecomesAConfirmedSettlementRecordedByThePerson() {
        val payment = SwPayment(5, LocalDate.of(2025, 4, 3), from = 1, to = 2, amountPaise = 50_000)
        val plan = plan(file(payments = listOf(payment)))
        val settlement = plan.settlements.single()
        assertEquals(plan.members[1].id, settlement.fromMemberId)
        assertEquals(plan.members[2].id, settlement.toMemberId)
        assertEquals(50_000L, settlement.amountMinor)
        assertEquals(SettleMethod.OTHER, settlement.method)
        assertFalse(settlement.matchedFromSms)
        assertEquals(plan.group.myMemberId, settlement.recordedByMemberId)
        assertEquals(now, settlement.confirmedAt)
        assertEquals(LocalDate.of(2025, 4, 3), settlement.occurredOn)
        assertEquals(SyncState.PENDING, settlement.syncState)
        // Its item carries only what the server reads.
        val item = plan.parts.single().body["items"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("kind", "id", "fromMemberId", "toMemberId", "amountPaise", "occurredOn"), item.keys)
        assertEquals("settlement", item["kind"]!!.jsonPrimitive.content)
        assertEquals("2025-04-03", item["occurredOn"]!!.jsonPrimitive.content)
    }

    @Test fun anExpenseItemIsAnExpenseInputWithKindAndId() {
        val rent = expense(2, LocalDate.of(2025, 4, 1), SwPart(1, 3_000, mapOf(0 to 1_000L, 1 to 1_000L, 2 to 1_000L)))
        val plan = plan(file(expenses = listOf(rent)))
        val item = plan.parts.single().body["items"]!!.jsonArray.single().jsonObject
        assertEquals("expense", item["kind"]!!.jsonPrimitive.content)
        assertEquals(plan.expenses.single().expense.id, item["id"]!!.jsonPrimitive.content)
        assertEquals("RENT_AND_BILLS", item["category"]!!.jsonPrimitive.content)
        assertEquals(plan.members[1].id, item["paidByMemberId"]!!.jsonPrimitive.content)
        assertEquals("false", item["auto"]!!.jsonPrimitive.content)
        assertEquals(3, item["shares"]!!.jsonArray.size)
        assertEquals(listOf(ImportItemRef(EntityKind.EXPENSE, plan.expenses.single().expense.id)), plan.parts.single().items)
    }

    @Test fun partsHoldAtMost50ItemsAnd30000BytesInDateOrder() {
        val people = (1..40).map { "Person $it with a fairly long name to make the shares big" }
        val expenses = (1..130).map { n ->
            val shares = (0 until 40).associateWith { 100L }
            SwExpense(n + 1, LocalDate.of(2025, 1, 1).plusDays((n * 7L) % 90), "x".repeat(80), "General", listOf(SwPart(0, 4_000, shares)))
        }
        val payments = (1..30).map { SwPayment(200 + it, LocalDate.of(2025, 2, it % 28 + 1), from = 1, to = 2, amountPaise = 1_000L) }
        val plan = plan(file(people, expenses, payments))

        assertEquals(160, plan.itemCount)
        assertEquals(130, plan.expenseCount)
        assertTrue(plan.parts.size > 4)
        for (part in plan.parts) {
            val text = ApiJson.encodeToString(JsonElement.serializer(), part.body)
            assertTrue("part of ${text.encodeToByteArray().size} bytes", text.encodeToByteArray().size <= ImportParts.MAX_BODY_BYTES)
            assertTrue(part.items.size <= ImportParts.MAX_ITEMS)
            assertEquals(part.items.size, part.body["items"]!!.jsonArray.size)
        }
        // Every item once, sent in date order (then file order).
        val sent = plan.parts.flatMap { it.items }
        assertEquals(160, sent.map { it.id }.toSet().size)
        val dateOf = plan.expenses.associate { it.expense.id to it.expense.occurredOn } + plan.settlements.associate { it.id to it.occurredOn }
        val dates = sent.map { dateOf.getValue(it.id) }
        assertEquals(dates.sorted(), dates)
    }

    @Test fun theNetsOfThePlanAreTheFilesBalances() {
        val csv = """
            Date,Description,Category,Cost,Currency,Arjun Mehta,Priya Nair,Kiran Rao
            2025-04-01,Rent,Rent,30000.00,INR,20000.00,-10000.00,-10000.00
            2025-04-02,Dinner,Dining out,1000.00,INR,-333.33,666.67,-333.34
            2025-04-03,Priya paid Arjun,Payment,500.00,INR,-500.00,500.00,0.00
            2025-04-04,Taxi,Taxi,900.00,INR,600.00,-300.00,-300.00
            2025-04-05,Income,General,30.00,INR,-20.00,10.00,10.00
            2025-04-06,Cab home,Taxi,12.00,USD,6.00,-6.00,0.00
        """.trimIndent()
        val read = (SplitwiseCsv.parse(csv.toByteArray()) as SplitwiseResult.Ok).file
        for (me in 0..2) {
            val plan = plan(read, me = me)
            val balance = GroupBalance.of(plan.expenses, plan.settlements)
            plan.members.forEachIndexed { index, member -> assertEquals(read.balancesPaise[index], balance.netOf(member.id)) }
        }
        val plan = plan(read)
        // The income row has two payers: two parts. The payment is a settlement, not an expense.
        assertEquals(5, plan.expenses.size)
        assertEquals(1, plan.settlements.size)
        assertEquals(4, plan.expenseCount)
        assertEquals(6, plan.itemCount)
        assertNull(plan.expenses.firstOrNull { it.expense.amountMinor == 1_200L })
    }
}
