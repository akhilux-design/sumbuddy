package com.expenses.app.groups

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.ChangeDto
import com.expenses.app.groups.api.ChangesPage
import com.expenses.app.groups.api.CreateGroupResponse
import com.expenses.app.groups.api.ExpenseDto
import com.expenses.app.groups.api.GroupDto
import com.expenses.app.groups.api.LostReason
import com.expenses.app.groups.api.MeGroupsResponse
import com.expenses.app.groups.api.SettlementDto
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.NewPerson
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.data.shareEntities
import com.expenses.app.groups.data.toEntity
import com.expenses.app.groups.sync.GroupEvent
import com.expenses.app.groups.sync.SyncOutcome
import com.expenses.splits.SplitMethod
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private val env = GroupsTestEnv()
    private val dao = env.db.groupDao()
    private val november = YearMonth.of(2026, 11)
    private val equalThree = mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null)

    @After fun close() = env.close()

    private fun expensePath(id: String, group: String = G1) = "/v1/groups/$group/expenses/$id"

    private fun changesPath(group: String = G1) = "/v1/groups/$group/changes"

    private suspend fun storeSynced(expense: ExpenseDto) = env.transactions.run {
        dao.upsertExpense(expense.toEntity(SyncState.SYNCED, env.clock.instant())!!)
        dao.deleteShares(expense.id)
        dao.insertShares(expense.shareEntities())
        env.mirrors.sync(expense.id)
    }

    @Test fun sendsTheOutboxInOrderWithItsKeysThenPulls() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        val expenseId = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, equalThree, rememberRule = false, auto = false)
        val goa = env.groups.createGroup("Goa trip", listOf(NewPerson.Friend("Kiran", "90000 00001")))
        val keys = env.outbox.entries().map { it.idempotencyKey }
        val accepted = expenseDto(expenseId, M_ME, 45_000, mapOf(M_ME to 15_000, M_PRIYA to 15_000, M_RAHUL to 15_000), version = 6)
        env.fake.on("PUT", expensePath(expenseId), json(201, ExpenseDto.serializer(), accepted))
        env.fake.on(
            "POST", "/v1/groups",
            json(201, CreateGroupResponse.serializer(), CreateGroupResponse(GroupDto(goa, "group", "Goa trip", ME_USER, 1, 0), emptyList())),
        )
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 6, goa to 1)))
        env.fake.on("GET", changesPath(), json(200, ChangesPage.serializer(), page(from = 5, to = 6, expenses = listOf(accepted))))
        env.fake.on("GET", changesPath(goa), json(200, ChangesPage.serializer(), page(goa, from = 0, to = 1, name = "Goa trip")))

        assertEquals(SyncOutcome.DONE, env.engine.sync())

        val writes = env.fake.requests.filter { it.method != "GET" }
        assertEquals(listOf("PUT ${expensePath(expenseId)}", "POST /v1/groups"), writes.map { "${it.method} ${it.requestUrl!!.encodedPath}" })
        assertEquals(keys, writes.map { it.getHeader("Idempotency-Key") })
        assertTrue(env.fake.requests.all { it.getHeader("Authorization") == "Bearer access-1" })
        assertTrue(env.fake.requests.all { it.getHeader("X-Installation-Id") == "d0000000-0000-4000-8000-000000000001" })
        assertTrue(env.outbox.entries().isEmpty())
        assertEquals(6L, dao.getGroup(G1)!!.version)
        assertEquals(SyncState.SYNCED, dao.getExpense(expenseId)!!.syncState)
        assertEquals(SyncState.SYNCED, dao.getGroup(goa)!!.syncState)
        // Only the person's share counts in the month.
        val row = env.expenses.get(payment)!!
        assertEquals(ExpenseStatus.ADDED, row.status)
        assertEquals(15_000L, row.myShareMinor)
        assertEquals(expenseId, row.groupExpenseId)
        assertEquals(15_000L, env.expenses.observeMonthTotal(november).first())
    }

    @Test fun aRetryAfterAServerErrorSendsTheSameKey() = runTest {
        env.seedGroup()
        val id = env.groups.addGroupExpense(G1, draft(paidBy = M_RAHUL))
        env.fake.on(
            "PUT", expensePath(id),
            error(503, "internal"),
            json(201, ExpenseDto.serializer(), expenseDto(id, M_RAHUL, 30_000, mapOf(M_ME to 15_000, M_RAHUL to 15_000), 6)),
        )
        assertEquals(SyncOutcome.RETRY, env.engine.push())
        assertEquals(1, env.outbox.entries().single().failures)
        assertEquals(SyncOutcome.DONE, env.engine.push())
        val sent = env.fake.requestsTo("PUT", expensePath(id))
        assertEquals(2, sent.size)
        assertEquals(sent[0].getHeader("Idempotency-Key"), sent[1].getHeader("Idempotency-Key"))
        assertTrue(env.outbox.entries().isEmpty())
    }

    @Test fun undoBeforeSendingSendsNothing() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        val id = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, equalThree, rememberRule = false, auto = false)
        env.groups.undoSplit(id)

        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertTrue(env.fake.requests.isEmpty())
        assertNull(dao.getExpense(id))
        val row = env.expenses.get(payment)!!
        assertEquals(ExpenseStatus.DETECTED, row.status)
        assertEquals(45_000L, row.myShareMinor)
        assertNull(row.groupExpenseId)
    }

    @Test fun anAttemptedCreateIsNeverCoalesced() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        val id = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, equalThree, rememberRule = false, auto = false)
        env.fake.on(
            "PUT", expensePath(id),
            error(502, "internal"),
            json(201, ExpenseDto.serializer(), expenseDto(id, M_ME, 45_000, mapOf(M_ME to 15_000, M_PRIYA to 15_000, M_RAHUL to 15_000), 6)),
        )
        env.fake.on("DELETE", expensePath(id), json(200, """{"version":7}"""))
        assertEquals(SyncOutcome.RETRY, env.engine.push())
        // The create may already be on the server: Undo must send it and then delete it.
        env.groups.undoSplit(id)
        assertEquals(SyncOutcome.DONE, env.engine.push())

        assertEquals(listOf("PUT", "PUT", "DELETE"), env.fake.requests.map { it.method })
        assertTrue(dao.getExpense(id)!!.deleted)
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(payment)!!.status)
        assertEquals(45_000L, env.expenses.get(payment)!!.myShareMinor)
    }

    @Test fun aCreateAndItsUpdatesAreSentAsOnePut() = runTest {
        env.seedGroup()
        val id = env.groups.addGroupExpense(G1, draft(paidBy = M_RAHUL, amount = 30_000))
        env.groups.updateGroupExpense(id, draft(paidBy = M_RAHUL, amount = 40_000))
        env.groups.updateGroupExpense(id, draft(paidBy = M_RAHUL, amount = 50_000))
        val lastKey = env.outbox.entries().last().idempotencyKey
        env.fake.on("PUT", expensePath(id), json(201, ExpenseDto.serializer(), expenseDto(id, M_RAHUL, 50_000, mapOf(M_ME to 25_000, M_RAHUL to 25_000), 6)))

        assertEquals(SyncOutcome.DONE, env.engine.push())

        val sent = env.fake.requestsTo("PUT", expensePath(id)).single()
        assertEquals(lastKey, sent.getHeader("Idempotency-Key"))
        val body = ApiJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals(50_000L, body["amountPaise"]!!.jsonPrimitive.long)
        assertEquals(25_000L, env.db.expenseDao().findByGroupExpense(id)!!.myShareMinor)
    }

    @Test fun aRefusedUpdateIsPutBack() = runTest {
        env.seedGroup()
        val id = "f0000000-0000-4000-8000-000000000001"
        storeSynced(expenseDto(id, M_RAHUL, 30_000, mapOf(M_ME to 15_000, M_RAHUL to 15_000), version = 5))
        assertEquals(15_000L, env.db.expenseDao().findByGroupExpense(id)!!.myShareMinor)
        env.groups.updateGroupExpense(id, draft(paidBy = M_RAHUL, amount = 90_000))
        assertEquals(45_000L, env.db.expenseDao().findByGroupExpense(id)!!.myShareMinor)
        env.fake.on("PUT", expensePath(id), error(400, "bad_request"))

        assertEquals(SyncOutcome.DONE, env.engine.push())

        val back = dao.getExpenseWithShares(id)!!
        assertEquals(30_000L, back.expense.amountMinor)
        assertEquals(SyncState.REJECTED, back.expense.syncState)
        assertEquals(setOf(M_ME, M_RAHUL), back.shares.map { it.memberId }.toSet())
        assertEquals(15_000L, env.db.expenseDao().findByGroupExpense(id)!!.myShareMinor)
        assertTrue(env.outbox.entries().isEmpty())
    }

    @Test fun aRefusedSplitPutsThePaymentBack() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        val id = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, equalThree, rememberRule = false, auto = false)
        env.fake.on("PUT", expensePath(id), error(409, "exists_different"))

        assertEquals(SyncOutcome.DONE, env.engine.push())

        assertNull(dao.getExpense(id))
        val row = env.expenses.get(payment)!!
        assertEquals(ExpenseStatus.DETECTED, row.status)
        assertEquals(45_000L, row.myShareMinor)
        assertNull(row.groupExpenseId)
    }

    @Test fun aStuckEntryHoldsBackOnlyItsOwnGroup() = runTest {
        env.seedGroup(G1)
        env.seedGroup(G2, myMember = M_ME2, others = listOf())
        val first = env.groups.addGroupExpense(G1, draft(paidBy = M_RAHUL))
        val second = env.groups.addGroupExpense(G1, draft(paidBy = M_RAHUL))
        val other = env.groups.addGroupExpense(G2, draft(paidBy = M_ME2, inputs = mapOf(M_ME2 to null)))
        val stuck = env.outbox.entries().first { it.entityId == first }
        env.db.outboxDao().update(stuck.copy(attempts = 4, failures = 4))
        env.fake.on("PUT", expensePath(first), error(500, "internal"))
        env.fake.on("PUT", expensePath(other, G2), json(201, ExpenseDto.serializer(), expenseDto(other, M_ME2, 30_000, mapOf(M_ME2 to 30_000), 6, groupId = G2)))

        // Parked, not retried by WorkManager's back-off: that would hold every other group's changes too.
        assertEquals(SyncOutcome.DONE, env.engine.push())

        assertEquals(listOf(expensePath(first), expensePath(other, G2)), env.fake.requests.map { it.requestUrl!!.encodedPath })
        assertEquals(setOf(first, second), env.outbox.entries().map { it.entityId }.toSet())
        assertEquals(5, env.outbox.entries().first { it.entityId == first }.failures)
    }

    @Test fun aServerErrorStopsEverythingUntilAnEntryIsStuck() = runTest {
        env.seedGroup(G1)
        env.seedGroup(G2, myMember = M_ME2, others = listOf())
        val first = env.groups.addGroupExpense(G1, draft(paidBy = M_RAHUL))
        env.groups.addGroupExpense(G2, draft(paidBy = M_ME2, inputs = mapOf(M_ME2 to null)))
        env.fake.on("PUT", expensePath(first), error(500, "internal"))

        assertEquals(SyncOutcome.RETRY, env.engine.push())
        assertEquals(1, env.fake.requests.size)
    }

    @Test fun ourRateLimitIsWaitedOut() = runTest {
        env.seedGroup()
        val id = env.groups.addGroupExpense(G1, draft(paidBy = M_RAHUL))
        env.fake.on(
            "PUT", expensePath(id),
            error(429, "rate_limited").setHeader("Retry-After", "7"),
            json(201, ExpenseDto.serializer(), expenseDto(id, M_RAHUL, 30_000, mapOf(M_ME to 15_000, M_RAHUL to 15_000), 6)),
        )
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertEquals(listOf(7_000L), env.sleeps.toList())
        assertTrue(env.outbox.entries().isEmpty())
    }

    @Test fun offlineKeepsEverything() = runTest {
        env.seedGroup()
        env.groups.addGroupExpense(G1, draft(paidBy = M_RAHUL))
        env.server.shutdown()
        assertEquals(SyncOutcome.RETRY, env.engine.sync())
        assertEquals(1, env.outbox.entries().size)
        assertEquals(0, env.outbox.entries().single().failures)
    }

    @Test fun signedOutStopsWithoutLosingAnything() = runTest {
        env.seedGroup()
        env.groups.addGroupExpense(G1, draft(paidBy = M_RAHUL))
        env.session.token = null
        assertEquals(SyncOutcome.SIGNED_OUT, env.engine.sync())
        assertEquals(1, env.outbox.entries().size)
        assertNotNull(dao.getGroup(G1))
    }

    @Test fun aRefreshThatFailsForNowIsRetriedNotSignedOut() = runTest {
        env.seedGroup()
        env.groups.addGroupExpense(G1, draft(paidBy = M_RAHUL))
        env.session.unavailable = ApiError(503, ApiError.INTERNAL, fromServer = false)
        assertEquals(SyncOutcome.RETRY, env.engine.sync())
        env.session.unavailable = ApiError.offline
        assertEquals(SyncOutcome.RETRY, env.engine.sync())
        assertEquals(1, env.outbox.entries().size)
        assertTrue(env.fake.requests.isEmpty())
    }

    @Test fun aRollbackPutsBackTheServersNewerSettlementThatAPullSkipped() = runTest {
        // Rahul recorded that he paid me; I confirm offline; meanwhile he deletes it (version 10).
        env.seedGroup(version = 5)
        val sid = "f1000000-0000-4000-8000-000000000001"
        val recorded = SettlementDto(
            sid, G1, deleted = false, version = 5, fromMemberId = M_RAHUL, toMemberId = M_ME, amountPaise = 50_000, method = "CASH",
            occurredOn = "2026-11-12", recordedByMemberId = M_RAHUL,
        )
        dao.upsertSettlement(recorded.toEntity(SyncState.SYNCED)!!)
        env.groups.confirmSettlement(sid)
        val confirm = "/v1/groups/$G1/settlements/$sid/confirm"
        env.fake.on("POST", confirm, error(503, "internal"), error(404, "not_found"))
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 10)))
        env.fake.on("GET", changesPath(), json(200, ChangesPage.serializer(), page(from = 5, to = 10, settlements = listOf(SettlementDto(sid, G1, deleted = true, version = 10)))))

        // The confirm fails for now; the pull still runs and skips the tombstone, since a change of it waits.
        assertEquals(SyncOutcome.RETRY, env.engine.sync())
        assertEquals(10L, dao.getGroup(G1)!!.version)
        assertTrue(!dao.getSettlement(sid)!!.deleted)

        // Refused: rolled back to the server's copy (deleted), not to the copy the person confirmed.
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertTrue(dao.getSettlement(sid)!!.deleted)
        assertTrue(env.outbox.entries().isEmpty())
    }

    @Test fun aRefusedUpdateAfterAPullGetsTheServersNewerCopy() = runTest {
        env.seedGroup(version = 5)
        val id = "f0000000-0000-4000-8000-000000000007"
        storeSynced(expenseDto(id, M_RAHUL, 30_000, mapOf(M_ME to 15_000, M_RAHUL to 15_000), version = 5))
        env.groups.updateGroupExpense(id, draft(paidBy = M_RAHUL, amount = 90_000))
        assertEquals(45_000L, env.db.expenseDao().findByGroupExpense(id)!!.myShareMinor)
        // Rahul changed it meanwhile (version 8): 60,000 split equally.
        val newer = expenseDto(id, M_RAHUL, 60_000, mapOf(M_ME to 30_000, M_RAHUL to 30_000), version = 8)
        env.fake.on("PUT", expensePath(id), error(503, "internal"), error(400, "bad_request"))
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 8)))
        env.fake.on("GET", changesPath(), json(200, ChangesPage.serializer(), page(from = 5, to = 8, expenses = listOf(newer))))

        assertEquals(SyncOutcome.RETRY, env.engine.sync())
        assertEquals(90_000L, dao.getExpense(id)!!.amountMinor)
        assertEquals(SyncOutcome.DONE, env.engine.push())

        val back = dao.getExpenseWithShares(id)!!
        assertEquals(60_000L, back.expense.amountMinor)
        assertEquals(8L, back.expense.version)
        assertEquals(30_000L, env.db.expenseDao().findByGroupExpense(id)!!.myShareMinor)
    }

    @Test fun aRolledBackPayerChangeTakesTheLinkBackFromTheMirror() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(30_000)
        val id = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null), rememberRule = false, auto = false)
        env.fake.on("PUT", expensePath(id), json(201, ExpenseDto.serializer(), expenseDto(id, M_ME, 30_000, mapOf(M_ME to 15_000, M_RAHUL to 15_000), 6)))
        env.engine.push()
        // Rahul is made the payer: my payment counts in full again and a mirror counts my share.
        env.groups.updateGroupExpense(id, draft(paidBy = M_RAHUL, amount = 30_000))
        assertEquals(ExpenseSource.GROUP, env.db.expenseDao().findByGroupExpense(id)!!.source)
        env.fake.on("PUT", expensePath(id), error(400, "bad_request"))

        env.engine.push()

        val row = env.expenses.get(payment)!!
        assertEquals(id, row.groupExpenseId)
        assertEquals(15_000L, row.myShareMinor)
        assertEquals(row.id, env.db.expenseDao().findByGroupExpense(id)!!.id)
    }

    @Test fun aChangedPushPullsOnlyThatGroupAndOnlyWhenBehind() = runTest {
        env.seedGroup(G1, version = 5)
        env.seedGroup(G2, version = 3, myMember = M_ME2, others = listOf())
        env.fake.on("GET", changesPath(), json(200, ChangesPage.serializer(), page(from = 5, to = 6)))

        assertEquals(SyncOutcome.DONE, env.engine.pullChanged(G1, 5))
        assertTrue(env.fake.requests.isEmpty())

        assertEquals(SyncOutcome.DONE, env.engine.pullChanged(G1, 6))
        assertEquals(listOf("GET ${changesPath()}"), env.fake.requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" })
        assertEquals(6L, dao.getGroup(G1)!!.version)
    }

    @Test fun signOutPutsBackAPaymentWhoseSplitWasNeverSent() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, equalThree, rememberRule = false, auto = false)
        assertEquals(15_000L, env.expenses.get(payment)!!.myShareMinor)

        env.local.clearAll()

        val row = env.expenses.get(payment)!!
        assertEquals(ExpenseStatus.DETECTED, row.status)
        assertEquals(45_000L, row.myShareMinor)
        assertNull(row.groupExpenseId)
        assertTrue(env.outbox.entries().isEmpty())
        // Sign out and account deletion take every "Groups" notification with the groups.
        assertEquals(1, env.notifier.cancelledAll.get())
    }

    @Test fun pullAppliesPagesInOrderAndMakesMirrors() = runTest {
        val expense = expenseDto("f0000000-0000-4000-8000-000000000002", M_RAHUL, 45_000, mapOf(M_ME to 15_000, M_RAHUL to 15_000, M_PRIYA to 15_000), version = 3)
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 3)))
        env.fake.on(
            "GET", changesPath(),
            json(200, ChangesPage.serializer(), page(from = 0, to = 2, latest = 3, hasMore = true, members = listOf(memberDto(M_ME, userId = ME_USER, name = "Arjun"), memberDto(M_RAHUL, userId = RAHUL_USER, name = "Rahul")))),
            json(200, ChangesPage.serializer(), page(from = 2, to = 3, members = listOf(memberDto(M_PRIYA, name = "Priya", version = 3)), expenses = listOf(expense), changes = listOf(ChangeDto(3, "expense", "create", expense.id, M_RAHUL, env.clock.millis())))),
        )

        assertEquals(SyncOutcome.DONE, env.engine.sync())

        assertEquals(listOf("since=0", "since=2"), env.fake.requestsTo("GET", changesPath()).map { it.requestUrl!!.encodedQuery })
        assertEquals(3L, dao.getGroup(G1)!!.version)
        assertEquals(3, dao.membersOf(G1).size)
        val mirror = env.db.expenseDao().findByGroupExpense(expense.id)!!
        assertEquals(ExpenseSource.GROUP, mirror.source)
        assertEquals(15_000L, mirror.myShareMinor)
        assertEquals(45_000L, mirror.amountMinor)
        assertEquals(LoadState.READY, env.groups.observeLoad(G1).first())
        // A pull from version 0 (new phone, sign-in again) posts nothing.
        assertTrue(env.notifier.events.isEmpty())
    }

    @Test fun aPageSkipsRowsWithAChangeWaiting() = runTest {
        env.seedGroup(version = 5)
        val id = env.groups.addGroupExpense(G1, draft(paidBy = M_RAHUL, amount = 30_000))
        env.fake.on("PUT", expensePath(id), error(503, "internal"))
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 6)))
        env.fake.on(
            "GET", changesPath(),
            json(200, ChangesPage.serializer(), page(from = 5, to = 6, expenses = listOf(expenseDto(id, M_RAHUL, 99_000, mapOf(M_RAHUL to 99_000), 6)))),
        )

        assertEquals(SyncOutcome.RETRY, env.engine.sync())

        assertEquals(30_000L, dao.getExpense(id)!!.amountMinor)
        assertEquals(SyncState.PENDING, dao.getExpense(id)!!.syncState)
        assertEquals(6L, dao.getGroup(G1)!!.version)
    }

    @Test fun aGroupStillBeingCreatedSurvivesTheGroupList() = runTest {
        env.seedGroup(G1)
        env.seedGroup(G2, myMember = M_ME2, others = listOf())
        val made = env.groups.createGroup("Goa trip", emptyList())
        env.fake.on("POST", "/v1/groups", error(503, "internal"))
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 5)))

        env.engine.sync()

        assertNotNull(dao.getGroup(made))
        assertNotNull(dao.getGroup(G1))
        // A group the server no longer lists (deleted, or the person was removed) goes.
        assertNull(dao.getGroup(G2))
    }

    @Test fun changesByOthersAreNotifiedOnce() = runTest {
        env.seedGroup(version = 5)
        val now = env.clock.millis()
        val dinner = expenseDto("f0000000-0000-4000-8000-000000000003", M_RAHUL, 45_000, mapOf(M_ME to 15_000, M_RAHUL to 30_000), 6, description = "Dinner")
        val mine = expenseDto("f0000000-0000-4000-8000-000000000004", M_ME, 10_000, mapOf(M_ME to 5_000, M_RAHUL to 5_000), 7)
        val old = expenseDto("f0000000-0000-4000-8000-000000000005", M_RAHUL, 20_000, mapOf(M_ME to 10_000, M_RAHUL to 10_000), 8)
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 9)))
        env.fake.on(
            "GET", changesPath(),
            json(
                200, ChangesPage.serializer(),
                page(
                    from = 5, to = 9,
                    members = listOf(memberDto(M_PRIYA, userId = "a0000000-0000-4000-8000-000000000003", name = "Priya Nair", version = 9)),
                    expenses = listOf(dinner, mine, old),
                    changes = listOf(
                        ChangeDto(6, "expense", "create", dinner.id, M_RAHUL, now),
                        ChangeDto(7, "expense", "create", mine.id, M_ME, now),
                        ChangeDto(8, "expense", "create", old.id, M_RAHUL, now - 25 * 3_600_000L),
                        ChangeDto(9, "member", "join", M_PRIYA, M_PRIYA, now),
                    ),
                ),
            ),
        )

        env.engine.sync()

        assertEquals(
            listOf(
                GroupEvent.ExpenseAdded(G1, dinner.id, "Rahul", "Dinner", 15_000),
                // First names, as the screens write them: "Priya Nair" took the place of "Priya".
                GroupEvent.MemberJoined(G1, M_PRIYA, "Priya", "Flat 302"),
            ),
            env.notifier.events.toList(),
        )
    }

    @Test fun notificationsNamePeopleAsTheScreensDo() = runTest {
        val vikram = "c0000000-0000-4000-8000-000000000004"
        val rahulKumar = "c0000000-0000-4000-8000-000000000005"
        env.seedGroup(
            version = 5,
            others = listOf(
                MemberEntity(M_RAHUL, G1, RAHUL_USER, "Rahul Verma", null, null, onApp = true, removed = false, version = 1),
                MemberEntity(rahulKumar, G1, null, "Rahul Kumar", null, null, onApp = false, removed = false, version = 1),
                MemberEntity(M_PRIYA, G1, null, "Priya Nair", null, null, onApp = false, removed = false, version = 1),
                MemberEntity(vikram, G1, "a0000000-0000-4000-8000-000000000004", "Vikram Rao", null, null, onApp = true, removed = false, version = 1),
            ),
        )
        val now = env.clock.millis()
        val dinner = expenseDto("f0000000-0000-4000-8000-000000000003", M_RAHUL, 45_000, mapOf(M_ME to 15_000, M_RAHUL to 30_000), 6, description = "Dinner")
        val lunch = expenseDto("f0000000-0000-4000-8000-000000000004", vikram, 20_000, mapOf(M_ME to 10_000, vikram to 10_000), 7, description = "Lunch")
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 8)))
        env.fake.on(
            "GET", changesPath(),
            json(
                200, ChangesPage.serializer(),
                page(
                    from = 5, to = 8,
                    // Someone else, Priya Shah, took the place of the friend Priya Nair.
                    members = listOf(memberDto(M_PRIYA, userId = "a0000000-0000-4000-8000-000000000005", name = "Priya Shah", version = 8)),
                    expenses = listOf(dinner, lunch),
                    changes = listOf(
                        ChangeDto(6, "expense", "create", dinner.id, M_RAHUL, now),
                        ChangeDto(7, "expense", "create", lunch.id, vikram, now),
                        ChangeDto(8, "member", "join", M_PRIYA, M_PRIYA, now),
                    ),
                ),
            ),
        )

        env.engine.sync()

        assertEquals(
            listOf(
                // Two Rahuls in the group: whole names. One Vikram: his first name.
                GroupEvent.ExpenseAdded(G1, dinner.id, "Rahul Verma", "Dinner", 15_000),
                GroupEvent.ExpenseAdded(G1, lunch.id, "Vikram", "Lunch", 10_000),
                // Another Priya than the friend whose place it was: whole, so a wrong join shows.
                GroupEvent.MemberJoined(G1, M_PRIYA, "Priya Shah", "Flat 302"),
            ),
            env.notifier.events.toList(),
        )
        // A reminder names who sent it the same way.
        assertEquals("Vikram", env.engine.reminderEvent(G1, vikram)?.actorName)
    }

    @Test fun notAMemberAnyMoreRemovesTheGroup() = runTest {
        env.seedGroup()
        val id = env.groups.addGroupExpense(G1, draft(paidBy = M_RAHUL))
        assertNotNull(env.db.expenseDao().findByGroupExpense(id))
        env.fake.on("PUT", expensePath(id), error(403, "not_member"))

        env.engine.push()

        assertNull(dao.getGroup(G1))
        assertTrue(env.outbox.entries().isEmpty())
        // The share of an expense that never reached the server does not stay in the month.
        assertNull(env.db.expenseDao().findByGroupExpense(id))
    }

    /** The person took a friend's place in G1 through a link yesterday; the owner can still undo it. */
    private suspend fun joinedByLinkYesterday() {
        val now = env.clock.instant()
        dao.upsertMembers(listOf(dao.getMember(M_ME)!!.copy(joinedAt = now.minus(Duration.ofDays(1)), undoUntil = now.plus(Duration.ofDays(6)))))
    }

    /** A share of a place's dinner (a mirror) and a place's payment linked to the person's own SMS payment. */
    private suspend fun splitsOfThePlace(): Pair<String, Long> {
        val dinner = expenseDto("f0000000-0000-4000-8000-000000000006", M_RAHUL, 45_000, mapOf(M_ME to 15_000, M_RAHUL to 30_000), 4)
        storeSynced(dinner)
        assertNotNull(env.db.expenseDao().findByGroupExpense(dinner.id))
        val payment = env.smsPayment(60_000)
        val paid = expenseDto("f0000000-0000-4000-8000-000000000007", M_ME, 60_000, mapOf(M_ME to 20_000, M_RAHUL to 40_000), 5, occurredOn = "2026-11-10")
        storeSynced(paid)
        assertEquals(paid.id, env.db.expenseDao().get(payment)!!.groupExpenseId)
        return dinner.id to payment
    }

    @Test fun anUndoneJoinForgetsTheSplitsOfThePlace() = runTest {
        env.seedGroup()
        joinedByLinkYesterday()
        val (dinner, payment) = splitsOfThePlace()
        assertEquals(35_000L, env.expenses.observeMonthTotal(november).first())
        // The owner tapped Undo: the group is no longer the person's, and the server says why.
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(lost = mapOf(G1 to LostReason.JOIN_UNDONE))))

        env.engine.sync()

        assertNull(dao.getGroup(G1))
        assertNull(env.db.expenseDao().findByGroupExpense(dinner))
        // The person's own payment, which the join linked while it waited, waits again: it does not count in full
        // without the person deciding, and nothing counts twice.
        val own = env.db.expenseDao().get(payment)!!
        assertNull(own.groupExpenseId)
        assertEquals(60_000L, own.myShareMinor)
        assertEquals(ExpenseStatus.DETECTED, own.status)
        assertEquals(0L, env.expenses.observeMonthTotal(november).first())
    }

    @Test fun anUndoneJoinPutsEachPaymentBackAsItWasBeforeItsSplit() = runTest {
        env.seedGroup()
        joinedByLinkYesterday()
        // Already added before the restore linked it: added again, in full.
        val added = env.smsPayment(60_000, status = ExpenseStatus.ADDED)
        storeSynced(expenseDto("f0000000-0000-4000-8000-000000000007", M_ME, 60_000, mapOf(M_ME to 20_000, M_RAHUL to 40_000), 5, occurredOn = "2026-11-10"))
        // Split from waiting while in the group: waits again. Split after it was added: added again.
        val halves = mapOf(M_ME to null, M_RAHUL to null)
        val waited = env.smsPayment(30_000)
        val waitedSplit = env.groups.splitPayment(waited, G1, SplitMethod.EQUAL, halves, rememberRule = false, auto = false)
        val addedThenSplit = env.smsPayment(12_000, status = ExpenseStatus.ADDED)
        val addedSplit = env.groups.splitPayment(addedThenSplit, G1, SplitMethod.EQUAL, halves, rememberRule = false, auto = false)
        // Typed in with Split: stays the person's own expense, in full, as a refused split does.
        val typed = env.groups.addGroupExpense(G1, draft(paidBy = M_ME, amount = 8_000))
        val typedRow = env.db.expenseDao().findByGroupExpense(typed)!!.id
        // All three reached the server before the undo.
        for ((id, amount) in listOf(waitedSplit to 30_000L, addedSplit to 12_000L, typed to 8_000L)) {
            env.fake.on("PUT", expensePath(id), json(201, ExpenseDto.serializer(), expenseDto(id, M_ME, amount, mapOf(M_ME to amount / 2, M_RAHUL to amount / 2), 6)))
        }
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertTrue(env.outbox.entries().isEmpty())
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(lost = mapOf(G1 to LostReason.JOIN_UNDONE))))

        env.engine.sync()

        assertNull(dao.getGroup(G1))
        val rows = listOf(added, waited, addedThenSplit, typedRow).map { env.expenses.get(it)!! }
        assertEquals(listOf(ExpenseStatus.ADDED, ExpenseStatus.DETECTED, ExpenseStatus.ADDED, ExpenseStatus.ADDED), rows.map { it.status })
        assertEquals(listOf(60_000L, 30_000L, 12_000L, 8_000L), rows.map { it.myShareMinor })
        assertTrue(rows.all { it.groupExpenseId == null })
        assertEquals(80_000L, env.expenses.observeMonthTotal(november).first())
    }

    @Test fun notAMemberBecauseTheJoinWasUndoneForgetsTheSplitsToo() = runTest {
        env.seedGroup(version = 5)
        joinedByLinkYesterday()
        val (dinner, _) = splitsOfThePlace()
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 6)))
        env.fake.on("GET", changesPath(), notMember(LostReason.JOIN_UNDONE))

        env.engine.sync()

        assertNull(dao.getGroup(G1))
        assertNull(env.db.expenseDao().findByGroupExpense(dinner))
    }

    @Test fun aRemovalOrADeletionInsideTheJoinWindowKeepsTheMirrors() = runTest {
        for (reason in listOf(LostReason.REMOVED, LostReason.DELETED, LostReason.LEFT)) {
            env.seedGroup()
            joinedByLinkYesterday()
            val (dinner, payment) = splitsOfThePlace()
            // Still inside the 7 days of the join, but the owner removed the person (or deleted the group): the
            // spending happened.
            env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(lost = mapOf(G1 to reason))))

            env.engine.sync()

            assertNull(dao.getGroup(G1))
            assertEquals(reason, 15_000L, env.db.expenseDao().findByGroupExpense(dinner)!!.myShareMinor)
            assertEquals(reason, 20_000L, env.db.expenseDao().get(payment)!!.myShareMinor)
            env.db.expenseDao().deleteGroupMirrors()
            env.db.expenseDao().delete(payment)
        }
    }

    @Test fun notAMemberWithoutAReasonKeepsTheMirrors() = runTest {
        env.seedGroup(version = 5)
        joinedByLinkYesterday()
        val (dinner, payment) = splitsOfThePlace()
        // An older server, or a loss over 90 days ago: nothing says the join was undone.
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 6)))
        env.fake.on("GET", changesPath(), error(403, "not_member"))

        env.engine.sync()

        assertNull(dao.getGroup(G1))
        assertNotNull(env.db.expenseDao().findByGroupExpense(dinner))
        assertEquals(20_000L, env.db.expenseDao().get(payment)!!.myShareMinor)
    }

    @Test fun leavingTheListAfterTheJoinWindowKeepsTheMirrors() = runTest {
        env.seedGroup()
        val now = env.clock.instant()
        dao.upsertMembers(listOf(dao.getMember(M_ME)!!.copy(joinedAt = now.minus(Duration.ofDays(8)), undoUntil = now.minus(Duration.ofDays(1)))))
        val (dinner, payment) = splitsOfThePlace()
        // Removed or deleted: the spending happened.
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(lost = mapOf(G1 to LostReason.REMOVED))))

        env.engine.sync()

        assertNull(dao.getGroup(G1))
        assertEquals(15_000L, env.db.expenseDao().findByGroupExpense(dinner)!!.myShareMinor)
        assertEquals(20_000L, env.db.expenseDao().get(payment)!!.myShareMinor)
    }

    @Test fun aDeletedGroupKeepsTheMirrorsEvenInsideTheJoinWindow() = runTest {
        env.seedGroup(version = 5)
        joinedByLinkYesterday()
        val (dinner, _) = splitsOfThePlace()
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 6)))
        env.fake.on("GET", changesPath(), error(410, "group_deleted"))

        env.engine.sync()

        assertNull(dao.getGroup(G1))
        assertNotNull(env.db.expenseDao().findByGroupExpense(dinner))
    }

    @Test fun anEditOfADeletedExpenseAppliesTheDelete() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        val id = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, equalThree, rememberRule = false, auto = false)
        env.fake.on("PUT", expensePath(id), json(201, ExpenseDto.serializer(), expenseDto(id, M_ME, 45_000, mapOf(M_ME to 15_000, M_PRIYA to 15_000, M_RAHUL to 15_000), 6)))
        env.engine.push()
        env.groups.updateGroupExpense(id, draft(paidBy = M_ME, amount = 45_000, inputs = mapOf(M_ME to null, M_RAHUL to null)))
        env.fake.on("PUT", expensePath(id), error(410, "expense_deleted"))

        env.engine.push()

        assertTrue(dao.getExpense(id)!!.deleted)
        val row = env.expenses.get(payment)!!
        assertNull(row.groupExpenseId)
        assertEquals(45_000L, row.myShareMinor)
        assertTrue(env.outbox.entries().isEmpty())
    }

    @Test fun aServerRestoredFromBackupIsPulledAgainFromZero() = runTest {
        env.seedGroup(version = 10)
        val expense = expenseDto("f0000000-0000-4000-8000-000000000006", M_RAHUL, 20_000, mapOf(M_ME to 10_000, M_RAHUL to 10_000), 3)
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 3)))
        env.fake.on(
            "GET", changesPath(),
            json(200, ChangesPage.serializer(), page(from = 10, to = 3, latest = 3)),
            json(200, ChangesPage.serializer(), page(from = 0, to = 3, expenses = listOf(expense), changes = listOf(ChangeDto(3, "expense", "create", expense.id, M_RAHUL, env.clock.millis())))),
        )

        env.engine.sync()

        assertEquals(listOf("since=10", "since=0"), env.fake.requestsTo("GET", changesPath()).map { it.requestUrl!!.encodedQuery })
        assertEquals(3L, dao.getGroup(G1)!!.version)
        assertNotNull(dao.getExpense(expense.id))
        assertTrue(env.notifier.events.isEmpty())
    }

    @Test fun aFailedPullMarksTheGroupAndLosesNothing() = runTest {
        env.seedGroup(version = 5)
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 8)))
        env.fake.on("GET", changesPath(), error(503, "internal"))

        assertEquals(SyncOutcome.RETRY, env.engine.sync())
        assertEquals(LoadState.FAILED, env.groups.observeLoad(G1).first())
        assertEquals(5L, dao.getGroup(G1)!!.version)
    }

    private fun draft(
        paidBy: String,
        amount: Long = 30_000,
        inputs: Map<String, Long?> = mapOf(M_ME to null, M_RAHUL to null),
    ) = GroupExpenseDraft("Dinner", Category.FOOD, amount, LocalDate.of(2026, 11, 12), paidBy, SplitMethod.EQUAL, inputs)
}
