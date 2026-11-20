package com.expenses.app.groups

import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.groups.api.ChangeDto
import com.expenses.app.groups.api.ChangesPage
import com.expenses.app.groups.api.ExpenseDto
import com.expenses.app.groups.api.GroupDto
import com.expenses.app.groups.api.LostGroupDto
import com.expenses.app.groups.api.LostReason
import com.expenses.app.groups.api.MeGroupDto
import com.expenses.app.groups.api.MeGroupsResponse
import com.expenses.app.groups.api.MemberDto
import com.expenses.app.groups.api.ShareDto
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.data.familyLoadState
import com.expenses.app.groups.sync.GroupEvent
import com.expenses.app.groups.sync.SyncOutcome
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The sync engine with a family (docs/family.md 7.4, 9): pulls, reconcile, losing it, accounts, notifications. */
@RunWith(RobolectricTestRunner::class)
class SyncEngineFamilyTest {
    private val env = GroupsTestEnv()
    private val dao = env.db.groupDao()
    private val expenseDao = env.db.expenseDao()

    @After fun close() = env.close()

    private val changesPath = "/v1/groups/$FAMILY/changes"

    private fun path(id: String) = "/v1/groups/$FAMILY/expenses/$id"

    private fun listed(version: Long, lost: Map<String, String> = emptyMap()) = json(
        200, MeGroupsResponse.serializer(),
        MeGroupsResponse(
            listOf(MeGroupDto(FAMILY, "family", "Home", version, M_FME, ME_USER)).takeIf { version > 0 }.orEmpty(),
            lost = lost.map { (id, reason) -> LostGroupDto(id, reason) },
        ),
    )

    private fun familyPage(
        from: Long,
        to: Long,
        members: List<MemberDto> = emptyList(),
        expenses: List<ExpenseDto> = emptyList(),
        changes: List<ChangeDto> = emptyList(),
    ) = json(
        200, ChangesPage.serializer(),
        ChangesPage(FAMILY, from, to, to, false, GroupDto(FAMILY, "family", "Home", ME_USER, to, 0, ownerMemberId = M_FME), members, expenses, emptyList(), changes),
    )

    private val meDto = MemberDto(M_FME, FAMILY, ME_USER, "Arjun Mehta", onApp = true, version = 1)
    private val meeraDto = MemberDto(M_MEERA, FAMILY, MEERA_USER, "Meera Iyer", onApp = true, version = 2)

    private fun familyExpense(id: String, paidBy: String, amount: Long, version: Long, occurredOn: String = "2026-11-15", category: String = "GROCERIES") =
        ExpenseDto(
            id = id, groupId = FAMILY, version = version, description = "BigBasket", category = category, amountPaise = amount, currency = "INR",
            occurredOn = occurredOn, paidByMemberId = paidBy, splitMethod = "EQUAL", shares = listOf(ShareDto(paidBy, amount, null)),
            createdByMemberId = paidBy, updatedByMemberId = paidBy, createdAt = 0, updatedAt = 0,
        )

    @Test fun joiningPullsTheFamilyAsAFamilyThenSharesTheEarlierFamilyRows() = runTest {
        val a = env.familyPayment(234_000, day = 3)
        val b = env.familyPayment(99_000, day = 9)
        env.fake.on("GET", "/v1/me/groups", listed(3))
        env.fake.on(
            "GET", changesPath,
            familyPage(0, 3, members = listOf(meDto, meeraDto), expenses = listOf(familyExpense("f0000000-0000-4000-8000-000000000601", M_MEERA, 50_000, 3))),
        )
        val before = env.scheduler.requests.get()

        assertEquals(SyncOutcome.DONE, env.engine.sync())

        val family = dao.getGroup(FAMILY)!!
        assertEquals(GroupKind.FAMILY, family.kind)
        assertEquals("Home", family.name)
        assertEquals(LoadState.READY, familyLoadState(family))
        assertEquals(listOf(OutboxOp.CREATE, OutboxOp.CREATE), env.outbox.entries().map { it.op })
        assertTrue(env.outbox.entries().all { it.groupId == FAMILY })
        assertNotNull(env.expenses.get(a)!!.familyExpenseId)
        assertNotNull(env.expenses.get(b)!!.familyExpenseId)
        // Queued after the pull: a sync is asked for at once. A pull from 0 posts nothing; the partner's expense makes
        // no personal row.
        assertTrue(env.scheduler.requests.get() > before)
        assertTrue(env.notifier.events.isEmpty())
        assertNull(expenseDao.findByFamilyExpense("f0000000-0000-4000-8000-000000000601"))
    }

    @Test fun reconcileRunsBeforeThePushSoARowTaggedWithoutTheListenerIsSentInTheSameRun() = runTest {
        env.seedFamily(version = 3)
        val id = env.familyPayment(234_000, day = 15)
        val fid = env.familyExpenseId(1)
        env.fake.on("PUT", path(fid), json(201, ExpenseDto.serializer(), familyExpense(fid, M_FME, 234_000, 4)))
        env.fake.on("GET", "/v1/me/groups", listed(4))
        env.fake.on("GET", changesPath, familyPage(3, 4, expenses = listOf(familyExpense(fid, M_FME, 234_000, 4))))

        assertEquals(SyncOutcome.DONE, env.engine.sync())

        assertEquals(1, env.fake.requestsTo("PUT", path(fid)).size)
        assertEquals(fid, env.expenses.get(id)!!.familyExpenseId)
        assertTrue(env.outbox.entries().isEmpty())
    }

    @Test fun aFamilyLostOrLeftOnAnotherPhoneUnlinksTheRowsAndKeepsTheirTag() = runTest {
        env.seedFamily(version = 3)
        val id = env.familyPayment()
        env.transactions.run { env.familyLinker.reconcile() }
        val fid = env.expenses.get(id)!!.familyExpenseId!!
        env.fake.on("PUT", path(fid), json(201, ExpenseDto.serializer(), familyExpense(fid, M_FME, 234_000, 4)))
        env.fake.on("GET", "/v1/me/groups", listed(0, lost = mapOf(FAMILY to LostReason.REMOVED)))

        assertEquals(SyncOutcome.DONE, env.engine.sync())

        assertNull(dao.getGroup(FAMILY))
        val row = env.expenses.get(id)!!
        assertNull(row.familyExpenseId)
        assertEquals(ExpenseScope.FAMILY, row.scope)
        assertTrue(env.outbox.entries().isEmpty())
    }

    @Test fun anotherAccountUnlinksEverything() = runTest {
        env.seedFamily()
        val id = env.familyPayment()
        env.transactions.run { env.familyLinker.reconcile() }

        env.local.switchAccount()

        assertNull(env.expenses.get(id)!!.familyExpenseId)
        assertEquals(ExpenseScope.FAMILY, env.expenses.get(id)!!.scope)
    }

    @Test fun signingOutKeepsTheLinksAndTheSameAccountSigningInAgainFindsThem() = runTest {
        env.seedFamily(version = 3)
        val id = env.familyPayment(234_000, day = 15)
        val fid = env.familyExpenseId(1)
        env.fake.on("PUT", path(fid), json(201, ExpenseDto.serializer(), familyExpense(fid, M_FME, 234_000, 4)))
        env.fake.on("GET", "/v1/me/groups", listed(4))
        env.fake.on("GET", changesPath, familyPage(3, 4))
        assertEquals(SyncOutcome.DONE, env.engine.sync())
        val rows = expenseDao.familyCandidates().size

        env.local.clearAll()
        assertNull(dao.getGroup(FAMILY))
        assertEquals(fid, env.expenses.get(id)!!.familyExpenseId)

        // Signed in again: the family comes back from version 0 with the person's own expense in it.
        env.fake.on("GET", changesPath, familyPage(0, 4, members = listOf(meDto, meeraDto), expenses = listOf(familyExpense(fid, M_FME, 234_000, 4, category = "FOOD"))))
        assertEquals(SyncOutcome.DONE, env.engine.sync())

        assertEquals(fid, env.expenses.get(id)!!.familyExpenseId)
        assertEquals(rows, expenseDao.familyCandidates().size)
        assertTrue(expenseDao.familyCandidates().none { it.source == ExpenseSource.GROUP })
        assertTrue(env.outbox.entries().isEmpty())
        // The category the person chose on another phone of theirs is taken; nothing is sent back.
        assertEquals(com.expenses.app.data.category.Category.FOOD, env.expenses.get(id)!!.category)
        assertEquals(1, env.fake.requestsTo("PUT", path(fid)).size)
    }

    @Test fun thePartnersExpenseMakesNoNotificationAndTheirJoinSaysTheFamilysName() = runTest {
        env.seedFamily(version = 3, partner = false, placeholder = true)
        val now = env.clock.millis()
        val joined = MemberDto(M_PLACEHOLDER, FAMILY, MEERA_USER, "Meera Iyer", onApp = true, version = 4)
        val expense = familyExpense("f0000000-0000-4000-8000-000000000701", M_PLACEHOLDER, 120_000, 5)
        env.fake.on("GET", "/v1/me/groups", listed(5))
        env.fake.on(
            "GET", changesPath,
            familyPage(
                3, 5, members = listOf(joined), expenses = listOf(expense),
                changes = listOf(
                    ChangeDto(4, "member", "join", M_PLACEHOLDER, actorMemberId = M_PLACEHOLDER, at = now),
                    ChangeDto(5, "expense", "create", expense.id, actorMemberId = M_PLACEHOLDER, at = now),
                ),
            ),
        )

        assertEquals(SyncOutcome.DONE, env.engine.sync())

        assertEquals(listOf<GroupEvent>(GroupEvent.MemberJoined(FAMILY, M_PLACEHOLDER, "Meera", "Home", family = true)), env.notifier.events.toList())
        assertNull(expenseDao.findByFamilyExpense(expense.id))
        assertEquals(LoadState.READY, familyLoadState(dao.getGroup(FAMILY)!!))
    }
}
