package com.expenses.app.groups

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.ChangesPage
import com.expenses.app.groups.api.CreateGroupResponse
import com.expenses.app.groups.api.ExpenseDto
import com.expenses.app.groups.api.GroupDto
import com.expenses.app.groups.api.ImportResponse
import com.expenses.app.groups.api.MeGroupsResponse
import com.expenses.app.groups.data.ActionResult
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.ImportState
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.data.canBeDeletedBy
import com.expenses.app.groups.splitwise.ImportBodies
import com.expenses.app.groups.sync.SyncOutcome
import com.expenses.splits.SplitMethod
import com.expenses.splitwise.SplitwiseFile
import com.expenses.splitwise.SwExpense
import com.expenses.splitwise.SwPart
import com.expenses.splitwise.SwPayment
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * Import from Splitwise on the phone (docs/splitwise-import.md 5.3 to 5.5): what one import writes, the personal rows
 * it keeps right, and how the sync engine sends its parts, finishes it, waits out a 429, and takes back a refused one.
 */
@RunWith(RobolectricTestRunner::class)
class SplitwiseImportTest {
    private val env = GroupsTestEnv()
    private val dao = env.db.groupDao()
    private val extras = env.db.groupExtrasDao()
    private val hash = "0".repeat(64)

    @After fun close() = env.close()

    /**
     * Arjun (the person, column 0), Priya and Kiran: Priya paid ₹3,000 rent for three (1 Nov), Arjun paid ₹450 at
     * Swiggy for three on [swiggyOn], and Kiran paid Priya ₹500 (12 Nov). Sent in that order.
     */
    private fun sampleFile(swiggyOn: LocalDate = LocalDate.of(2026, 11, 10), swiggy: String = "Swiggy") = SplitwiseFile(
        people = listOf("Arjun Mehta", "Priya Nair", "Kiran Rao"),
        expenses = listOf(
            SwExpense(2, LocalDate.of(2026, 11, 1), "Rent", "Rent", listOf(SwPart(1, 300_000, mapOf(0 to 100_000L, 1 to 100_000L, 2 to 100_000L)))),
            SwExpense(3, swiggyOn, swiggy, "Dining out", listOf(SwPart(0, 45_000, mapOf(0 to 15_000L, 1 to 15_000L, 2 to 15_000L)))),
        ),
        payments = listOf(SwPayment(4, LocalDate.of(2026, 11, 12), from = 2, to = 1, amountPaise = 50_000)),
        balancesPaise = listOf(-70_000, 135_000, -65_000),
        leftOutByCurrency = emptyMap(),
        noEffectRows = 0,
    )

    /** [n] payments from Priya to Kiran, one a day from 1 Jan 2026. */
    private fun paymentsFile(n: Int) = SplitwiseFile(
        people = listOf("Arjun Mehta", "Priya Nair", "Kiran Rao"),
        expenses = emptyList(),
        payments = (1..n).map { SwPayment(it + 1, LocalDate.of(2026, 1, 1).plusDays(it.toLong()), from = 1, to = 2, amountPaise = 100L + it) },
        balancesPaise = listOf(0, 0, 0),
        leftOutByCurrency = emptyMap(),
        noEffectRows = 0,
    )

    private suspend fun import(file: SplitwiseFile = sampleFile()): String = env.groups.importSplitwise(file, me = 0, groupName = "Flat 302", fileHash = hash)

    private suspend fun idsOf(groupId: String): Triple<String, String, String> {
        val expenses = dao.activeExpensesOf(groupId).associateBy { it.expense.description }
        return Triple(expenses.getValue("Rent").expense.id, expenses.getValue("Swiggy").expense.id, dao.activeSettlementsOf(groupId).single().id)
    }

    private fun acceptCreate(groupId: String) = env.fake.on(
        "POST", "/v1/groups",
        json(201, CreateGroupResponse.serializer(), CreateGroupResponse(GroupDto(groupId, "group", "Flat 302", ME_USER, 1, 0), emptyList())),
    )

    private fun importPath(groupId: String) = "/v1/groups/$groupId/import"

    private fun applied(first: Long, items: Int) = json(200, ImportResponse.serializer(), ImportResponse(true, first, first + items - 1))

    // ---- GroupsRepository.importSplitwise (5.3) ----

    @Test fun anImportWritesTheGroupItsRowsAndItsOutboxInOneGo() = runTest {
        val groupId = import()
        val group = dao.getGroup(groupId)!!
        assertEquals(GroupKind.GROUP, group.kind)
        assertEquals("Flat 302", group.name)
        assertEquals(ME_USER, group.ownerUserId)
        assertEquals(SyncState.PENDING, group.syncState)
        val members = dao.membersOf(groupId)
        assertEquals(setOf("Arjun", "Priya Nair", "Kiran Rao"), members.map { it.name }.toSet())
        assertEquals(ME_USER, members.single { it.id == group.myMemberId }.userId)
        assertTrue(members.filter { it.id != group.myMemberId }.all { it.phone == null && it.userId == null && !it.onApp })
        assertTrue(dao.activeExpensesOf(groupId).all { it.expense.syncState == SyncState.PENDING && it.expense.version == 0L })
        assertEquals(SyncState.PENDING, dao.activeSettlementsOf(groupId).single().syncState)
        // The create first, then one part (3 items).
        val entries = env.outbox.entries()
        assertEquals(listOf(EntityKind.GROUP to "POST /v1/groups", EntityKind.IMPORT to "POST ${importPath(groupId)}"), entries.map { it.entityKind to "${it.method} ${it.path}" })
        assertEquals(OutboxOp.CREATE, entries[0].op)
        val (rent, swiggy, payment) = idsOf(groupId)
        assertEquals(listOf(rent, swiggy, payment), ImportBodies.itemsOf(entries[1].bodyJson).map { it.id })
        val row = extras.importOf(groupId)!!
        assertEquals(hash, row.fileHash)
        assertEquals(2, row.expenseCount)
        assertEquals(3, row.itemCount)
        assertEquals(0, row.sentItems)
        assertEquals(ImportState.SENDING, row.state)
        assertEquals(1, env.scheduler.requests.get())
        // Nothing reaches the server yet.
        assertTrue(env.fake.requests.isEmpty())
    }

    @Test fun personalRowsFollowTheSplitRules() = runTest {
        val sms = env.smsPayment(45_000)
        val groupId = import()
        val (rent, swiggy, _) = idsOf(groupId)
        // Priya paid: a mirror counts my share.
        val mirror = env.db.expenseDao().findByGroupExpense(rent)!!
        assertEquals(ExpenseSource.GROUP, mirror.source)
        assertEquals(100_000L, mirror.myShareMinor)
        // I paid, and my SMS payment of the same amount on that day is linked: it counts my share only.
        val linked = env.expenses.get(sms)!!
        assertEquals(swiggy, linked.groupExpenseId)
        assertEquals(ExpenseStatus.ADDED, linked.status)
        assertEquals(15_000L, linked.myShareMinor)
    }

    @Test fun iPaidWithNoMatchingPaymentGivesAMirror() = runTest {
        val groupId = import(sampleFile(swiggyOn = LocalDate.of(2025, 3, 1)))
        val (_, swiggy, _) = idsOf(groupId)
        val mirror = env.db.expenseDao().findByGroupExpense(swiggy)!!
        assertEquals(ExpenseSource.GROUP, mirror.source)
        assertEquals(15_000L, mirror.myShareMinor)
    }

    @Test fun linksAreNotMadeWhilePartsWait() = runTest {
        val groupId = import()
        val friend = dao.membersOf(groupId).first { !it.onApp }
        val result = env.groups.shareLink(groupId, friend.id) as ApiResult.Error
        assertEquals(GroupsRepository.IMPORT_PENDING, result.error.code)
        assertFalse(result.error.fromServer)
        assertTrue(env.fake.requests.isEmpty())
    }

    @Test fun theSameFileIsFoundWhileItsGroupIsHere() = runTest {
        assertNull(env.groups.importedFile(hash))
        val groupId = import()
        val past = env.groups.importedFile(hash)!!
        assertEquals(groupId, past.groupId)
        assertEquals("Flat 302", past.groupName)
        assertEquals(LocalDate.of(2026, 11, 16), past.on)
        assertEquals(2, past.expenseCount)
        assertNull(env.groups.importedFile("1".repeat(64)))
        // A failed import, or one whose group is gone, does not count.
        extras.setImportState(groupId, ImportState.FAILED)
        assertNull(env.groups.importedFile(hash))
        extras.setImportState(groupId, ImportState.DONE)
        env.local.removeGroup(groupId)
        assertNull(env.groups.importedFile(hash))
    }

    /** The date on "This file already came across" (137) is India's, as everywhere in the app. */
    @Test fun anEarlierImportIsDatedInIndiaWhateverZoneTheClockHas() = runTest {
        // 16 Nov, 20:00 UTC is already 17 Nov, 01:30 in India.
        GroupsTestEnv(Clock.fixed(Instant.parse("2026-11-16T20:00:00Z"), ZoneOffset.UTC)).use { utc ->
            utc.groups.importSplitwise(sampleFile(), me = 0, groupName = "Flat 302", fileHash = hash)
            assertEquals(LocalDate.of(2026, 11, 17), utc.groups.importedFile(hash)!!.on)
        }
    }

    /**
     * Delete group in Group settings (143) while parts of the import still wait: the parts are dropped with the
     * group, so its import can never finish. Its row goes too instead of staying at SENDING for good.
     */
    @Test fun deletingTheGroupWhilePartsWaitForgetsItsImport() = runTest {
        val groupId = import(paymentsFile(60))
        acceptCreate(groupId)
        // The first part (50 items) goes through; the second meets a server error and waits.
        env.fake.on("POST", importPath(groupId), applied(first = 2, items = 50), error(503, "internal"))
        assertEquals(SyncOutcome.RETRY, env.engine.push())
        assertEquals(ImportState.SENDING, extras.importOf(groupId)!!.state)

        env.fake.on("DELETE", "/v1/groups/$groupId", MockResponse().setResponseCode(204))
        assertEquals(ActionResult.Done, env.groups.deleteGroup(groupId))

        assertNull(dao.getGroup(groupId))
        assertTrue(env.outbox.entries().isEmpty())
        assertNull(extras.importOf(groupId))
        assertNull(env.groups.observeImport(groupId).first())
        // Nothing failed and nothing is left of it: the same file can come across again.
        assertNull(env.groups.importedFile(hash))
        assertEquals(1, env.fake.requestsTo("DELETE", "/v1/groups/$groupId").size)
    }

    /** A refused delete (a friend joined meanwhile and a net is not 0) changes nothing on the phone. */
    @Test fun aRefusedDeleteInSettingsKeepsTheGroupAndItsImport() = runTest {
        val groupId = import()
        env.fake.on("DELETE", "/v1/groups/$groupId", error(409, "balances_not_settled"))
        assertEquals(ActionResult.NotSettled, env.groups.deleteGroup(groupId))
        assertNotNull(dao.getGroup(groupId))
        assertEquals(ImportState.SENDING, extras.importOf(groupId)!!.state)
    }

    @Test fun theOwnerMayDeleteAnImportedPaymentBetweenTwoFriends() = runTest {
        val groupId = import()
        val payment = dao.activeSettlementsOf(groupId).single()
        val group = dao.getGroup(groupId)!!
        assertTrue(payment.canBeDeletedBy(group.myMemberId))
        val stranger = "c0000000-0000-4000-8000-000000000099"
        assertFalse(payment.copy(recordedByMemberId = stranger).canBeDeletedBy(group.myMemberId))
        env.groups.deleteSettlement(payment.id)
        assertTrue(dao.getSettlement(payment.id)!!.deleted)
        val delete = env.outbox.entries().last()
        assertEquals(EntityKind.SETTLEMENT to OutboxOp.DELETE, delete.entityKind to delete.op)
        assertEquals("/v1/groups/$groupId/settlements/${payment.id}", delete.path)
    }

    // ---- SyncEngine (5.4) ----

    @Test fun acceptedPartsAreStoredWithTheirVersionsAndTheImportIsDone() = runTest {
        val groupId = import()
        acceptCreate(groupId)
        env.fake.on("POST", importPath(groupId), applied(first = 2, items = 3))
        assertEquals(SyncOutcome.DONE, env.engine.push())

        val (rent, swiggy, payment) = idsOf(groupId)
        assertEquals(2L, dao.getExpense(rent)!!.version)
        assertEquals(3L, dao.getExpense(swiggy)!!.version)
        assertEquals(4L, dao.getSettlement(payment)!!.version)
        assertTrue(dao.activeExpensesOf(groupId).all { it.expense.syncState == SyncState.SYNCED })
        assertEquals(SyncState.SYNCED, dao.getSettlement(payment)!!.syncState)
        val row = extras.importOf(groupId)!!
        assertEquals(3, row.sentItems)
        assertEquals(ImportState.DONE, row.state)
        assertTrue(env.outbox.entries().isEmpty())
        // The request: the stored body under the entry's own key.
        val sent = env.fake.requestsTo("POST", importPath(groupId)).single()
        assertNotNull(sent.getHeader("Idempotency-Key"))
        assertEquals(listOf(rent, swiggy, payment), ImportBodies.itemsOf(sent.body.readUtf8()).map { it.id })
    }

    @Test fun progressCountsPartsAndDoneWaitsForTheLast() = runTest {
        val groupId = import(paymentsFile(60))
        acceptCreate(groupId)
        // The first part (50 items) goes through; the second meets a server error and waits.
        env.fake.on("POST", importPath(groupId), applied(first = 2, items = 50), error(503, "internal"))
        assertEquals(SyncOutcome.RETRY, env.engine.push())
        val progress = env.groups.observeImport(groupId).first()!!
        assertEquals(50, progress.sent)
        assertEquals(60, progress.total)
        assertEquals(ImportState.SENDING, progress.state)
        // A server error is a problem ("Could not do that now"), tried again later.
        assertTrue(progress.problem)
        env.fake.on("POST", importPath(groupId), applied(first = 52, items = 10))
        assertEquals(SyncOutcome.DONE, env.engine.push())
        val done = env.groups.observeImport(groupId).first()!!
        assertEquals(60, done.sent)
        assertEquals(ImportState.DONE, done.state)
        assertFalse(done.problem)
        assertEquals((2L..61L).toList(), dao.activeSettlementsOf(groupId).map { it.version }.sorted())
    }

    @Test fun anImportMadeOfflineFinishesOnceBackOnline() = runTest {
        val groupId = import()
        env.session.unavailable = ApiError.offline
        assertEquals(SyncOutcome.RETRY, env.engine.push())
        assertEquals(2, env.outbox.entries().size)
        assertEquals(ImportState.SENDING, extras.importOf(groupId)!!.state)
        // Offline is not a problem: it finishes by itself.
        assertFalse(env.groups.observeImport(groupId).first()!!.problem)
        env.session.unavailable = null
        acceptCreate(groupId)
        env.fake.on("POST", importPath(groupId), applied(first = 2, items = 3))
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertEquals(ImportState.DONE, extras.importOf(groupId)!!.state)
    }

    @Test fun aRetryAfterAServerErrorSendsTheSameKeyAndTheReplayIsApplied() = runTest {
        val groupId = import()
        acceptCreate(groupId)
        env.fake.on("POST", importPath(groupId), error(503, "internal"), applied(first = 2, items = 3).setHeader("Idempotent-Replay", "true"))
        assertEquals(SyncOutcome.RETRY, env.engine.push())
        assertTrue(env.groups.observeImport(groupId).first()!!.problem)
        assertEquals(SyncOutcome.DONE, env.engine.push())
        val sent = env.fake.requestsTo("POST", importPath(groupId))
        assertEquals(2, sent.size)
        assertEquals(sent[0].getHeader("Idempotency-Key"), sent[1].getHeader("Idempotency-Key"))
        assertEquals(ImportState.DONE, extras.importOf(groupId)!!.state)
        assertFalse(env.groups.observeImport(groupId).first()!!.problem)
    }

    @Test fun appliedFalseMarksTheRowsSentAndKeepsVersion0() = runTest {
        val groupId = import()
        acceptCreate(groupId)
        env.fake.on("POST", importPath(groupId), json(200, """{"applied":false,"firstVersion":null,"lastVersion":null}"""))
        assertEquals(SyncOutcome.DONE, env.engine.push())
        val (rent, _, payment) = idsOf(groupId)
        assertEquals(0L, dao.getExpense(rent)!!.version)
        assertEquals(SyncState.SYNCED, dao.getExpense(rent)!!.syncState)
        assertEquals(SyncState.SYNCED, dao.getSettlement(payment)!!.syncState)
        assertEquals(ImportState.DONE, extras.importOf(groupId)!!.state)
    }

    @Test fun a429IsWaitedOutAndThePartSentAgain() = runTest {
        val groupId = import()
        acceptCreate(groupId)
        env.fake.on("POST", importPath(groupId), error(429, "rate_limited").setHeader("Retry-After", "30"), applied(first = 2, items = 3))
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertEquals(listOf(30_000L), env.sleeps.toList())
        assertEquals(2, env.fake.requestsTo("POST", importPath(groupId)).size)
        assertEquals(ImportState.DONE, extras.importOf(groupId)!!.state)
    }

    @Test fun aStuckPartBlocksOnlyItsOwnGroup() = runTest {
        env.seedGroup()
        val groupId = import()
        acceptCreate(groupId)
        env.fake.on("POST", importPath(groupId), error(503, "internal"))
        val part = env.outbox.entries().single { it.entityKind == EntityKind.IMPORT }
        env.db.outboxDao().update(part.copy(failures = 4))
        // A change of another group, made after the import.
        val other = env.groups.addGroupExpense(
            G1,
            GroupExpenseDraft("Milk", Category.GROCERIES, 6_000, LocalDate.of(2026, 11, 15), M_RAHUL, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null)),
        )
        val accepted = expenseDto(other, M_RAHUL, 6_000, mapOf(M_ME to 3_000, M_RAHUL to 3_000), 6)
        env.fake.on("PUT", "/v1/groups/$G1/expenses/$other", json(201, ExpenseDto.serializer(), accepted))
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertEquals(5, env.outbox.entries().single { it.entityKind == EntityKind.IMPORT }.failures)
        assertEquals(SyncState.SYNCED, dao.getExpense(other)!!.syncState)
        assertTrue(env.groups.observeImport(groupId).first()!!.problem)
        assertEquals(ImportState.SENDING, extras.importOf(groupId)!!.state)
    }

    @Test fun aRefusedPartTakesTheWholeImportBack() = runTest {
        // A waiting SMS payment the import links (I paid Swiggy on 10 Nov), and the rent mirror.
        val sms = env.smsPayment(45_000)
        val groupId = import()
        val (rent, swiggy, _) = idsOf(groupId)
        assertEquals(swiggy, env.expenses.get(sms)!!.groupExpenseId)
        assertNotNull(env.db.expenseDao().findByGroupExpense(rent))
        acceptCreate(groupId)
        env.fake.on("POST", importPath(groupId), error(400, "bad_request"))
        // The delete gets no good answer at first, so it waits.
        env.fake.on("DELETE", "/v1/groups/$groupId", error(503, "internal"), MockResponse().setResponseCode(204))
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(groupId to 1)))

        assertEquals(SyncOutcome.RETRY, env.engine.sync())

        assertNull(dao.getGroup(groupId))
        assertEquals(ImportState.FAILED, extras.importOf(groupId)!!.state)
        // The mirror went; the linked payment waits again, counted in full.
        assertNull(env.db.expenseDao().findByGroupExpense(rent))
        val back = env.expenses.get(sms)!!
        assertNull(back.groupExpenseId)
        assertEquals(ExpenseStatus.DETECTED, back.status)
        assertEquals(45_000L, back.myShareMinor)
        // The delete was sent in the same run (then waits), and the pull did not bring the group back.
        val delete = env.outbox.entries().single()
        assertEquals(EntityKind.GROUP to OutboxOp.DELETE, delete.entityKind to delete.op)
        assertEquals(1, env.fake.requestsTo("DELETE", "/v1/groups/$groupId").size)
        assertTrue(env.fake.requestsTo("GET", "/v1/groups/$groupId/changes").isEmpty())

        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertTrue(env.outbox.entries().isEmpty())
        assertNull(dao.getGroup(groupId))
        assertEquals(ImportState.FAILED, extras.importOf(groupId)!!.state)
    }

    @Test fun aRefusedDeleteIsDroppedAndTheGroupComesBack() = runTest {
        val groupId = import()
        acceptCreate(groupId)
        env.fake.on("POST", importPath(groupId), error(409, "limit_reached"))
        env.fake.on("DELETE", "/v1/groups/$groupId", error(409, "balances_not_settled"))
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(groupId to 1)))
        env.fake.on("GET", "/v1/groups/$groupId/changes", json(200, ChangesPage.serializer(), page(groupId, from = 0, to = 1)))
        assertEquals(SyncOutcome.DONE, env.engine.sync())
        assertTrue(env.outbox.entries().isEmpty())
        assertNotNull(dao.getGroup(groupId))
        assertEquals(ImportState.FAILED, extras.importOf(groupId)!!.state)
    }

    @Test fun aRefusedCreateFailsTheImportWithNothingToDelete() = runTest {
        val sms = env.smsPayment(45_000)
        val groupId = import()
        env.fake.on("POST", "/v1/groups", error(400, "bad_request"))
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertNull(dao.getGroup(groupId))
        assertTrue(env.outbox.entries().isEmpty())
        assertEquals(ImportState.FAILED, extras.importOf(groupId)!!.state)
        assertTrue(env.fake.requestsTo("POST", importPath(groupId)).isEmpty())
        assertNull(env.expenses.get(sms)!!.groupExpenseId)
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(sms)!!.status)
    }

    @Test fun notMemberFailsTheImport() = runTest {
        val groupId = import()
        acceptCreate(groupId)
        env.fake.on("POST", importPath(groupId), error(403, "not_member"))
        assertEquals(SyncOutcome.DONE, env.engine.push())
        assertNull(dao.getGroup(groupId))
        assertEquals(ImportState.FAILED, extras.importOf(groupId)!!.state)
        assertTrue(env.outbox.entries().isEmpty())
    }

    @Test fun signingOutWithPartsUnsentForgetsTheirMirrors() = runTest {
        val sms = env.smsPayment(45_000)
        val groupId = import()
        val (rent, _, _) = idsOf(groupId)
        env.local.clearAll()
        assertNull(env.db.expenseDao().findByGroupExpense(rent))
        assertNull(env.expenses.get(sms)!!.groupExpenseId)
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(sms)!!.status)
        assertNull(extras.importOf(groupId))
    }

    @Test fun anImportedGroupIsReadyWhileItsPartsAreSent() = runTest {
        val groupId = import()
        acceptCreate(groupId)
        env.fake.on("POST", importPath(groupId), error(503, "internal"))
        assertEquals(SyncOutcome.RETRY, env.engine.push())
        // Its create is accepted (version 0 of 1 known), yet its data is all here: no loading placeholders.
        assertEquals(LoadState.READY, env.groups.observeLoad(groupId).first())
        assertEquals(LoadState.READY, env.groups.observeGroup(groupId).first()!!.load)
    }

    @Test fun nothingOfTheFileIsLogged() = runTest {
        ShadowLog.clear()
        val groupId = import(sampleFile(swiggy = "Secret dinner at Lumen"))
        acceptCreate(groupId)
        env.fake.on("POST", importPath(groupId), error(400, "bad_request"))
        env.fake.on("DELETE", "/v1/groups/$groupId", MockResponse().setResponseCode(204))
        env.engine.push()
        val logged = ShadowLog.getLogs().joinToString("\n") { it.msg.orEmpty() }
        for (secret in listOf("Secret dinner", "Priya Nair", "Kiran Rao", "45000", "450.00", groupId, hash)) {
            assertFalse("logged: $secret", logged.contains(secret))
        }
    }
}
