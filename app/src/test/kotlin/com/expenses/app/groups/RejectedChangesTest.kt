package com.expenses.app.groups

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.groups.data.ExpenseShareEntity
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.GroupExpenseEntity
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.data.RejectedChanges.RetryOutcome
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.sync.SyncOutcome
import com.expenses.splits.SplitMethod
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Try again and Dismiss on the Group page's "Not saved" row (design 02-everyday/43; docs/groups-build-plan.md 4.2
 * task 6): a refused typed create is made again with a new id, an edit and a delete are sent again, a split of the
 * person's own payment goes back to Split it, and refusals that would come again are not tried: a role rule, or a
 * change naming someone who left since. Two taps at once make the change once.
 */
@RunWith(RobolectricTestRunner::class)
class RejectedChangesTest {
    private val env = GroupsTestEnv()
    private val dao = env.db.groupDao()
    private val rejected = env.rejectedChanges

    @After fun close() = env.close()

    private val snacks = GroupExpenseDraft(
        "Snacks", Category.FOOD, 30_000, LocalDate.of(2026, 11, 12), M_RAHUL, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null),
    )

    /** An edit of [syncedExpense]: ₹900, still paid by Rahul and shared by the person and Rahul. */
    private val dinnerOut = GroupExpenseDraft(
        "Dinner out", Category.FOOD, 90_000, LocalDate.of(2026, 11, 10), M_RAHUL, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null),
    )

    /** A synced expense of ₹600 paid by Rahul, shared equally by the person and Rahul. */
    private suspend fun syncedExpense(id: String = "f0000000-0000-4000-8000-000000000001"): String {
        dao.upsertExpense(
            GroupExpenseEntity(
                id, G1, "Dinner", Category.FOOD, 60_000, LocalDate.of(2026, 11, 10), M_RAHUL, SplitMethod.EQUAL, auto = false,
                createdByMemberId = M_RAHUL, updatedByMemberId = M_RAHUL, updatedAt = Instant.EPOCH, deleted = false, version = 3,
                syncState = SyncState.SYNCED,
            ),
        )
        dao.insertShares(listOf(ExpenseShareEntity(id, M_ME, 30_000, null), ExpenseShareEntity(id, M_RAHUL, 30_000, null)))
        return id
    }

    private fun path(id: String) = "/v1/groups/$G1/expenses/$id"

    @Test fun aRefusedTypedCreateIsMadeAgainWithANewId() = runTest {
        env.seedGroup()
        val first = env.groups.addGroupExpense(G1, snacks)
        env.fake.on("PUT", path(first), error(409, "exists_different"))
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertNull(dao.getExpense(first))
        val change = rejected.observe(G1).first().single()
        assertTrue(rejected.canRetry(change, dao.membersOf(G1), stored = null))
        val tried = rejected.refusedExpense(change)!!
        assertEquals("Snacks", tried.description)
        assertEquals(30_000L, tried.amountMinor)

        assertEquals(RetryOutcome.Retried, rejected.retry(change.id))

        val again = env.madeId(2)
        val made = dao.getExpenseWithShares(again)!!
        assertEquals("Snacks", made.expense.description)
        assertEquals(M_RAHUL, made.expense.paidByMemberId)
        assertEquals(setOf(M_ME, M_RAHUL), made.shares.map { it.memberId }.toSet())
        val queued = env.outbox.entries().single()
        assertEquals(again, queued.entityId)
        assertEquals(OutboxOp.CREATE, queued.op)
        assertTrue(rejected.observe(G1).first().isEmpty())
        // The person's share of what Rahul paid is counted again.
        assertEquals(15_000L, env.expenses.findByGroupExpense(again)!!.myShareMinor)
    }

    @Test fun aRefusedEditIsSentAgain() = runTest {
        env.seedGroup()
        val id = syncedExpense()
        env.groups.updateGroupExpense(
            id,
            GroupExpenseDraft("Dinner out", Category.FOOD, 90_000, LocalDate.of(2026, 11, 10), M_RAHUL, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null)),
        )
        env.fake.on("PUT", path(id), error(400, "bad_request"))
        env.engine.push()
        // Rolled back to what the server has.
        assertEquals(60_000L, dao.getExpense(id)!!.amountMinor)
        val change = rejected.observe(G1).first().single()
        assertEquals(OutboxOp.UPDATE, change.op)
        assertEquals("Dinner out", rejected.refusedExpense(change)!!.description)

        assertEquals(RetryOutcome.Retried, rejected.retry(change.id))

        val edited = dao.getExpense(id)!!
        assertEquals("Dinner out", edited.description)
        assertEquals(90_000L, edited.amountMinor)
        assertEquals(SyncState.PENDING, edited.syncState)
        val queued = env.outbox.entries().single()
        assertEquals(OutboxOp.UPDATE, queued.op)
        assertEquals(id, queued.entityId)
        assertTrue(rejected.observe(G1).first().isEmpty())
    }

    @Test fun aRefusedDeleteIsSentAgain() = runTest {
        env.seedGroup()
        val id = syncedExpense()
        env.groups.deleteGroupExpense(id)
        env.fake.on("DELETE", path(id), error(409, "exists_different"))
        env.engine.push()
        assertFalse(dao.getExpense(id)!!.deleted)
        val change = rejected.observe(G1).first().single()
        assertEquals(OutboxOp.DELETE, change.op)
        // A delete has no body: the row is drawn from the expense as it was.
        assertEquals("Dinner", rejected.refusedExpense(change)!!.description)

        assertEquals(RetryOutcome.Retried, rejected.retry(change.id))

        assertTrue(dao.getExpense(id)!!.deleted)
        assertEquals(OutboxOp.DELETE, env.outbox.entries().single().op)
        assertTrue(rejected.observe(G1).first().isEmpty())
    }

    @Test fun aRefusedSplitOfTheirOwnPaymentGoesBackToSplitIt() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        val id = env.groups.splitPayment(
            payment, G1, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = false, auto = false,
        )
        env.fake.on("PUT", path(id), error(409, "exists_different"))
        env.engine.push()
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(payment)!!.status)
        val change = rejected.observe(G1).first().single()

        assertEquals(RetryOutcome.SplitPayment(payment), rejected.retry(change.id))

        // Nothing is made here: Split it does it, and the payment still waits.
        assertTrue(env.outbox.entries().isEmpty())
        assertNull(env.expenses.get(payment)!!.groupExpenseId)
        assertTrue(rejected.observe(G1).first().isEmpty())
    }

    @Test fun refusalsThatWouldComeAgainAreNotTried() = runTest {
        env.seedGroup()
        val first = env.groups.addGroupExpense(G1, snacks)
        env.fake.on("PUT", path(first), error(403, "not_allowed"))
        env.engine.push()
        val change = rejected.observe(G1).first().single()

        assertFalse(rejected.canRetry(change, dao.membersOf(G1), stored = null))
        assertEquals(RetryOutcome.NotPossible, rejected.retry(change.id))
        assertNotNull(dao.getRejected(change.id))
        assertTrue(env.outbox.entries().isEmpty())
    }

    @Test fun aDoubleTryAgainMakesTheExpenseOnce() = runTest {
        env.seedGroup()
        val first = env.groups.addGroupExpense(G1, snacks)
        env.fake.on("PUT", path(first), error(409, "exists_different"))
        env.engine.push()
        val change = rejected.observe(G1).first().single()

        // Two taps at once: both start before either has forgotten the row.
        val outcomes = coroutineScope { listOf(async { rejected.retry(change.id) }, async { rejected.retry(change.id) }).awaitAll() }

        assertEquals(setOf(RetryOutcome.Retried, RetryOutcome.Gone), outcomes.toSet())
        val queued = env.outbox.entries()
        assertEquals(1, queued.size)
        assertEquals(OutboxOp.CREATE, queued.single().op)
        assertEquals(env.madeId(2), queued.single().entityId)
        assertNotNull(dao.getExpense(env.madeId(2)))
        assertNull(dao.getExpense(env.madeId(3)))
        assertNull(dao.getRejected(change.id))
    }

    @Test fun aCreateNamingSomeoneWhoLeftIsNotTriedAgain() = runTest {
        env.seedGroup()
        val withPriya = snacks.copy(inputs = mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null))
        val first = env.groups.addGroupExpense(G1, withPriya)
        env.fake.on("PUT", path(first), error(400, "bad_request"))
        env.engine.push()
        val change = rejected.observe(G1).first().single()
        assertTrue(rejected.canRetry(change, dao.membersOf(G1), stored = null))

        // Priya leaves before the person taps Try again.
        dao.upsertMembers(listOf(dao.getMember(M_PRIYA)!!.copy(removed = true)))

        assertFalse(rejected.canRetry(change, dao.membersOf(G1), stored = null))
        assertEquals(RetryOutcome.NotPossible, rejected.retry(change.id))
        assertNotNull(dao.getRejected(change.id))
        assertTrue(env.outbox.entries().isEmpty())
        assertNull(dao.getExpense(env.madeId(2)))
    }

    @Test fun anEditMayKeepSomeoneWhoLeft() = runTest {
        env.seedGroup()
        val id = syncedExpense()
        env.groups.updateGroupExpense(id, dinnerOut)
        env.fake.on("PUT", path(id), error(400, "bad_request"))
        env.engine.push()
        val change = rejected.observe(G1).first().single()
        // Rahul, the payer and a share the server already has, leaves: the server takes him unchanged (4.7).
        dao.upsertMembers(listOf(dao.getMember(M_RAHUL)!!.copy(removed = true)))

        assertTrue(rejected.canRetry(change, dao.membersOf(G1), dao.getExpenseWithShares(id)))
        assertEquals(RetryOutcome.Retried, rejected.retry(change.id))
        assertEquals(OutboxOp.UPDATE, env.outbox.entries().single().op)
        assertNull(dao.getRejected(change.id))
    }

    @Test fun anEditAddingSomeoneWhoLeftIsNotTriedAgain() = runTest {
        env.seedGroup()
        val id = syncedExpense()
        env.groups.updateGroupExpense(id, dinnerOut.copy(inputs = mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null)))
        env.fake.on("PUT", path(id), error(400, "bad_request"))
        env.engine.push()
        val change = rejected.observe(G1).first().single()
        // Priya, whom the edit adds, leaves before Try again.
        dao.upsertMembers(listOf(dao.getMember(M_PRIYA)!!.copy(removed = true)))

        assertFalse(rejected.canRetry(change, dao.membersOf(G1), dao.getExpenseWithShares(id)))
        assertEquals(RetryOutcome.NotPossible, rejected.retry(change.id))
        assertNotNull(dao.getRejected(change.id))
        assertTrue(env.outbox.entries().isEmpty())
        assertEquals(60_000L, dao.getExpense(id)!!.amountMinor)
    }

    @Test fun dismissForgetsTheRow() = runTest {
        env.seedGroup()
        val first = env.groups.addGroupExpense(G1, snacks)
        env.fake.on("PUT", path(first), error(409, "exists_different"))
        env.engine.push()
        val change = rejected.observe(G1).first().single()

        rejected.dismiss(change.id)

        assertTrue(rejected.observe(G1).first().isEmpty())
        assertEquals(RetryOutcome.Gone, rejected.retry(change.id))
        assertTrue(env.outbox.entries().isEmpty())
    }
}
