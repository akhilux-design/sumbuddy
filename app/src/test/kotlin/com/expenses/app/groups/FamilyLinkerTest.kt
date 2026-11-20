package com.expenses.app.groups

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.repository.TestZone
import com.expenses.app.data.repository.nov
import com.expenses.app.groups.api.ExpenseDto
import com.expenses.app.groups.api.ShareDto
import com.expenses.app.groups.api.VersionResponse
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.data.shareEntities
import com.expenses.app.groups.data.toEntity
import com.expenses.app.groups.sync.SyncOutcome
import com.expenses.parser.Direction
import com.expenses.splits.SplitMethod
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which payments are shared in the family month, and what of them (docs/family.md 7). */
@RunWith(RobolectricTestRunner::class)
class FamilyLinkerTest {
    private val env = GroupsTestEnv()
    private val dao = env.db.groupDao()
    private val expenseDao = env.db.expenseDao()

    @After fun close() = env.close()

    private fun path(id: String) = "/v1/groups/$FAMILY/expenses/$id"

    /** The family expense [id] as the server answers it: paid by [paidBy] for the whole [amount]. */
    private fun familyExpense(
        id: String,
        amount: Long = 234_000,
        version: Long = 4,
        paidBy: String = M_FME,
        occurredOn: String = "2026-11-15",
        category: String = "GROCERIES",
        description: String = "BigBasket",
    ) = ExpenseDto(
        id = id, groupId = FAMILY, version = version, description = description, category = category, amountPaise = amount,
        currency = "INR", occurredOn = occurredOn, paidByMemberId = paidBy, splitMethod = "EQUAL",
        shares = listOf(ShareDto(paidBy, amount, null)), createdByMemberId = paidBy, updatedByMemberId = paidBy, createdAt = 0, updatedAt = 0,
    )

    /** Stores [expense] as a pull would. */
    private suspend fun pulled(expense: ExpenseDto) = env.transactions.run {
        dao.upsertExpense(expense.toEntity(SyncState.SYNCED, env.clock.instant())!!)
        dao.deleteShares(expense.id)
        dao.insertShares(expense.shareEntities())
        env.mirrors.sync(expense.id)
    }

    /** Sends the outbox with the server accepting the create of family expense [id] at [version]. */
    private suspend fun accept(id: String, amount: Long, version: Long = 4, occurredOn: String = "2026-11-10", description: String = "Swiggy", category: String = "FOOD") {
        env.fake.on("PUT", path(id), json(201, ExpenseDto.serializer(), familyExpense(id, amount, version, occurredOn = occurredOn, description = description, category = category)))
        assertEquals(SyncOutcome.DONE, env.engine.push())
    }

    private suspend fun row(id: Long) = env.expenses.get(id)!!

    private suspend fun entries() = env.outbox.entries()

    @Test fun markingFamilyQueuesOneCreateWithOnlyTheSharedFields() = runTest {
        env.seedFamily()
        val id = env.smsPayment(45_000)
        val before = env.scheduler.requests.get()

        env.expenses.add(id, Category.FOOD, ExpenseScope.FAMILY, note = "for the party")

        val fid = env.familyExpenseId(1)
        val entry = entries().single()
        assertEquals(FAMILY, entry.groupId)
        assertEquals(EntityKind.EXPENSE, entry.entityKind)
        assertEquals(OutboxOp.CREATE, entry.op)
        assertEquals("PUT", entry.method)
        assertEquals(path(fid), entry.path)
        val body = Json.parseToJsonElement(entry.bodyJson!!).jsonObject
        assertEquals(
            setOf("description", "category", "amountPaise", "occurredOn", "paidByMemberId", "splitMethod", "auto", "shares", "baseVersion"),
            body.keys,
        )
        assertEquals("Swiggy", body.getValue("description").jsonPrimitive.content)
        assertEquals("FOOD", body.getValue("category").jsonPrimitive.content)
        assertEquals(45_000L, body.getValue("amountPaise").jsonPrimitive.long)
        assertEquals("2026-11-10", body.getValue("occurredOn").jsonPrimitive.content)
        assertEquals(M_FME, body.getValue("paidByMemberId").jsonPrimitive.content)
        assertEquals("EQUAL", body.getValue("splitMethod").jsonPrimitive.content)
        assertFalse(body.getValue("auto").jsonPrimitive.boolean)
        assertEquals(JsonNull, body.getValue("baseVersion"))
        val share = body.getValue("shares").jsonArray.single().jsonObject
        assertEquals(M_FME, share.getValue("memberId").jsonPrimitive.content)
        assertEquals(45_000L, share.getValue("sharePaise").jsonPrimitive.long)
        assertEquals(JsonNull, share.getValue("input"))
        // Never the note, account, card, "paid with", SMS text or sender, or the exact time.
        for (secret in listOf("party", "dinner", "XX1234", "debited", "123456789012", "VM-HDFCBK", "UPI", "20:15", "T14")) {
            assertFalse(secret, entry.bodyJson!!.contains(secret))
        }
        assertEquals(fid, row(id).familyExpenseId)
        assertEquals(ExpenseStatus.ADDED, row(id).status)
        val stored = dao.getExpenseWithShares(fid)!!
        assertEquals(SyncState.PENDING, stored.expense.syncState)
        assertEquals(45_000L, stored.shares.single().shareMinor)
        assertTrue(env.scheduler.requests.get() > before)

        // What reaches the server is that body, as it is.
        env.fake.on("PUT", path(fid), json(201, ExpenseDto.serializer(), familyExpense(fid, 45_000, occurredOn = "2026-11-10")))
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertEquals(entry.bodyJson, env.fake.requestsTo("PUT", path(fid)).single().body.readUtf8())
        assertEquals(fid, row(id).familyExpenseId)
        assertEquals(45_000L, row(id).myShareMinor)
    }

    @Test fun aPayeeThatIsAUpiIdOrANumberIsSentAsTheCategoryName() = runTest {
        env.seedFamily()
        val upi = env.smsPayment(12_300, counterparty = "meera.iyer@okaxis")
        val phone = env.smsPayment(45_600, counterparty = "9000000001")
        env.expenses.add(upi, Category.GROCERIES, ExpenseScope.FAMILY)
        env.expenses.add(phone, Category.GROCERIES, ExpenseScope.FAMILY)

        val sent = entries().map { Json.parseToJsonElement(it.bodyJson!!).jsonObject.getValue("description").jsonPrimitive.content }
        assertEquals(listOf("Groceries", "Groceries"), sent)
    }

    @Test fun justMeTakesItOutAndNothingIsSentWhenNothingWasSentYet() = runTest {
        env.seedFamily()
        val id = env.smsPayment(45_000)
        env.expenses.add(id, Category.FOOD, ExpenseScope.FAMILY)
        env.expenses.update(row(id).copy(scope = ExpenseScope.ME))

        assertEquals(listOf(OutboxOp.CREATE, OutboxOp.DELETE), entries().map { it.op })
        assertNull(row(id).familyExpenseId)
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertTrue(env.fake.requests.isEmpty())
        assertTrue(entries().isEmpty())
        assertNull(dao.getExpense(env.familyExpenseId(1)))
        // Counted in the person's own month, in full, as before.
        assertEquals(45_000L, row(id).myShareMinor)
    }

    @Test fun justMeAfterTheCreateWasSentDeletesIt() = runTest {
        env.seedFamily()
        val id = env.smsPayment(45_000)
        env.expenses.add(id, Category.FOOD, ExpenseScope.FAMILY)
        val fid = env.familyExpenseId(1)
        accept(fid, 45_000)

        env.expenses.update(row(id).copy(scope = ExpenseScope.ME))

        val delete = entries().single()
        assertEquals(OutboxOp.DELETE, delete.op)
        assertEquals("DELETE", delete.method)
        assertEquals(path(fid), delete.path)
        assertNull(delete.bodyJson)
        assertTrue(dao.getExpense(fid)!!.deleted)
        env.fake.on("DELETE", path(fid), json(200, VersionResponse.serializer(), VersionResponse(5)))
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertEquals(1, env.fake.requestsTo("DELETE", path(fid)).size)
        assertEquals(SyncState.SYNCED, dao.getExpense(fid)!!.syncState)
        assertEquals(ExpenseScope.ME, row(id).scope)
        assertNull(row(id).familyExpenseId)
    }

    @Test fun aCategoryEditQueuesAnUpdateWithTheBaseVersion() = runTest {
        env.seedFamily()
        val id = env.smsPayment(45_000)
        env.expenses.add(id, Category.FOOD, ExpenseScope.FAMILY)
        val fid = env.familyExpenseId(1)
        accept(fid, 45_000, version = 4)

        env.expenses.setCategory(id, Category.FUN)

        val update = entries().single()
        assertEquals(OutboxOp.UPDATE, update.op)
        assertEquals("PUT", update.method)
        val body = Json.parseToJsonElement(update.bodyJson!!).jsonObject
        assertEquals("FUN", body.getValue("category").jsonPrimitive.content)
        assertEquals(4L, body.getValue("baseVersion").jsonPrimitive.long)
        assertEquals(4L, env.outbox.restoreOf(update)!!.expense!!.version)
        assertNull(env.outbox.restoreOf(update)!!.personal)

        // The same category again, or a change of what is never shared (the note), sends nothing.
        env.expenses.setCategory(id, Category.FUN)
        env.expenses.update(row(id).copy(note = "the team's", paidWith = PaidWith.CARD))
        assertEquals(1, entries().size)
    }

    @Test fun ignoreUndoAddBackToWaitingAndDeleteTakeItOut() = runTest {
        env.seedFamily()
        val ignored = env.smsPayment(10_100)
        val undone = env.smsPayment(10_200)
        val waiting = env.smsPayment(10_300)
        val deleted = env.smsPayment(10_400)
        for (id in listOf(ignored, undone, waiting, deleted)) env.expenses.add(id, Category.FOOD, ExpenseScope.FAMILY)
        val linked = listOf(ignored, undone, waiting, deleted).associateWith { row(it).familyExpenseId!! }
        linked.forEach { (id, fid) ->
            env.fake.on("PUT", path(fid), json(201, ExpenseDto.serializer(), familyExpense(fid, row(id).amountMinor, occurredOn = "2026-11-10", category = "FOOD", description = "Swiggy")))
        }
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertTrue(entries().isEmpty())

        env.expenses.ignore(ignored)
        env.expenses.undoAdd(undone)
        env.expenses.backToWaiting(listOf(waiting))
        env.expenses.delete(deleted)

        val deletes = entries().filter { it.op == OutboxOp.DELETE }.map { it.entityId }
        assertEquals(linked.values.toSet(), deletes.toSet())
        assertEquals(4, entries().size)
        assertNull(row(ignored).familyExpenseId)
        assertNull(row(undone).familyExpenseId)
        assertNull(row(waiting).familyExpenseId)
        assertNull(env.expenses.get(deleted))
        linked.values.forEach { assertTrue(dao.getExpense(it)!!.deleted) }
    }

    @Test fun deletingOldSmsTakesTheirFamilyExpensesOut() = runTest {
        env.seedFamily()
        val old = env.familyPayment(234_000, day = 2)
        val recent = env.familyPayment(99_000, day = 14)
        env.transactions.run { env.familyLinker.reconcile() }
        val oldLink = row(old).familyExpenseId!!
        val recentLink = row(recent).familyExpenseId!!

        env.expenses.deleteSmsBefore(nov(10))

        assertNull(env.expenses.get(old))
        assertEquals(listOf(OutboxOp.CREATE, OutboxOp.CREATE, OutboxOp.DELETE), entries().map { it.op })
        assertEquals(oldLink, entries().last().entityId)
        assertEquals(recentLink, row(recent).familyExpenseId)
    }

    @Test fun splittingAFamilyPaymentTakesItOutAndUndoingTheSplitSharesItAgain() = runTest {
        env.seedGroup()
        env.seedFamily()
        val id = env.smsPayment(45_000)
        env.expenses.add(id, Category.FOOD, ExpenseScope.FAMILY)
        val first = env.familyExpenseId(1)
        accept(first, 45_000)

        val split = env.groups.splitPayment(id, G1, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = false, auto = false)

        assertNull(row(id).familyExpenseId)
        assertEquals(split, row(id).groupExpenseId)
        assertEquals(15_000L, row(id).myShareMinor)
        assertTrue(entries().any { it.entityId == first && it.op == OutboxOp.DELETE })

        env.groups.undoSplit(split)

        val again = env.familyExpenseId(2)
        assertNull(row(id).groupExpenseId)
        assertEquals(again, row(id).familyExpenseId)
        assertEquals(45_000L, row(id).myShareMinor)
        assertTrue(entries().any { it.entityId == again && it.op == OutboxOp.CREATE && it.groupId == FAMILY })
    }

    @Test fun creditsOtherCurrenciesWaitingRowsHugeAmountsAndLaterDatesAreNeverSent() = runTest {
        env.seedFamily()
        val credit = expenseDao.insert(
            Expense(
                amountMinor = 50_000, direction = Direction.CREDIT, counterparty = "Meera", category = Category.OTHER, occurredAt = nov(12),
                source = ExpenseSource.SMS, status = ExpenseStatus.ADDED, scope = ExpenseScope.FAMILY,
            ),
        )
        val dollars = expenseDao.insert(
            Expense(
                amountMinor = 2_000, currency = "USD", direction = Direction.DEBIT, counterparty = "Netflix", category = Category.FUN,
                occurredAt = nov(12), source = ExpenseSource.SMS, status = ExpenseStatus.ADDED, scope = ExpenseScope.FAMILY,
            ),
        )
        val waiting = env.smsPayment(45_000)
        env.expenses.update(row(waiting).copy(scope = ExpenseScope.FAMILY))
        env.expenses.update(row(credit))
        env.expenses.update(row(dollars))
        env.expenses.addManual(1_000_000_001, "Flat", Category.RENT_AND_BILLS, occurredAt = nov(12), scope = ExpenseScope.FAMILY)
        env.expenses.addManual(45_000, "Groceries", Category.GROCERIES, occurredAt = nov(18), scope = ExpenseScope.FAMILY)
        env.expenses.addManual(30_000, "Before 2000", Category.OTHER, occurredAt = LocalDate.of(1999, 12, 31).atStartOfDay(TestZone).toInstant(), scope = ExpenseScope.FAMILY)
        assertFalse(env.transactions.run { env.familyLinker.reconcile() })
        assertTrue(entries().isEmpty())

        // Tomorrow (India) and ₹1 crore are still taken.
        env.expenses.addManual(1_000_000_000, "Flat", Category.RENT_AND_BILLS, occurredAt = nov(17), scope = ExpenseScope.FAMILY)
        assertEquals(1, entries().size)
    }

    @Test fun nothingHappensWithoutAFamilyOrBeforeItsFirstPull() = runTest {
        val id = env.smsPayment(45_000)
        env.expenses.add(id, Category.FOOD, ExpenseScope.FAMILY)
        assertTrue(entries().isEmpty())
        assertFalse(env.transactions.run { env.familyLinker.reconcile() })

        env.seedFamily(version = 0, latestVersion = 3)
        env.expenses.setCategory(id, Category.GROCERIES)
        assertFalse(env.transactions.run { env.familyLinker.reconcile() })
        assertTrue(entries().isEmpty())
        assertNull(row(id).familyExpenseId)
    }

    /**
     * Code review (Family build): a payment is shared only once the family's data is complete. While a pull has not
     * ended or failed, the person's own family expense for it may still be on its way; the next complete reconcile
     * shares it. A shared row is still taken out and edited meanwhile.
     */
    @Test fun newRowsWaitForACompletePullThenReconcileSharesThem() = runTest {
        env.seedFamily(version = 3, latestVersion = 5)
        val id = env.smsPayment(45_000)
        env.expenses.add(id, Category.FOOD, ExpenseScope.FAMILY)
        assertTrue(entries().isEmpty())
        assertFalse(env.transactions.run { env.familyLinker.reconcile() })

        dao.setVersion(FAMILY, 5, 5)
        dao.setLoadFailed(FAMILY, true)
        env.expenses.setCategory(id, Category.GROCERIES)
        assertFalse(env.transactions.run { env.familyLinker.reconcile() })
        assertTrue(entries().isEmpty())
        assertNull(row(id).familyExpenseId)

        dao.setVersion(FAMILY, 5, 5)
        assertTrue(env.transactions.run { env.familyLinker.reconcile() })
        assertEquals(listOf(OutboxOp.CREATE), entries().map { it.op })
        val shared = row(id).familyExpenseId!!

        // A later pull runs: the shared row still follows the person's edits and Just me.
        dao.setLatestVersion(FAMILY, 9)
        env.expenses.setCategory(id, Category.FOOD)
        assertEquals(Category.FOOD, dao.getExpense(shared)!!.category)
        env.expenses.update(row(id).copy(scope = ExpenseScope.ME))
        assertNull(row(id).familyExpenseId)
        assertTrue(dao.getExpense(shared)!!.deleted)
        assertEquals(OutboxOp.DELETE, entries().last().op)
    }

    @Test fun reconcileSharesEarlierFamilyRowsOnceAndIsIdempotent() = runTest {
        val a = env.familyPayment(234_000, day = 3)
        val b = env.familyPayment(99_000, day = 9)
        val mine = env.smsPayment(45_000, status = ExpenseStatus.ADDED)
        env.seedFamily()

        assertTrue(env.transactions.run { env.familyLinker.reconcile() })
        assertEquals(2, entries().size)
        assertNotNull(row(a).familyExpenseId)
        assertNotNull(row(b).familyExpenseId)
        assertNull(row(mine).familyExpenseId)

        assertFalse(env.transactions.run { env.familyLinker.reconcile() })
        assertEquals(2, entries().size)
        // Field edits are never pushed by reconcile.
        expenseDao.update(row(a).copy(category = Category.FUN))
        assertFalse(env.transactions.run { env.familyLinker.reconcile() })
        // Eligibility is: a row made Just me without the listener is taken out.
        expenseDao.update(row(b).copy(scope = ExpenseScope.ME))
        assertTrue(env.transactions.run { env.familyLinker.reconcile() })
        assertEquals(listOf(OutboxOp.CREATE, OutboxOp.CREATE, OutboxOp.DELETE), entries().map { it.op })
    }

    @Test fun aPartnersFamilyExpenseNeverBecomesAPersonalRow() = runTest {
        env.seedFamily()
        val count = expenseDao.familyCandidates().size
        pulled(familyExpense("f0000000-0000-4000-8000-000000000101", paidBy = M_MEERA))

        assertEquals(count, expenseDao.familyCandidates().size)
        assertNull(expenseDao.findByFamilyExpense("f0000000-0000-4000-8000-000000000101"))
        assertNull(expenseDao.findByGroupExpense("f0000000-0000-4000-8000-000000000101"))
        assertEquals(0L, env.expenses.observeMonthTotal(java.time.YearMonth.of(2026, 11)).first())
    }

    @Test fun aRestoredOwnExpenseLinksTheOneMatchingPaymentOrMakesAMirror() = runTest {
        env.seedFamily()
        // A new phone: the bank SMS of the payment was read again and waits, Just me.
        val waiting = env.smsPayment(234_000, counterparty = "BIGBASKET")
        pulled(familyExpense("f0000000-0000-4000-8000-000000000201", amount = 234_000, occurredOn = "2026-11-10"))

        val linked = row(waiting)
        assertEquals("f0000000-0000-4000-8000-000000000201", linked.familyExpenseId)
        assertEquals(ExpenseScope.FAMILY, linked.scope)
        assertEquals(ExpenseStatus.ADDED, linked.status)
        assertEquals("BIGBASKET", linked.counterparty)
        assertNull(linked.groupExpenseId)

        // No payment of that amount: a mirror counts it.
        pulled(familyExpense("f0000000-0000-4000-8000-000000000202", amount = 99_900, occurredOn = "2026-11-15", description = "Rent"))
        val mirror = expenseDao.findByFamilyExpense("f0000000-0000-4000-8000-000000000202")!!
        assertEquals(ExpenseSource.GROUP, mirror.source)
        assertEquals(ExpenseScope.FAMILY, mirror.scope)
        assertEquals(ExpenseStatus.ADDED, mirror.status)
        assertEquals(99_900L, mirror.myShareMinor)
        assertEquals("Rent", mirror.counterparty)
        assertEquals(LocalDate.of(2026, 11, 15).atStartOfDay(TestZone).toInstant(), mirror.occurredAt)
        // Both are linked: nothing is shared again.
        assertFalse(env.transactions.run { env.familyLinker.reconcile() })
        assertTrue(entries().isEmpty())
    }

    @Test fun anSmsRowKeepsTheBanksAmountAndTakesTheCategoryAndATombstoneMakesItJustMe() = runTest {
        env.seedFamily()
        val id = env.familyPayment(234_000, day = 15)
        env.transactions.run { env.familyLinker.reconcile() }
        val fid = row(id).familyExpenseId!!
        accept(fid, 234_000, version = 4, occurredOn = "2026-11-15", description = "BigBasket", category = "GROCERIES")

        // Changed on another phone of the person.
        pulled(familyExpense(fid, amount = 1_000, version = 6, occurredOn = "2026-11-01", category = "FOOD", description = "Something else"))
        assertEquals(Category.FOOD, row(id).category)
        assertEquals(234_000L, row(id).amountMinor)
        assertEquals("BigBasket", row(id).counterparty)
        assertEquals(nov(15, 19, 42), row(id).occurredAt)
        assertTrue(entries().isEmpty())

        // Taken out on another phone of the person: a tombstone.
        env.transactions.run {
            dao.upsertExpense(dao.getExpense(fid)!!.copy(deleted = true, version = 7))
            dao.deleteShares(fid)
            env.mirrors.sync(fid)
        }
        assertNull(row(id).familyExpenseId)
        assertEquals(ExpenseScope.ME, row(id).scope)
        assertFalse(env.transactions.run { env.familyLinker.reconcile() })
    }

    @Test fun aTypedRowFollowsTheDescriptionAmountAndDate() = runTest {
        env.seedFamily()
        val id = env.expenses.addManual(50_000, "Milk", Category.GROCERIES, occurredAt = nov(12, 9, 0), scope = ExpenseScope.FAMILY)
        val fid = row(id).familyExpenseId!!
        accept(fid, 50_000, occurredOn = "2026-11-12", description = "Milk", category = "GROCERIES")

        pulled(familyExpense(fid, amount = 55_000, version = 6, occurredOn = "2026-11-13", category = "GROCERIES", description = "Milk and bread"))

        assertEquals("Milk and bread", row(id).counterparty)
        assertEquals(55_000L, row(id).amountMinor)
        assertEquals(55_000L, row(id).myShareMinor)
        assertEquals(LocalDate.of(2026, 11, 13).atStartOfDay(TestZone).toInstant(), row(id).occurredAt)
        assertTrue(entries().isEmpty())
    }

    @Test fun aRefusedCreateOrUpdateLeavesThePaymentJustMeWithNoNotSavedRow() = runTest {
        env.seedFamily()
        val created = env.smsPayment(45_000)
        env.expenses.add(created, Category.FOOD, ExpenseScope.FAMILY)
        val first = env.familyExpenseId(1)
        env.fake.on("PUT", path(first), error(400, "bad_request"))
        assertEquals(SyncOutcome.DONE, env.engine.push())

        assertNull(row(created).familyExpenseId)
        assertEquals(ExpenseScope.ME, row(created).scope)
        assertNull(dao.getExpense(first))
        assertTrue(dao.observeRejected(FAMILY).first().isEmpty())
        assertTrue(entries().isEmpty())

        val updated = env.smsPayment(12_000)
        env.expenses.add(updated, Category.FOOD, ExpenseScope.FAMILY)
        val second = env.familyExpenseId(2)
        env.fake.on(
            "PUT", path(second),
            json(201, ExpenseDto.serializer(), familyExpense(second, 12_000, version = 4, occurredOn = "2026-11-10", category = "FOOD", description = "Swiggy")),
            error(403, "not_allowed"),
        )
        env.fake.on("DELETE", path(second), json(200, VersionResponse.serializer(), VersionResponse(6)))
        assertEquals(SyncOutcome.DONE, env.engine.push())
        env.expenses.setCategory(updated, Category.FUN)
        assertEquals(SyncOutcome.DONE, env.engine.push())

        assertNull(row(updated).familyExpenseId)
        assertEquals(ExpenseScope.ME, row(updated).scope)
        assertEquals(Category.FUN, row(updated).category)
        // The server's copy came back and its delete was sent in the same run.
        assertEquals(1, env.fake.requestsTo("DELETE", path(second)).size)
        assertTrue(dao.getExpense(second)!!.deleted)
        assertTrue(dao.observeRejected(FAMILY).first().isEmpty())
        assertTrue(entries().isEmpty())
    }
}
