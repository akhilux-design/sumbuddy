package com.expenses.app.groups

import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.groups.api.ChangesPage
import com.expenses.app.groups.api.GroupDto
import com.expenses.app.groups.api.MeGroupsResponse
import com.expenses.app.groups.api.MemberDto
import com.expenses.app.groups.data.ActionResult
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.LinkEntity
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.data.joinsToUndo
import com.expenses.app.groups.sync.SyncOutcome
import com.expenses.splits.SplitMethod
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The data the stage-2 screens build on (docs/groups-build-plan.md): the owner label, undoing a join by link, the
 * banner rule, refused changes kept for "Not saved", split labels and the count of unsent changes.
 */
@RunWith(RobolectricTestRunner::class)
class OwnerAndUndoJoinTest {
    private val env = GroupsTestEnv()
    private val dao = env.db.groupDao()
    private val extras = env.db.groupExtrasDao()
    private val rejected = env.rejectedChanges

    @After fun close() = env.close()

    private val vikram = "a0000000-0000-4000-8000-000000000003"
    private val week = Duration.ofDays(7).toMillis()
    private val undoPath = "/v1/groups/$G1/members/$M_PRIYA/undo-join"

    /** Priya's place, taken by Vikram through her link [ago] before now. */
    private suspend fun vikramInPriyasPlace(ago: Duration = Duration.ofHours(1)) {
        val joinedAt = env.clock.instant().minus(ago)
        dao.upsertMembers(
            listOf(
                MemberEntity(
                    M_PRIYA, G1, vikram, "Vikram", null, null, onApp = true, removed = false, version = 6,
                    joinedAt = joinedAt, undoUntil = joinedAt.plusMillis(week), placeName = "Priya",
                ),
            ),
        )
    }

    @Test fun aPullKeepsTheOwnerTheJoinWindowAndTheFriendsName() = runTest {
        env.seedGroup(version = 5)
        val joinedAt = env.clock.millis() - 60_000
        val joined = MemberDto(
            M_PRIYA, G1, vikram, "Vikram", onApp = true, version = 6, joinedAt = joinedAt, undoUntil = joinedAt + week, canUndo = true,
        )
        val group = GroupDto(G1, "group", "Flat 302", ME_USER, 6, 0, ownerMemberId = M_ME)
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 6)))
        env.fake.on("GET", "/v1/groups/$G1/changes", json(200, ChangesPage.serializer(), page(from = 5, to = 6, members = listOf(joined)).copy(group = group)))

        assertEquals(SyncOutcome.DONE, env.engine.sync())

        assertEquals(M_ME, dao.getGroup(G1)!!.ownerMemberId)
        val member = dao.getMember(M_PRIYA)!!
        assertEquals("Vikram", member.name)
        assertEquals("Priya", member.placeName)
        assertEquals(Instant.ofEpochMilli(joinedAt + week), member.undoUntil)
        val detail = env.groups.observeGroup(G1).first()!!
        assertEquals(listOf(M_PRIYA), detail.joinsToUndo(env.clock.instant()).map { it.id })
        // The 7 days end: no banner, whatever the server said when it sent the row.
        assertTrue(detail.joinsToUndo(Instant.ofEpochMilli(joinedAt + week)).isEmpty())
    }

    @Test fun onlyTheOwnerSeesAJoinToUndo() = runTest {
        env.seedGroup()
        vikramInPriyasPlace()
        val mine = env.groups.observeGroup(G1).first()!!
        assertEquals(listOf(M_PRIYA), mine.joinsToUndo(env.clock.instant()).map { it.id })
        dao.upsertGroup(dao.getGroup(G1)!!.copy(ownerUserId = RAHUL_USER, ownerMemberId = M_RAHUL))
        val theirs = env.groups.observeGroup(G1).first()!!
        assertTrue(theirs.joinsToUndo(env.clock.instant()).isEmpty())
    }

    @Test fun undoJoinPutsTheFriendBackAndForgetsTheLink() = runTest {
        env.seedGroup()
        vikramInPriyasPlace()
        extras.upsertLink(LinkEntity(M_PRIYA, G1, "f0000000-0000-4000-8000-0000000000aa", "t".repeat(24), Instant.EPOCH, Instant.EPOCH))
        val restored = MemberDto(M_PRIYA, G1, null, "Priya", phone = "+919000000001", onApp = false, version = 7)
        env.fake.on("POST", undoPath, json(200, MemberDto.serializer(), restored))
        val syncsBefore = env.scheduler.requests.get()

        assertEquals(ActionResult.Done, env.groups.undoJoin(G1, M_PRIYA))

        val member = dao.getMember(M_PRIYA)!!
        assertNull(member.userId)
        assertEquals("Priya", member.name)
        assertEquals("+919000000001", member.phone)
        assertNull(member.undoUntil)
        assertNull(member.placeName)
        assertNull(extras.linkFor(M_PRIYA))
        assertTrue(env.scheduler.requests.get() > syncsBefore)
        assertNotNull(env.fake.requestsTo("POST", undoPath).single().getHeader("Idempotency-Key"))
        assertTrue(env.groups.observeGroup(G1).first()!!.joinsToUndo(env.clock.instant()).isEmpty())
    }

    @Test fun anUndoTooLateSaysWhyAndTheBannerGoesAtOnce() = runTest {
        env.seedGroup()
        // This phone's clock is behind the server's: by its own clock the 7 days have not ended yet.
        vikramInPriyasPlace(ago = Duration.ofDays(6))
        env.fake.on("POST", undoPath, error(409, "undo_expired"))
        val syncsBefore = env.scheduler.requests.get()

        assertEquals(ActionResult.Failed("undo_expired"), env.groups.undoJoin(G1, M_PRIYA))

        val member = dao.getMember(M_PRIYA)!!
        assertEquals(vikram, member.userId)
        assertNull(member.undoUntil)
        assertNull(member.joinedAt)
        // A pull would not send the row again (time passing changes no version), so the banner must go here.
        assertTrue(env.groups.observeGroup(G1).first()!!.joinsToUndo(env.clock.instant()).isEmpty())
        assertTrue(env.scheduler.requests.get() > syncsBefore)
    }

    @Test fun anUndoBySomeoneNoLongerTheOwnerPullsAgain() = runTest {
        env.seedGroup()
        vikramInPriyasPlace()
        env.fake.on("POST", undoPath, error(403, "not_owner"))
        val syncsBefore = env.scheduler.requests.get()

        assertEquals(ActionResult.Failed("not_owner"), env.groups.undoJoin(G1, M_PRIYA))

        assertTrue(env.scheduler.requests.get() > syncsBefore)
    }

    @Test fun anUndoInAGroupThatIsGoneRemovesItFromThisPhone() = runTest {
        env.seedGroup()
        vikramInPriyasPlace()
        env.fake.on("POST", undoPath, error(410, "group_deleted"))

        assertEquals(ActionResult.Failed("group_deleted"), env.groups.undoJoin(G1, M_PRIYA))

        assertNull(dao.getGroup(G1))
        assertNull(env.groups.observeGroup(G1).first())
    }

    @Test fun aStoredLinkTurnedOffElsewhereIsReplacedOnce() = runTest {
        env.seedGroup()
        val old = LinkEntity(M_PRIYA, G1, "f0000000-0000-4000-8000-0000000000aa", "t".repeat(24), env.clock.instant(), env.clock.instant())
        extras.upsertLink(old)
        env.fake.on("PUT", "/v1/groups/$G1/members/$M_PRIYA/links/${old.linkId}", error(404, "link_not_found"))
        val fresh = env.madeId(1)
        env.fake.on(
            "PUT", "/v1/groups/$G1/members/$M_PRIYA/links/$fresh",
            json(201, """{"linkId":"$fresh","url":"https://links.test/pay/x","createdAt":0,"expiresAt":0}"""),
        )

        val url = (env.groups.shareLink(G1, M_PRIYA) as com.expenses.app.groups.api.ApiResult.Ok).value

        val stored = extras.linkFor(M_PRIYA)!!
        assertEquals(fresh, stored.linkId)
        assertEquals("https://links.test/pay/${stored.token}", url)
        assertTrue(stored.token != old.token)
        assertEquals(1, env.fake.requestsTo("PUT", "/v1/groups/$G1/members/$M_PRIYA/links/$fresh").size)
    }

    @Test fun aPulledJoinOrUndoForgetsTheLinkThisPhoneMade() = runTest {
        env.seedGroup(version = 5)
        extras.upsertLink(LinkEntity(M_PRIYA, G1, "f0000000-0000-4000-8000-0000000000aa", "t".repeat(24), Instant.EPOCH, Instant.EPOCH))
        assertEquals(setOf(M_PRIYA), env.groups.observeLinkedMembers(G1).first())
        // Someone joined in Priya's place (the server turned her links off).
        val joined = MemberDto(M_PRIYA, G1, vikram, "Vikram", onApp = true, version = 6)
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 6)))
        env.fake.on("GET", "/v1/groups/$G1/changes", json(200, ChangesPage.serializer(), page(from = 5, to = 6, members = listOf(joined))))

        assertEquals(SyncOutcome.DONE, env.engine.sync())

        assertTrue(env.groups.observeLinkedMembers(G1).first().isEmpty())
    }

    @Test fun aFriendKnowsTheirDirectSplitWithoutMakingOne() = runTest {
        env.seedGroup()
        env.seedGroup(
            id = G2, myMember = M_ME2,
            others = listOf(MemberEntity(M_RAHUL2, G2, RAHUL_USER, "Rahul", null, null, onApp = true, removed = false, version = 1)),
        )
        dao.upsertGroup(dao.getGroup(G2)!!.copy(kind = GroupKind.DIRECT, name = null))
        env.groups.addGroupExpense(
            G2,
            com.expenses.app.groups.data.GroupExpenseDraft(
                "Auto", com.expenses.app.data.category.Category.TRAVEL, 20_000, java.time.LocalDate.of(2026, 11, 12), M_ME2,
                SplitMethod.EQUAL, mapOf(M_ME2 to null, M_RAHUL2 to null),
            ),
        )
        // Priya owes in Flat 302 only: no direct split with her.
        env.groups.addGroupExpense(
            G1,
            com.expenses.app.groups.data.GroupExpenseDraft(
                "Snacks", com.expenses.app.data.category.Category.FOOD, 30_000, java.time.LocalDate.of(2026, 11, 12), M_ME,
                SplitMethod.EQUAL, mapOf(M_ME to null, M_PRIYA to null),
            ),
        )
        val groupsBefore = dao.allGroups().size

        val friends = env.groups.observeFriends().first().associateBy { it.name }

        assertEquals(G2, friends.getValue("Rahul").directGroupId)
        assertNull(friends.getValue("Priya").directGroupId)
        assertEquals(groupsBefore, dao.allGroups().size)
    }

    @Test fun theOwnerIsKnownBeforeTheServerSendsOwnerMemberId() = runTest {
        env.seedGroup()
        assertNull(dao.getGroup(G1)!!.ownerMemberId)
        assertEquals(M_ME, env.groups.observeGroup(G1).first()!!.ownerMemberId)

        dao.upsertGroup(dao.getGroup(G1)!!.copy(ownerUserId = RAHUL_USER))
        assertEquals(M_RAHUL, env.groups.observeGroup(G1).first()!!.ownerMemberId)

        dao.upsertGroup(dao.getGroup(G1)!!.copy(ownerMemberId = M_PRIYA))
        assertEquals(M_PRIYA, env.groups.observeGroup(G1).first()!!.ownerMemberId)
    }

    @Test fun turningOffAFriendsLinkNeedsTheServer() = runTest {
        env.seedGroup()
        extras.upsertLink(LinkEntity(M_PRIYA, G1, "f0000000-0000-4000-8000-0000000000aa", "t".repeat(24), Instant.EPOCH, Instant.EPOCH))
        assertEquals(setOf(M_PRIYA), env.groups.observeLinkedMembers(G1).first())
        env.fake.on("DELETE", "/v1/groups/$G1/members/$M_PRIYA/links", okhttp3.mockwebserver.MockResponse().setResponseCode(204))

        assertEquals(ActionResult.Done, env.groups.turnOffLinks(G1, M_PRIYA))

        assertTrue(env.groups.observeLinkedMembers(G1).first().isEmpty())
    }

    @Test fun aRefusedSplitIsKeptForTheGroupPageAndDismissed() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        val id = env.groups.splitPayment(
            payment, G1, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = false, auto = false,
        )
        assertEquals(1, env.groups.observeUnsentCount().first())
        env.fake.on("PUT", "/v1/groups/$G1/expenses/$id", error(409, "exists_different"))

        assertEquals(SyncOutcome.DONE, env.engine.push())

        // Rolled back first: the payment waits again and the expense is gone.
        assertNull(dao.getExpense(id))
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(payment)!!.status)
        assertEquals(0, env.groups.observeUnsentCount().first())
        val kept = rejected.observe(G1).first().single()
        assertEquals(id, kept.entityId)
        assertEquals(EntityKind.EXPENSE, kept.entityKind)
        assertEquals(OutboxOp.CREATE, kept.op)
        assertEquals("exists_different", kept.errorCode)
        assertTrue(kept.bodyJson!!.contains("Swiggy"))
        assertTrue(kept.restoreJson!!.contains("\"expenseId\":$payment"))

        rejected.dismiss(kept.id)
        assertTrue(rejected.observe(G1).first().isEmpty())
    }

    @Test fun refusedChangesGoWithTheirGroup() = runTest {
        env.seedGroup()
        val id = env.groups.addGroupExpense(
            G1,
            com.expenses.app.groups.data.GroupExpenseDraft(
                "Snacks", com.expenses.app.data.category.Category.FOOD, 30_000, java.time.LocalDate.of(2026, 11, 12), M_RAHUL,
                SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null),
            ),
        )
        env.fake.on("PUT", "/v1/groups/$G1/expenses/$id", error(400, "bad_request"))
        env.engine.push()
        assertEquals(1, rejected.observe(G1).first().size)

        env.local.removeGroup(G1)

        assertTrue(rejected.observe(G1).first().isEmpty())
    }

    @Test fun splitLabelsNameTheGroupAndTheShare() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        val id = env.groups.splitPayment(
            payment, G1, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = false, auto = false,
        )

        val label = env.groups.observeSplitLabel(id).first()!!

        assertEquals(G1, label.groupId)
        assertEquals(GroupKind.GROUP, label.groupKind)
        assertEquals("Flat 302", label.groupName)
        assertEquals(45_000L, label.totalMinor)
        assertEquals(15_000L, label.myShareMinor)
        assertTrue(label.paidByMe)
        assertEquals("Arjun", label.paidByName)
        assertTrue(label.notSent)
    }

    @Test fun aSplitRuleCanSplitByItselfOrBeForgotten() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        env.groups.splitPayment(
            payment, G1, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = true, auto = false,
        )
        assertEquals(false, env.groups.splitRuleFor("Swiggy").first()!!.auto)

        env.groups.setSplitRuleAuto("Swiggy", auto = true)
        assertEquals(true, env.groups.splitRuleFor("Swiggy").first()!!.auto)

        env.groups.forgetSplitRule("Swiggy")
        assertNull(env.groups.splitRuleFor("Swiggy").first())
    }

    @Test fun aNewGroupIsOwnedByItsMaker() = runTest {
        val id = env.groups.createGroup("Goa trip", emptyList())
        val group = dao.getGroup(id)!!
        assertEquals(group.myMemberId, group.ownerMemberId)
        assertTrue(env.groups.observeGroup(id).first()!!.isOwner)
    }
}
