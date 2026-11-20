package com.expenses.app.groups

import com.expenses.app.data.category.Category
import com.expenses.app.groups.api.ChangesPage
import com.expenses.app.groups.api.MeGroupsResponse
import com.expenses.app.groups.api.MemberDto
import com.expenses.app.groups.data.GroupEntity
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.NewPerson
import com.expenses.app.groups.data.Person
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.sync.SyncOutcome
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.components.SelfAvatarColor
import com.expenses.splits.SplitMethod
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The avatar colours from this phone's own data (`GroupsRepository.observeAvatarColors()` and the colours the reads
 * carry): people by the order they were stored in, groups by age, the same colour on every read, and no change when
 * a friend joins the app in their place or that join is undone.
 */
@RunWith(RobolectricTestRunner::class)
class AvatarColorsTest {
    private val env = GroupsTestEnv()
    private val dao = env.db.groupDao()

    @After fun close() = env.close()

    private val vikram = "a0000000-0000-4000-8000-000000000003"
    private val priyaUser = "a0000000-0000-4000-8000-000000000004"
    private val kiran = "c0000000-0000-4000-8000-000000000004"
    private val priya2 = "c0000000-0000-4000-8000-000000000013"
    private val meera2 = "c0000000-0000-4000-8000-000000000014"
    private val priyasPhone = "+919000000001"

    private fun friend(id: String, groupId: String, name: String, phone: String? = null) =
        MemberEntity(id, groupId, null, name, phone, null, onApp = false, removed = false, version = 1)

    private fun user(id: String, groupId: String, userId: String, name: String, phone: String? = null) =
        MemberEntity(id, groupId, userId, name, phone, null, onApp = true, removed = false, version = 1)

    /** A synced named group made at [createdAt] seconds, with the person first and then [others], stored in that order. */
    private suspend fun group(id: String, name: String, createdAt: Long, myMember: String, others: List<MemberEntity>) {
        dao.upsertGroup(
            GroupEntity(id, GroupKind.GROUP, name, ME_USER, myMember, 5, 5, syncState = SyncState.SYNCED, createdAt = Instant.ofEpochSecond(createdAt)),
        )
        dao.upsertMembers(listOf(user(myMember, id, ME_USER, "Arjun")) + others)
    }

    /** Flat 302 with Rahul (on the app), Priya (a friend, by phone) and Kiran (a friend, by name), stored in that order. */
    private suspend fun flat302(createdAt: Long = 100) = group(
        G1, "Flat 302", createdAt, M_ME,
        listOf(user(M_RAHUL, G1, RAHUL_USER, "Rahul"), friend(M_PRIYA, G1, "Priya", priyasPhone), friend(kiran, G1, "Kiran")),
    )

    private suspend fun colours() = env.groups.observeAvatarColors().first()

    private suspend fun colourOnGroupPage(groupId: String, memberId: String): AvatarColor =
        env.groups.observeGroup(groupId).first()!!.members.single { it.member.id == memberId }.avatar

    @Test fun peopleAreColouredInTheOrderTheyWereStoredNotByName() = runTest {
        flat302()

        // Rahul, Priya, Kiran as stored: green, pink, blue, as the design draws them. By name Kiran would come first.
        assertEquals(AvatarColor.Green, colours().ofMember(M_RAHUL))
        assertEquals(AvatarColor.Pink, colours().ofMember(M_PRIYA))
        assertEquals(AvatarColor.Blue, colours().ofMember(kiran))
        assertEquals(SelfAvatarColor, colours().ofMember(M_ME))
    }

    @Test fun aChangedRowKeepsItsPlaceInTheOrder() = runTest {
        flat302()

        // Rahul is renamed and written again, as a pull does: his row is updated, not stored anew.
        dao.upsertMembers(listOf(user(M_RAHUL, G1, RAHUL_USER, "Zubin")))

        assertEquals(AvatarColor.Green, colours().ofMember(M_RAHUL))
        assertEquals(AvatarColor.Pink, colours().ofMember(M_PRIYA))
    }

    @Test fun aJoinAndAnUndoneJoinKeepTheColour() = runTest {
        flat302()
        val before = colours()
        assertEquals(AvatarColor.Pink, before.ofMember(M_PRIYA))

        // Vikram takes Priya's place through her link: the same member row, now an app user.
        dao.upsertMembers(listOf(user(M_PRIYA, G1, vikram, "Vikram").copy(placeName = "Priya")))
        val joined = colours()
        assertEquals(AvatarColor.Pink, joined.ofMember(M_PRIYA))
        assertEquals(AvatarColor.Pink, colourOnGroupPage(G1, M_PRIYA))
        assertEquals(before.ofMember(M_RAHUL), joined.ofMember(M_RAHUL))
        assertEquals(before.ofMember(kiran), joined.ofMember(kiran))

        // The owner undoes it: the place is Priya, a friend without the app, again.
        dao.upsertMembers(listOf(friend(M_PRIYA, G1, "Priya", priyasPhone)))
        assertEquals(before, colours())
    }

    @Test fun aJoinPulledFromTheServerKeepsTheColour() = runTest {
        flat302()
        val joined = MemberDto(M_PRIYA, G1, priyaUser, "Priya Nair", onApp = true, version = 6)
        env.fake.on("GET", "/v1/me/groups", json(200, MeGroupsResponse.serializer(), meGroups(G1 to 6)))
        env.fake.on("GET", "/v1/groups/$G1/changes", json(200, ChangesPage.serializer(), page(from = 5, to = 6, members = listOf(joined))))

        assertEquals(SyncOutcome.DONE, env.engine.sync())

        assertEquals(priyaUser, dao.getMember(M_PRIYA)!!.userId)
        assertEquals(AvatarColor.Pink, colours().ofMember(M_PRIYA))
        assertEquals(AvatarColor.Blue, colours().ofMember(kiran))
    }

    @Test fun theSamePersonInTwoGroupsHasOneColourOnEveryRead() = runTest {
        flat302(createdAt = 100)
        // Goa trip is newer: Meera is new, Priya (the same phone) and Rahul (the same user) are known from Flat 302.
        group(
            G2, "Goa trip", 200, M_ME2,
            listOf(friend(meera2, G2, "Meera"), friend(priya2, G2, "Priya", priyasPhone), user(M_RAHUL2, G2, RAHUL_USER, "Rahul")),
        )
        // Everyone owes the person something in both groups, so Friends lists them.
        for ((groupId, me, others) in listOf(Triple(G1, M_ME, listOf(M_RAHUL, M_PRIYA, kiran)), Triple(G2, M_ME2, listOf(meera2, priya2, M_RAHUL2)))) {
            env.groups.addGroupExpense(
                groupId,
                GroupExpenseDraft("Dinner", Category.FOOD, 40_000, LocalDate.of(2026, 11, 12), me, SplitMethod.EQUAL, (others + me).associateWith { null }),
            )
        }

        val colours = colours()
        assertEquals(AvatarColor.Green, colours.ofMember(M_RAHUL))
        assertEquals(AvatarColor.Green, colours.ofMember(M_RAHUL2))
        assertEquals(AvatarColor.Pink, colours.ofMember(M_PRIYA))
        assertEquals(AvatarColor.Pink, colours.ofMember(priya2))
        // Meera is the fourth person seen, though stored first in the newer group.
        assertEquals(AvatarColor.Orange, colours.ofMember(meera2))

        // The Group pages and Friends on Groups carry the same colours.
        assertEquals(AvatarColor.Green, colourOnGroupPage(G1, M_RAHUL))
        assertEquals(AvatarColor.Green, colourOnGroupPage(G2, M_RAHUL2))
        assertEquals(AvatarColor.Pink, colourOnGroupPage(G2, priya2))
        assertEquals(SelfAvatarColor, colourOnGroupPage(G2, M_ME2))
        val friends = env.groups.observeFriends().first().associate { it.name to it.avatar }
        assertEquals(
            mapOf("Rahul" to AvatarColor.Green, "Priya" to AvatarColor.Pink, "Kiran" to AvatarColor.Blue, "Meera" to AvatarColor.Orange),
            friends,
        )
    }

    @Test fun aFriendByPhoneWhoIsOnTheAppInAnotherGroupHasOneColour() = runTest {
        flat302(createdAt = 100)
        // In the newer group Priya is on the app, with the phone number she was added by in Flat 302.
        group(G2, "Goa trip", 200, M_ME2, listOf(friend(meera2, G2, "Meera"), user(priya2, G2, priyaUser, "Priya Nair", priyasPhone)))

        assertEquals(AvatarColor.Pink, colours().ofMember(priya2))
        assertEquals(AvatarColor.Pink, colours().ofPerson(priyaUser))
        assertEquals(AvatarColor.Orange, colours().ofMember(meera2))
    }

    @Test fun groupsAreColouredByAgeWhateverTheirIds() = runTest {
        // G2 is the older one.
        group(G2, "Flat 302", 100, M_ME2, emptyList())
        group(G1, "Goa trip", 200, M_ME, emptyList())

        val rows = env.groups.observeGroups().first().associate { it.name to it.avatar }
        assertEquals(mapOf("Flat 302" to AvatarColor.Green, "Goa trip" to AvatarColor.Yellow), rows)
        assertEquals(AvatarColor.Green, colours().ofGroup(G2))
        assertEquals(AvatarColor.Yellow, colours().ofGroup(G1))
    }

    @Test fun groupsMadeAtTheSameMomentAreOrderedById() = runTest {
        group(G2, "Second", 100, M_ME2, emptyList())
        group(G1, "First", 100, M_ME, emptyList())

        assertEquals(AvatarColor.Green, colours().ofGroup(G1))
        assertEquals(AvatarColor.Yellow, colours().ofGroup(G2))
    }

    @Test fun aSplitWithOneFriendHasTheFriendsColour() = runTest {
        flat302()
        val direct = env.groups.directGroupWith(Person(priyasPhone, "Priya", null, priyasPhone))

        assertEquals(AvatarColor.Pink, colours().ofGroup(direct))
        // It takes no group colour: the next named group is the second.
        val goa = env.groups.createGroup("Goa trip", listOf(NewPerson.Friend("Meera", null)))
        assertEquals(AvatarColor.Yellow, colours().ofGroup(goa))
    }

    @Test fun thePersonIsTheirOwnColourInEveryGroup() = runTest {
        flat302(createdAt = 100)
        group(G2, "Goa trip", 200, M_ME2, listOf(friend(meera2, G2, "Meera")))

        assertEquals(SelfAvatarColor, colours().ofMember(M_ME))
        assertEquals(SelfAvatarColor, colours().ofMember(M_ME2))
        assertEquals(SelfAvatarColor, colourOnGroupPage(G1, M_ME))
        listOf(M_RAHUL, M_PRIYA, kiran, meera2).forEach { assertEquals(false, colours().ofMember(it) == SelfAvatarColor) }
    }

    @Test fun aGroupMadeOnThisPhoneColoursItsPeopleInTheOrderTheyWereChosen() = runTest {
        val id = env.groups.createGroup(
            "Flat 302",
            listOf(NewPerson.KnownUser(RAHUL_USER, "Rahul"), NewPerson.Friend("Priya", priyasPhone), NewPerson.Friend("Kiran", null)),
        )

        val byName = env.groups.observeGroup(id).first()!!.members.associate { it.member.name to it.avatar }
        assertEquals(
            mapOf("Arjun" to SelfAvatarColor, "Rahul" to AvatarColor.Green, "Priya" to AvatarColor.Pink, "Kiran" to AvatarColor.Blue),
            byName,
        )
    }

    @Test fun aFamilyChangesNoOtherColourAndItsPlaceholderTakesNone() = runTest {
        flat302(createdAt = 100)
        val without = colours()
        // The family is older than Flat 302, and its placeholder was stored before the partner.
        dao.upsertGroup(
            GroupEntity(FAMILY, GroupKind.FAMILY, "Home", ME_USER, M_FME, 3, 3, syncState = SyncState.SYNCED, createdAt = Instant.ofEpochSecond(10)),
        )
        dao.upsertMembers(
            listOf(
                user(M_FME, FAMILY, ME_USER, "Arjun"),
                friend(M_PLACEHOLDER, FAMILY, "Partner"),
                user(M_MEERA, FAMILY, "a0000000-0000-4000-8000-000000000099", "Meera"),
            ),
        )

        val with = colours()
        listOf(M_RAHUL, M_PRIYA, kiran).forEach { assertEquals(without.ofMember(it), with.ofMember(it)) }
        assertEquals(without.ofGroup(G1), with.ofGroup(G1))
        assertEquals(SelfAvatarColor, with.ofMember(M_FME))
        // Meera is the fourth person: orange. Had the placeholder taken a place, she would be yellow.
        assertEquals(AvatarColor.Orange, with.ofMember(M_MEERA))
        // A family takes no group colour: the next named group is the second.
        val goa = env.groups.createGroup("Goa trip", emptyList())
        assertEquals(AvatarColor.Yellow, colours().ofGroup(goa))
    }

    @Test fun aPartnerAlreadyInAGroupKeepsTheirColourInTheFamily() = runTest {
        flat302(createdAt = 100)
        env.seedFamily(createdAt = Instant.ofEpochSecond(10))
        dao.upsertMembers(listOf(user(M_MEERA, FAMILY, RAHUL_USER, "Rahul")))

        assertEquals(colours().ofMember(M_RAHUL), colours().ofMember(M_MEERA))
        assertEquals(AvatarColor.Green, colours().ofMember(M_MEERA))
    }
}
