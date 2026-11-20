package com.expenses.app.groups

import com.expenses.app.data.category.Category
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.ExpenseDto
import com.expenses.app.groups.api.LinkResponse
import com.expenses.app.groups.data.ActionResult
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.NewPerson
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.data.OwedTotals
import com.expenses.app.groups.data.Person
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.groups.data.StoredGroups
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.data.shareEntities
import com.expenses.app.groups.data.toEntity
import com.expenses.splits.DirectIds
import com.expenses.splits.SplitMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GroupsRepositoryTest {
    private val env = GroupsTestEnv()
    private val dao = env.db.groupDao()
    private val repo = env.groups

    @After fun close() = env.close()

    private suspend fun pulled(expense: ExpenseDto) = env.transactions.run {
        dao.upsertExpense(expense.toEntity(SyncState.SYNCED, env.clock.instant())!!)
        dao.deleteShares(expense.id)
        dao.insertShares(expense.shareEntities())
        env.mirrors.sync(expense.id)
    }

    @Test fun balancesTotalsAndFriendsComeFromTheServersRules() = runTest {
        env.seedGroup(G1)
        // Office lunch: a second group with Rahul.
        env.seedGroup(G2, myMember = M_ME2, others = listOf(MemberEntity(M_RAHUL2, G2, RAHUL_USER, "Rahul", null, null, true, false, 1)))
        // Flat 302: I paid 45,000 for three; Rahul paid 9,000 for Rahul and me.
        pulled(expenseDto("f0000000-0000-4000-8000-000000000020", M_ME, 45_000, mapOf(M_ME to 15_000, M_RAHUL to 15_000, M_PRIYA to 15_000), 6))
        pulled(expenseDto("f0000000-0000-4000-8000-000000000021", M_RAHUL, 9_000, mapOf(M_ME to 4_500, M_RAHUL to 4_500), 7))
        // Office lunch: Rahul paid 8,000 for both of us.
        pulled(expenseDto("f0000000-0000-4000-8000-000000000022", M_RAHUL2, 8_000, mapOf(M_ME2 to 4_000, M_RAHUL2 to 4_000), 2, groupId = G2))

        val groups = repo.observeGroups().first().associateBy { it.id }
        assertEquals(25_500L, groups.getValue(G1).myNetMinor)
        assertEquals(3, groups.getValue(G1).memberCount)
        assertEquals(-4_000L, groups.getValue(G2).myNetMinor)
        assertEquals(OwedTotals(owedToMeMinor = 25_500, iOweMinor = 4_000, overallMinor = 21_500, groupCount = 2), repo.observeTotals().first())

        // Rahul: owes me 10,500 in Flat 302, I owe him 4,000 in Office lunch; one person across groups.
        val friends = repo.observeFriends().first().associateBy { it.key }
        assertEquals(6_500L, friends.getValue(RAHUL_USER).netMinor)
        assertEquals(15_000L, friends.getValue("+919000000001").netMinor)

        val detail = repo.observeGroup(G1).first()!!
        assertEquals(25_500L, detail.myNetMinor)
        assertTrue(detail.isOwner)
        assertEquals(10_500L, detail.members.first { it.member.id == M_RAHUL }.pairMinor)
        assertEquals(15_000L, detail.members.first { it.member.id == M_PRIYA }.pairMinor)
        assertTrue(detail.members.first().isMe)
    }

    @Test fun createGroupQueuesTheServersBody() = runTest {
        val id = repo.createGroup("  Goa trip  ", listOf(NewPerson.KnownUser(RAHUL_USER, "Rahul"), NewPerson.Friend("Priya Nair", "90000 00001")))
        val group = dao.getGroup(id)!!
        assertEquals("Goa trip", group.name)
        assertEquals(SyncState.PENDING, group.syncState)
        assertEquals(LoadState.READY, repo.observeLoad(id).first())
        val entry = env.outbox.entries().single()
        assertEquals(EntityKind.GROUP to OutboxOp.CREATE, entry.entityKind to entry.op)
        val body = ApiJson.parseToJsonElement(entry.bodyJson!!).jsonObject
        assertEquals("group", body["kind"]!!.jsonPrimitive.content)
        assertEquals(group.myMemberId, body["myMemberId"]!!.jsonPrimitive.content)
        val members = body["members"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("id", "userId"), members[0].keys)
        assertEquals(setOf("id", "name", "phone"), members[1].keys)
        assertEquals("+919000000001", members[1]["phone"]!!.jsonPrimitive.content)
        assertEquals(1, env.scheduler.requests.get())
    }

    @Test fun aDirectSplitWithAnAppUserHasTheSameIdsOnBothPhones() = runTest {
        val id = repo.directGroupWith(Person(RAHUL_USER, "Rahul", RAHUL_USER, null))
        assertEquals(DirectIds.groupId(ME_USER, RAHUL_USER), id)
        val group = dao.getGroup(id)!!
        assertEquals(GroupKind.DIRECT, group.kind)
        assertEquals(DirectIds.memberId(id, ME_USER), group.myMemberId)
        assertTrue(dao.membersOf(id).any { it.id == DirectIds.memberId(id, RAHUL_USER) })
        // Asked again, the same one comes back.
        assertEquals(id, repo.directGroupWith(Person(RAHUL_USER, "Rahul", RAHUL_USER, null)))
        assertEquals(1, env.outbox.entries().size)
    }

    @Test fun knownPeopleAreEachPersonOnceAndNeverMe() = runTest {
        env.seedGroup(G1)
        env.seedGroup(G2, myMember = M_ME2, others = listOf(MemberEntity(M_RAHUL2, G2, RAHUL_USER, "Rahul", null, null, true, false, 1)))
        val people = repo.observeKnownPeople().first()
        assertEquals(listOf("Priya", "Rahul"), people.map { it.name })
        assertEquals(listOf("+919000000001", RAHUL_USER), people.map { it.key })
    }

    @Test fun aSplitSendsOnlyWhatTheGroupNeeds() = runTest {
        env.seedGroup()
        // A person-to-person UPI payment: the payee is a UPI ID holding a phone number.
        val payment = env.smsPayment(30_000, counterparty = "9876543210@ybl")
        repo.splitPayment(payment, G1, SplitMethod.SHARES, mapOf(M_ME to 2L, M_RAHUL to 1L), rememberRule = true, auto = false)
        val body = env.outbox.entries().single().bodyJson!!
        val json = ApiJson.parseToJsonElement(body).jsonObject
        assertEquals("Food", json["description"]!!.jsonPrimitive.content)
        assertEquals("2026-11-10", json["occurredOn"]!!.jsonPrimitive.content)
        for (secret in listOf("9876543210", "dinner with the team", "XX1234", "123456789012", "VM-HDFCBK", "20:15")) {
            assertTrue("$secret leaked", !body.contains(secret))
        }
        assertEquals(setOf("description", "category", "amountPaise", "occurredOn", "paidByMemberId", "splitMethod", "auto", "shares", "baseVersion"), json.keys)
        // "Do this every time" is kept on the phone, by merchant.
        assertNotNull(repo.splitRuleFor("9876543210@ybl").first())
    }

    @Test fun recordingASettlementWithAFriendWithoutTheAppIsConfirmedAtOnce() = runTest {
        env.seedGroup()
        val id = repo.recordSettlement(G1, M_PRIYA, M_ME, 15_000, SettleMethod.CASH, matchedFromSms = false)
        assertNotNull(dao.getSettlement(id)!!.confirmedAt)
        val withRahul = repo.recordSettlement(G1, M_ME, M_RAHUL, 4_500, SettleMethod.UPI, matchedFromSms = false)
        assertNull(dao.getSettlement(withRahul)!!.confirmedAt)
        // Priya paid me back 15,000 and I paid Rahul 4,500, with no expenses yet: my net is -15,000 + 4,500.
        val detail = repo.observeGroup(G1).first()!!
        assertEquals(-10_500L, detail.myNetMinor)
        assertEquals(15_000L, detail.members.first { it.member.id == M_PRIYA }.netMinor)
        assertEquals(EntityKind.SETTLEMENT, env.outbox.entries().first().entityKind)
    }

    @Test fun shareLinkIsMadeOnceAndReused() = runTest {
        env.seedGroup()
        env.fake.on("PUT", "/v1/groups/$G1/members/$M_PRIYA/links/${env.madeId(1)}", ApiJsonResponse.link)
        val first = repo.shareLink(G1, M_PRIYA)
        val second = repo.shareLink(G1, M_PRIYA)
        assertTrue(first is ApiResult.Ok)
        val url = (first as ApiResult.Ok).value
        assertTrue(url.matches(Regex("^https://links\\.test/pay/[A-Za-z0-9_-]{24}$")))
        assertEquals(url, (second as ApiResult.Ok).value)
        val sent = env.fake.requests.map { ApiJson.parseToJsonElement(it.body.readUtf8()).jsonObject["token"]!!.jsonPrimitive.content }
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1])
        assertEquals(url.substringAfterLast('/'), sent[0])
    }

    @Test fun leavingNeedsTheServerAndSaysWhy() = runTest {
        env.seedGroup()
        env.fake.on("POST", "/v1/groups/$G1/leave", error(409, "balances_not_settled"))
        assertEquals(ActionResult.NotSettled, repo.leaveGroup(G1))
        assertNotNull(dao.getGroup(G1))
        env.fake.on("POST", "/v1/groups/$G1/leave", MockResponses.noContent)
        assertEquals(ActionResult.Done, repo.leaveGroup(G1))
        assertNull(dao.getGroup(G1))
        env.server.shutdown()
        env.seedGroup()
        assertEquals(ActionResult.Offline, repo.leaveGroup(G1))
    }

    @Test fun groupExpenseDescriptionsAreCleaned() = runTest {
        env.seedGroup()
        val id = repo.addGroupExpense(
            G1,
            com.expenses.app.groups.data.GroupExpenseDraft(
                "‮evil‬  name", Category.FUN, 1_000, java.time.LocalDate.of(2026, 11, 1), M_RAHUL, SplitMethod.EQUAL,
                mapOf(M_ME to null, M_RAHUL to null),
            ),
        )
        assertEquals("evil  name", dao.getExpense(id)!!.description)
    }

    @Test fun aFamilyNeverShowsInGroupsFriendsTotalsSplitLabelsKnownPeopleOrStoredGroups() = runTest {
        env.seedFamily(partner = true)
        dao.upsertMembers(listOf(MemberEntity(M_PLACEHOLDER, FAMILY, null, "Partner", null, null, onApp = false, removed = false, version = 1)))
        env.transactions.run {
            for ((id, paidBy) in listOf("f0000000-0000-4000-8000-000000000801" to M_FME, "f0000000-0000-4000-8000-000000000802" to M_MEERA)) {
                val dto = expenseDto(id, paidBy, 60_000, mapOf(paidBy to 60_000L), 3, groupId = FAMILY)
                dao.upsertExpense(dto.toEntity(SyncState.SYNCED, env.clock.instant())!!)
                dao.insertShares(dto.shareEntities())
                env.mirrors.sync(id)
            }
        }

        val home = repo.home()
        assertTrue(home.groups.isEmpty())
        assertTrue(home.friends.isEmpty())
        assertEquals(OwedTotals(0, 0, 0, 0), home.totals)
        assertEquals(StoredGroups.NONE, home.stored)
        assertTrue(home.nothingToList)
        assertTrue(repo.observeSplitLabels().first().isEmpty())
        // The partner on the app is someone the person knows; the placeholder is nobody.
        assertEquals(listOf("Meera Iyer"), repo.observeKnownPeople().first().map { it.name })
        // The family's own page read does not fail (its id is sent to Home by the screens).
        assertEquals(0L, repo.observeGroup(FAMILY).first()!!.myNetMinor)

        // With a group too, only the group shows; a family still loading does not make the groups incomplete.
        env.seedGroup(G1)
        dao.upsertGroup(dao.getGroup(FAMILY)!!.copy(version = 0, latestVersion = 5))
        val both = repo.home()
        assertEquals(listOf(G1), both.groups.map { it.id })
        assertEquals(StoredGroups.COMPLETE, both.stored)
        assertEquals(1, both.totals.groupCount)
    }

    @Test fun aFamilyCanBeRenamed() = runTest {
        env.seedFamily()
        repo.renameGroup(FAMILY, "Our home")
        assertEquals("Our home", dao.getGroup(FAMILY)!!.name)
    }
}

/** Canned answers used above. */
private object ApiJsonResponse {
    val link = json(201, LinkResponse.serializer(), LinkResponse("ignored", "https://elsewhere/pay/x", 0, 0))
}

/** Answers without a body. */
private object MockResponses {
    val noContent: okhttp3.mockwebserver.MockResponse = okhttp3.mockwebserver.MockResponse().setResponseCode(204)
}
