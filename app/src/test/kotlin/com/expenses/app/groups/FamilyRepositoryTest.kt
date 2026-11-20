package com.expenses.app.groups

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.repository.TestClock
import com.expenses.app.data.repository.TestZone
import com.expenses.app.groups.api.AcceptInviteResponse
import com.expenses.app.groups.api.CreateGroupResponse
import com.expenses.app.groups.api.GroupDto
import com.expenses.app.groups.api.InvitePreviewDto
import com.expenses.app.groups.api.LinkResponse
import com.expenses.app.groups.api.MemberDto
import com.expenses.app.groups.data.ActionResult
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.FamilyRepository
import com.expenses.app.groups.data.FamilyResult
import com.expenses.app.groups.data.FamilyState
import com.expenses.app.groups.data.GroupExpenseEntity
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.data.familyLoadState
import com.expenses.app.groups.match.MovingClock
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.components.SelfAvatarColor
import com.expenses.splits.SplitMethod
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The family on this phone (docs/family.md 8.1): set up, invite, leave, and the family month. */
@RunWith(RobolectricTestRunner::class)
class FamilyRepositoryTest {
    private val clock = MovingClock(TestClock.instant(), TestZone)
    private val env = GroupsTestEnv(clock)
    private val dao = env.db.groupDao()
    private val repo = env.family
    private val november = YearMonth.of(2026, 11)

    @After fun close() = env.close()

    private fun member(id: String, userId: String?, name: String, version: Long = 1) =
        MemberDto(id, FAMILY, userId, name, onApp = userId != null, version = version)

    @Test fun setUpPostsAFamilyWithAPlaceholderAndStoresItComplete() = runTest {
        val earlier = listOf(env.familyPayment(234_000, day = 3), env.familyPayment(99_000, day = 9))
        assertEquals(2, repo.observeShareableEarlier().first())
        val id = env.madeId(1)
        val me = env.madeId(2)
        val placeholder = env.madeId(3)
        env.fake.on(
            "POST", "/v1/groups",
            json(
                201, CreateGroupResponse.serializer(),
                CreateGroupResponse(
                    GroupDto(id, "family", "Home", ME_USER, version = 1, createdAt = 1_000, ownerMemberId = me),
                    listOf(MemberDto(me, id, ME_USER, "Arjun Mehta", onApp = true, version = 1), MemberDto(placeholder, id, null, "Partner", version = 1)),
                ),
            ),
        )

        val result = repo.create("  Home ")

        assertEquals(FamilyResult.Ok(id), result)
        val body = Json.parseToJsonElement(env.fake.requestsTo("POST", "/v1/groups").single().body.readUtf8()).jsonObject
        assertEquals(setOf("id", "kind", "name", "myMemberId", "members"), body.keys)
        assertEquals("family", body.getValue("kind").jsonPrimitive.content)
        assertEquals("Home", body.getValue("name").jsonPrimitive.content)
        assertEquals(me, body.getValue("myMemberId").jsonPrimitive.content)
        val only = body.getValue("members").jsonArray.single().jsonObject
        assertEquals(setOf("id", "name"), only.keys)
        assertEquals(placeholder, only.getValue("id").jsonPrimitive.content)
        assertEquals("Partner", only.getValue("name").jsonPrimitive.content)

        val group = dao.getGroup(id)!!
        assertEquals(GroupKind.FAMILY, group.kind)
        assertEquals(1L, group.version)
        assertEquals(1L, group.latestVersion)
        assertEquals(SyncState.SYNCED, group.syncState)
        assertEquals(LoadState.READY, familyLoadState(group))
        val info = repo.observeFamily().first()!!
        assertEquals(FamilyState.WAITING, info.state)
        assertEquals(placeholder, info.placeholderId)
        assertTrue(info.isOwner)
        assertEquals("Arjun", info.me.shortName)
        // The earlier Family rows are shared now.
        val creates = env.outbox.entries().filter { it.entityKind == EntityKind.EXPENSE && it.op == OutboxOp.CREATE }
        assertEquals(2, creates.size)
        assertTrue(creates.all { it.groupId == id })
        earlier.forEach { assertTrue(env.expenses.get(it)!!.familyExpenseId != null) }
        assertEquals(0, repo.observeShareableEarlier().first())
        assertTrue(env.scheduler.requests.get() > 0)
    }

    @Test fun setUpWhenAlreadyInAFamilySyncsAndSaysSo() = runTest {
        env.fake.on("POST", "/v1/groups", error(409, "already_in_family"))

        assertEquals(FamilyResult.AlreadyInFamily, repo.create("Home"))
        assertTrue(env.scheduler.requests.get() > 0)
        assertNull(dao.familyGroup())

        // A family already on this phone: nothing is sent.
        env.seedFamily()
        val sent = env.fake.requests.size
        assertEquals(FamilyResult.AlreadyInFamily, repo.create("Home"))
        assertEquals(sent, env.fake.requests.size)
    }

    @Test fun setUpOfflineOrSignedOutMakesNothing() = runTest {
        env.familyPayment()
        env.server.shutdown()
        assertEquals(FamilyResult.Offline, repo.create("Home"))
        assertNull(dao.familyGroup())
        assertTrue(env.outbox.entries().isEmpty())
        env.me = null
        assertEquals(FamilyResult.SignedOut, repo.create("Home"))
    }

    @Test fun inviteLinkAddsAPlaceholderWhenNoneAndUsesASevenDayLinkRenewedAfterThreeDays() = runTest {
        env.seedFamily(partner = false, placeholder = false)
        assertEquals(FamilyState.ALONE, repo.observeFamily().first()!!.state)
        val placeholder = env.madeId(1)
        env.fake.on("POST", "/v1/groups/$FAMILY/members", json(201, MemberDto.serializer(), member(placeholder, null, "Partner", version = 4)))
        env.fake.on(
            "PUT", "/v1/groups/$FAMILY/members/$placeholder/links/${env.madeId(3)}",
            json(201, LinkResponse.serializer(), LinkResponse(env.madeId(3), "https://links.test/pay/x")),
        )

        val first = repo.inviteLink() as FamilyResult.Ok

        val added = Json.parseToJsonElement(env.fake.requestsTo("POST", "/v1/groups/$FAMILY/members").single().body.readUtf8()).jsonObject
        assertEquals(setOf("id", "name"), added.keys)
        assertEquals("Partner", added.getValue("name").jsonPrimitive.content)
        assertTrue(first.value.startsWith("https://links.test/pay/"))
        assertEquals(placeholder, repo.observeFamily().first()!!.placeholderId)
        assertEquals(FamilyState.WAITING, repo.observeFamily().first()!!.state)
        val link = env.db.groupExtrasDao().linkFor(placeholder)!!
        assertEquals(Duration.ofDays(7), Duration.between(link.createdAt, link.expiresAt))

        // Two days later the same link is sent again.
        clock.advance(Duration.ofDays(2))
        assertEquals(first, repo.inviteLink())
        assertEquals(1, env.fake.requestsTo("POST", "/v1/groups/$FAMILY/members").size)

        // After three days a new one is made.
        clock.advance(Duration.ofDays(2))
        env.fake.on(
            "PUT", "/v1/groups/$FAMILY/members/$placeholder/links/${env.madeId(4)}",
            json(201, LinkResponse.serializer(), LinkResponse(env.madeId(4), "https://links.test/pay/y")),
        )
        val renewed = repo.inviteLink() as FamilyResult.Ok
        assertNotEquals(first.value, renewed.value)
        val again = env.db.groupExtrasDao().linkFor(placeholder)!!
        assertEquals(clock.instant(), again.createdAt)
        assertEquals(clock.instant().plus(Duration.ofDays(7)), again.expiresAt)
    }

    @Test fun inviteLinkWhenThePartnerJoinedMeanwhileSyncs() = runTest {
        env.seedFamily(partner = false, placeholder = true)
        env.fake.on("PUT", "/v1/groups/$FAMILY/members/$M_PLACEHOLDER/links/${env.madeId(1)}", error(409, "member_on_app"))

        assertEquals(FamilyResult.Failed("member_on_app"), repo.inviteLink())
        assertTrue(env.scheduler.requests.get() > 0)

        env.server.shutdown()
        assertEquals(FamilyResult.Offline, repo.inviteLink())
    }

    /**
     * Code review (Family build): a partner who deleted their account stays as "Former member", not on the app and
     * with no link. That is "Invite your partner" (just you, docs/family.md 1.5), not "Invite sent"; inviting removes
     * that place first (it counts toward the 2 members) and gives the invite a placeholder of its own.
     */
    @Test fun aPartnerWhoDeletedTheirAccountLeavesJustYouAndTheInviteGetsAPlaceOfItsOwn() = runTest {
        env.seedFamily()
        dao.upsertMembers(listOf(MemberEntity(M_MEERA, FAMILY, null, "Former member", null, null, onApp = false, removed = false, version = 5)))
        val alone = repo.observeFamily().first()!!
        assertEquals(FamilyState.ALONE, alone.state)
        assertNull(alone.placeholderId)
        assertNull(alone.partner)

        val placeholder = env.madeId(2)
        env.fake.on("DELETE", "/v1/groups/$FAMILY/members/$M_MEERA", MockResponse().setResponseCode(204))
        env.fake.on("POST", "/v1/groups/$FAMILY/members", json(201, MemberDto.serializer(), member(placeholder, null, "Partner", version = 7)))
        env.fake.on(
            "PUT", "/v1/groups/$FAMILY/members/$placeholder/links/${env.madeId(4)}",
            json(201, LinkResponse.serializer(), LinkResponse(env.madeId(4), "https://links.test/pay/x")),
        )

        assertTrue((repo.inviteLink() as FamilyResult.Ok).value.startsWith("https://links.test/pay/"))

        val asked = env.fake.requests.map { it.method + " " + it.requestUrl!!.encodedPath }
        assertEquals(
            listOf(
                "DELETE /v1/groups/$FAMILY/members/$M_MEERA",
                "POST /v1/groups/$FAMILY/members",
                "PUT /v1/groups/$FAMILY/members/$placeholder/links/${env.madeId(4)}",
            ),
            asked,
        )
        val waiting = repo.observeFamily().first()!!
        assertEquals(FamilyState.WAITING, waiting.state)
        assertEquals(placeholder, waiting.placeholderId)
        assertTrue(dao.getMember(M_MEERA)!!.removed)
    }

    /** Code review (Family build): accept answering `409 already_member` asks for a sync, so the family comes to this phone. */
    @Test fun joiningAFamilyThePersonIsInAlreadyAsksForASync() = runTest {
        val token = "abcdefghijklmnopqrstuvwx"
        env.fake.on("POST", "/v1/invites/$token/accept", error(409, "already_member"))
        val before = env.scheduler.requests.get()

        assertEquals(FamilyResult.Failed(FamilyRepository.ALREADY_MEMBER), repo.join(token, "Home"))

        assertTrue(env.scheduler.requests.get() > before)
    }

    @Test fun leavingUnlinksTheRowsKeepsTheirFamilyTagAndRemovesTheFamily() = runTest {
        env.seedFamily()
        val id = env.familyPayment()
        env.transactions.run { env.familyLinker.reconcile() }
        assertTrue(env.expenses.get(id)!!.familyExpenseId != null)
        env.fake.on("POST", "/v1/groups/$FAMILY/leave", MockResponse().setResponseCode(204))

        assertEquals(ActionResult.Done, repo.leave())

        assertNull(dao.getGroup(FAMILY))
        assertNull(repo.observeFamily().first())
        assertNull(repo.observeMonth(november).first())
        val row = env.expenses.get(id)!!
        assertNull(row.familyExpenseId)
        assertEquals(ExpenseScope.FAMILY, row.scope)
        assertTrue(env.outbox.entries().isEmpty())
        // Joining a family later shares it again, after saying how many.
        assertEquals(1, repo.observeShareableEarlier().first())
    }

    @Test fun leavingOfflineKeepsEverything() = runTest {
        env.seedFamily()
        env.server.shutdown()
        assertEquals(ActionResult.Offline, repo.leave())
        assertEquals(GroupKind.FAMILY, dao.getGroup(FAMILY)!!.kind)
    }

    @Test fun removingThePartnerTakesTheirExpensesOutAtOnceAndCancelInviteRemovesThePlaceholder() = runTest {
        env.seedFamily()
        store("f0000000-0000-4000-8000-000000000301", M_MEERA, 50_000, LocalDate.of(2026, 11, 5))
        env.fake.on("DELETE", "/v1/groups/$FAMILY/members/$M_MEERA", MockResponse().setResponseCode(204))

        assertEquals(ActionResult.Done, repo.removePartner(M_MEERA))

        val month = repo.observeMonth(november).first()!!
        assertEquals(0L, month.totalMinor)
        assertEquals(1, month.people.size)
        assertEquals(FamilyState.ALONE, repo.observeFamily().first()!!.state)

        dao.upsertMembers(listOf(MemberEntity(M_PLACEHOLDER, FAMILY, null, "Partner", null, null, onApp = false, removed = false, version = 5)))
        env.fake.on("DELETE", "/v1/groups/$FAMILY/members/$M_PLACEHOLDER", MockResponse().setResponseCode(204))
        assertEquals(ActionResult.Done, repo.cancelInvite(M_PLACEHOLDER))
        assertEquals(FamilyState.ALONE, repo.observeFamily().first()!!.state)
    }

    @Test fun renameIsQueued() = runTest {
        env.seedFamily()
        repo.rename(" Our home ")
        assertEquals("Our home", dao.getGroup(FAMILY)!!.name)
        val entry = env.outbox.entries().single()
        assertEquals("PATCH", entry.method)
        assertEquals("/v1/groups/$FAMILY", entry.path)
        assertEquals("Our home", repo.observeFamily().first()!!.name)
    }

    @Test fun theFamilyStatesTheScreensShow() = runTest {
        assertNull(repo.observeFamily().first())

        env.seedFamily(version = 0, latestVersion = 3, partner = false, placeholder = true)
        val loading = repo.observeFamily().first()!!
        assertEquals(LoadState.LOADING, loading.load)
        assertEquals(FamilyState.WAITING, loading.state)

        env.seedFamily()
        val together = repo.observeFamily().first()!!
        assertEquals(LoadState.READY, together.load)
        assertEquals(FamilyState.TOGETHER, together.state)
        assertEquals("Home", together.name)
        assertEquals(M_FME, together.myMemberId)
        assertTrue(together.isOwner)
        assertEquals("Arjun Mehta", together.me.name)
        assertEquals("Arjun", together.me.shortName)
        assertEquals(SelfAvatarColor, together.me.avatar)
        assertEquals("Meera Iyer", together.partner!!.name)
        assertEquals("Meera", together.partner!!.shortName)
        assertEquals(AvatarColor.Green, together.partner!!.avatar)

        // The partner's phone: the owner is the other person.
        dao.upsertGroup(dao.getGroup(FAMILY)!!.copy(ownerMemberId = M_MEERA, ownerUserId = MEERA_USER))
        assertTrue(!repo.observeFamily().first()!!.isOwner)
    }

    @Test fun joiningShowsTheFamilyLoadingAtOnceAndAlreadyInAFamilySaysSo() = runTest {
        val token = "abcdefghijklmnopqrstuvwx"
        env.fake.on("POST", "/v1/invites/$token/accept", error(409, "already_in_family"))
        assertEquals(FamilyResult.AlreadyInFamily, repo.join(token, "Home"))
        assertNull(repo.observeFamily().first())

        env.fake.on("POST", "/v1/invites/$token/accept", json(200, AcceptInviteResponse.serializer(), AcceptInviteResponse(FAMILY, M_MEERA, version = 4)))
        val before = env.scheduler.requests.get()
        assertEquals(FamilyResult.Ok(FAMILY), repo.join(token, "Home"))

        val info = repo.observeFamily().first()!!
        assertEquals(LoadState.LOADING, info.load)
        assertEquals("Home", info.name)
        assertEquals(M_MEERA, info.myMemberId)
        assertEquals(LoadState.LOADING, repo.observeMonth(november).first()!!.load)
        assertTrue(env.scheduler.requests.get() > before)

        env.server.shutdown()
        assertEquals(FamilyResult.Offline, repo.join(token, "Home"))
    }

    @Test fun previewOfAFamilyLinkSaysSo() = runTest {
        val token = "abcdefghijklmnopqrstuvwx"
        env.fake.on(
            "GET", "/v1/invites/$token",
            json(
                200, InvitePreviewDto.serializer(),
                InvitePreviewDto(FAMILY, "Home", "Arjun", listOf("Arjun"), 1, kind = "family", alreadyInFamily = true),
            ),
        )
        val preview = (env.groups.previewInvite(token) as com.expenses.app.groups.api.ApiResult.Ok).value
        assertEquals(GroupKind.FAMILY, preview.kind)
        assertTrue(preview.alreadyInFamily)
        assertEquals("Home", preview.groupName)

        // An older server sends neither field: a group link.
        env.fake.on("GET", "/v1/invites/$token", json(200, """{"groupId":"$G1","groupName":"Flat 302","inviterName":"Rahul"}"""))
        val old = (env.groups.previewInvite(token) as com.expenses.app.groups.api.ApiResult.Ok).value
        assertEquals(GroupKind.GROUP, old.kind)
        assertTrue(!old.alreadyInFamily)
    }

    @Test fun theFamilyMonthCountsFullAmountsByIndiaMonthWithPeopleCategoriesAndLatest() = runTest {
        env.seedFamily()
        // Mine: groceries 3,000 and food 1,000 in November; one waiting to be sent (PENDING) counts too.
        store("f0000000-0000-4000-8000-000000000401", M_FME, 300_000, LocalDate.of(2026, 11, 1), Category.GROCERIES)
        store("f0000000-0000-4000-8000-000000000402", M_FME, 100_000, LocalDate.of(2026, 11, 20), Category.FOOD)
        val pending = env.expenses.addManual(150_000, "Dinner", Category.FOOD, occurredAt = LocalDate.of(2026, 11, 16).atTime(21, 0).atZone(TestZone).toInstant(), scope = ExpenseScope.FAMILY)
        val pendingId = env.expenses.get(pending)!!.familyExpenseId!!
        assertEquals(SyncState.PENDING, dao.getExpense(pendingId)!!.syncState)
        // Meera's: groceries 1,500 and 1,000 on the last day, rent 6,500 on the 2nd.
        store("f0000000-0000-4000-8000-000000000403", M_MEERA, 150_000, LocalDate.of(2026, 11, 30), Category.GROCERIES, "BigBasket")
        store("f0000000-0000-4000-8000-000000000404", M_MEERA, 100_000, LocalDate.of(2026, 11, 30), Category.GROCERIES)
        store("f0000000-0000-4000-8000-000000000405", M_MEERA, 650_000, LocalDate.of(2026, 11, 2), Category.RENT_AND_BILLS)
        // Outside November (India dates): 31 Oct counts as last month; 1 Dec does not count; a deleted one never.
        store("f0000000-0000-4000-8000-000000000406", M_MEERA, 70_000, LocalDate.of(2026, 10, 31), Category.FOOD)
        store("f0000000-0000-4000-8000-000000000407", M_FME, 80_000, LocalDate.of(2026, 12, 1), Category.FOOD)
        store("f0000000-0000-4000-8000-000000000408", M_FME, 90_000, LocalDate.of(2026, 11, 10), Category.FOOD, deleted = true)

        val month = repo.observeMonth(november).first()!!

        assertEquals(november, month.month)
        assertEquals("Home", month.familyName)
        assertEquals(1_450_000L, month.totalMinor)
        assertEquals(6, month.count)
        assertEquals(70_000L, month.lastMonthTotalMinor)
        assertEquals(LoadState.READY, month.load)
        assertEquals(listOf(M_FME, M_MEERA), month.people.map { it.member.memberId })
        assertTrue(month.people.first().isMe)
        assertEquals(3, month.people[0].count)
        assertEquals(550_000L, month.people[0].totalMinor)
        assertEquals(3, month.people[1].count)
        assertEquals(900_000L, month.people[1].totalMinor)
        // Rent 6,500, then groceries 5,500 and food 2,500.
        assertEquals(
            listOf(CategoryTotal(Category.RENT_AND_BILLS, 1, 650_000), CategoryTotal(Category.GROCERIES, 3, 550_000)),
            month.topCategories,
        )
        assertEquals(3, month.categories.size)
        assertEquals(5, month.latest.size)
        assertEquals(6, month.expenses.size)
        val newest = month.latest.first()
        assertEquals(LocalDate.of(2026, 11, 30), newest.occurredOn)
        assertTrue(!newest.paidByMe)
        assertEquals("Meera", newest.paidByName)
        assertNull(newest.personalExpenseId)
        val mine = month.expenses.first { it.groupExpenseId == pendingId }
        assertTrue(mine.paidByMe)
        assertEquals(pending, mine.personalExpenseId)
        assertEquals("Dinner", mine.title)
        assertEquals(LocalDate.of(2026, 11, 1), month.expenses.last().occurredOn)
    }

    @Test fun categoriesThatTieGoByNameAndAMonthWithNothingHasZeroRows() = runTest {
        env.seedFamily()
        store("f0000000-0000-4000-8000-000000000501", M_FME, 40_000, LocalDate.of(2026, 11, 3), Category.SHOPPING)
        store("f0000000-0000-4000-8000-000000000502", M_MEERA, 40_000, LocalDate.of(2026, 11, 4), Category.FOOD)
        store("f0000000-0000-4000-8000-000000000503", M_MEERA, 40_000, LocalDate.of(2026, 11, 5), Category.GROCERIES)

        assertEquals(
            listOf(Category.FOOD, Category.GROCERIES),
            repo.observeMonth(november).first()!!.topCategories.map { it.category },
        )

        val december = repo.observeMonth(YearMonth.of(2026, 12)).first()!!
        assertEquals(0L, december.totalMinor)
        assertEquals(0, december.count)
        assertEquals(listOf(0, 0), december.people.map { it.count })
        assertEquals(listOf(0L, 0L), december.people.map { it.totalMinor })
        assertTrue(december.topCategories.isEmpty())
        assertTrue(december.latest.isEmpty())
        assertEquals(120_000L, december.lastMonthTotalMinor)
    }

    /** Stores a family expense as a pull would. */
    private suspend fun store(
        id: String,
        paidBy: String,
        amount: Long,
        on: LocalDate,
        category: Category = Category.GROCERIES,
        description: String = "Expense",
        deleted: Boolean = false,
    ) = env.transactions.run {
        dao.upsertExpense(
            GroupExpenseEntity(
                id, FAMILY, description, category, amount, on, paidBy, SplitMethod.EQUAL, auto = false, createdByMemberId = paidBy,
                updatedByMemberId = paidBy, updatedAt = Instant.ofEpochSecond(on.toEpochDay()), deleted = deleted, version = 3,
                syncState = SyncState.SYNCED,
            ),
        )
        env.mirrors.sync(id)
    }
}
