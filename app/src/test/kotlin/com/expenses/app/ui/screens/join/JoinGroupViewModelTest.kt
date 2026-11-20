package com.expenses.app.ui.screens.join

import com.expenses.app.data.repository.TestClock
import com.expenses.app.groups.FAMILY
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_MEERA
import com.expenses.app.groups.api.AcceptInviteResponse
import com.expenses.app.groups.api.InvitePreviewDto
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.error
import com.expenses.app.groups.json
import com.expenses.app.ui.screens.family.ClearedViewModels
import com.expenses.app.ui.screens.signin.ActionProblem
import java.time.YearMonth
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Join group (02-everyday/32, 50) on the group data layer over the fake server. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class JoinGroupViewModelTest {
    private val env = GroupsTestEnv()
    private val online = MutableStateFlow(true)
    private val effects = CopyOnWriteArrayList<JoinGroupEffect>()
    private val viewModels = ClearedViewModels()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
        env.close()
    }

    private fun preview(alreadyMember: Boolean = false) = json(
        200, InvitePreviewDto.serializer(),
        InvitePreviewDto(
            groupId = G1, groupName = "Flat 302", inviterName = "Rahul", memberNames = listOf("Rahul", "Priya"), memberCount = 3,
            youOwePaise = 115_000, owedToYouPaise = 0, expensesThisMonth = 5, alreadyMember = alreadyMember,
        ),
    )

    /**
     * The connection drops once the request is in, so no answer comes. The fake server's dispatcher honours only this
     * socket policy: a response it hands back with `DISCONNECT_AT_START` is written as an empty 200.
     */
    private fun lostAnswer() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)

    private fun TestScope.joinGroup(): JoinGroupViewModel {
        val vm = viewModels.keep { JoinGroupViewModel(TOKEN, env.groups, online, TestClock, env.family) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effect.collect { effects += it } }
        return vm
    }

    private suspend fun JoinGroupViewModel.awaitReady(): JoinGroupUiState.Ready =
        state.first { it is JoinGroupUiState.Ready && !it.joining } as JoinGroupUiState.Ready

    /** Waits, in real time, until [count] effects arrived. */
    private suspend fun awaitEffects(count: Int) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (effects.size < count) delay(10) }
    }

    @Test fun thePreviewShowsTheGroupAndJoinOpensIt() = runTest {
        env.fake.on("GET", INVITE, preview())
        env.fake.on("POST", ACCEPT, json(200, AcceptInviteResponse.serializer(), AcceptInviteResponse(G1, M_ME, 9)))
        val vm = joinGroup()
        assertEquals(JoinGroupUiState.Loading, vm.state.value)

        vm.start()
        val ready = vm.awaitReady()
        assertEquals("Rahul", ready.preview.inviterName)
        assertEquals("Flat 302", ready.preview.groupName)
        assertEquals(listOf("Rahul", "Priya"), ready.preview.memberNames)
        assertEquals(115_000L, ready.preview.youOweMinor)
        assertEquals(5, ready.preview.expensesThisMonth)
        assertEquals(YearMonth.of(2026, 11), ready.month)
        // Shown again (after a sign-in, a rotation): no second lookup.
        vm.start()
        assertEquals(1, env.fake.requestsTo("GET", INVITE).size)

        val syncsBefore = env.scheduler.requests.get()
        vm.onEvent(JoinGroupEvent.Join)
        awaitEffects(1)

        assertEquals(listOf<JoinGroupEffect>(JoinGroupEffect.OpenGroup(G1)), effects.toList())
        assertEquals(1, env.fake.requestsTo("POST", ACCEPT).size)
        // The group is pulled; its page waits for it.
        assertTrue(env.scheduler.requests.get() > syncsBefore)
        // A second tap while it opens does nothing.
        vm.onEvent(JoinGroupEvent.Join)
        assertEquals(1, env.fake.requestsTo("POST", ACCEPT).size)
    }

    @Test fun someoneAlreadyInTheGroupGoesStraightToIt() = runTest {
        env.fake.on("GET", INVITE, preview(alreadyMember = true))
        val vm = joinGroup()

        vm.start()
        awaitEffects(1)

        assertEquals(listOf<JoinGroupEffect>(JoinGroupEffect.OpenGroup(G1)), effects.toList())
        assertTrue(env.fake.requestsTo("POST", ACCEPT).isEmpty())
    }

    @Test fun joiningAsAnotherMemberOpensTheGroupToo() = runTest {
        env.fake.on("GET", INVITE, preview())
        env.fake.on("POST", ACCEPT, error(409, "already_member"))
        val vm = joinGroup()
        vm.start()
        vm.awaitReady()

        vm.onEvent(JoinGroupEvent.Join)
        awaitEffects(1)

        assertEquals(listOf<JoinGroupEffect>(JoinGroupEffect.OpenGroup(G1)), effects.toList())
    }

    @Test fun aLinkThatNoLongerWorks() = runTest {
        // Turned off or ran out before the screen opened.
        env.fake.on("GET", INVITE, error(404, "link_not_found"))
        var vm = joinGroup()
        vm.start()
        vm.state.first { it == JoinGroupUiState.LinkNoLongerWorks }

        // Turned off while the screen was open, or used by someone else meanwhile.
        for (refusal in listOf(error(404, "link_not_found"), error(409, "already_claimed"))) {
            env.fake.on("GET", INVITE, preview())
            env.fake.on("POST", ACCEPT, refusal)
            vm = joinGroup()
            vm.start()
            vm.awaitReady()
            vm.onEvent(JoinGroupEvent.Join)
            vm.state.first { it == JoinGroupUiState.LinkNoLongerWorks }
        }
        assertTrue(effects.isEmpty())
    }

    @Test fun offline() = runTest {
        env.fake.on("GET", INVITE, preview())
        env.fake.on("POST", ACCEPT, json(200, AcceptInviteResponse.serializer(), AcceptInviteResponse(G1, M_ME, 9)))
        online.value = false
        val vm = joinGroup()

        // The lookup is not tried: the wording of 02-everyday/23 with Try again.
        vm.start()
        assertEquals(JoinGroupUiState.CouldNotLoad(offline = true), vm.state.value)
        assertTrue(env.fake.requestsTo("GET", INVITE).isEmpty())

        online.value = true
        vm.onEvent(JoinGroupEvent.TryAgain)
        vm.awaitReady()

        // Join group offline: nothing is tried, the banner says so and the group stays on screen.
        online.value = false
        vm.onEvent(JoinGroupEvent.Join)
        val shown = vm.state.value as JoinGroupUiState.Ready
        assertEquals(ActionProblem.OFFLINE, shown.problem)
        assertTrue(env.fake.requestsTo("POST", ACCEPT).isEmpty())

        // The connection drops during the join: the same.
        online.value = true
        env.fake.on("POST", ACCEPT, lostAnswer())
        vm.onEvent(JoinGroupEvent.Join)
        assertEquals(ActionProblem.OFFLINE, (vm.state.first { it is JoinGroupUiState.Ready && !it.joining } as JoinGroupUiState.Ready).problem)
        assertTrue(effects.isEmpty())

        // A lookup whose answer was lost says offline too.
        env.fake.on("GET", INVITE, lostAnswer())
        val again = joinGroup()
        again.start()
        assertEquals(JoinGroupUiState.CouldNotLoad(offline = true), again.state.first { it is JoinGroupUiState.CouldNotLoad })
    }

    @Test fun otherRefusalsShowTheBannerAndKeepTheGroup() = runTest {
        env.fake.on("GET", INVITE, preview())
        env.fake.on("POST", ACCEPT, error(409, "limit_reached"))
        val vm = joinGroup()
        vm.start()
        vm.awaitReady()

        vm.onEvent(JoinGroupEvent.Join)

        val shown = vm.state.first { it is JoinGroupUiState.Ready && it.problem != null } as JoinGroupUiState.Ready
        assertEquals(ActionProblem.FAILED, shown.problem)
        assertEquals("Flat 302", shown.preview.groupName)
        assertTrue(effects.isEmpty())

        // A lookup the server could not answer: Try again, not "offline".
        env.fake.on("GET", INVITE, error(503, "internal"))
        val other = joinGroup()
        other.start()
        assertEquals(JoinGroupUiState.CouldNotLoad(offline = false), other.state.first { it is JoinGroupUiState.CouldNotLoad })
    }

    // ---- A family's link: Join Family (06-family/01, 13 to 16; docs/family.md 1.4) ----

    private fun familyPreview(alreadyMember: Boolean = false, alreadyInFamily: Boolean = false) = json(
        200, InvitePreviewDto.serializer(),
        InvitePreviewDto(
            groupId = FAMILY, groupName = "Home", inviterName = "Arjun", memberNames = listOf("Arjun"), memberCount = 1,
            alreadyMember = alreadyMember, kind = "family", alreadyInFamily = alreadyInFamily,
        ),
    )

    private suspend fun JoinGroupViewModel.awaitFamily(): JoinGroupUiState.ReadyFamily =
        state.first { it is JoinGroupUiState.ReadyFamily && !it.joining } as JoinGroupUiState.ReadyFamily

    @Test fun aFamilyLinkShowsJoinFamilyWithTheEarlierExpensesAndJoinOpensHome() = runTest {
        env.familyPayment(234_000, day = 3)
        env.fake.on("GET", INVITE, familyPreview())
        env.fake.on("POST", ACCEPT, json(200, AcceptInviteResponse.serializer(), AcceptInviteResponse(FAMILY, M_MEERA, 4)))
        val vm = joinGroup()

        vm.start()
        val ready = vm.state.first { it is JoinGroupUiState.ReadyFamily && it.earlierCount == 1 } as JoinGroupUiState.ReadyFamily
        assertEquals("Home", ready.preview.groupName)
        assertEquals(GroupKind.FAMILY, ready.preview.kind)

        vm.onEvent(JoinGroupEvent.Join)
        awaitEffects(1)

        assertEquals(listOf<JoinGroupEffect>(JoinGroupEffect.OpenFamily), effects.toList())
        // The family is on this phone at once, loading, so Home never shows a frame without it.
        val family = env.family.observeFamily().first()!!
        assertEquals(FAMILY, family.groupId)
        assertEquals(LoadState.LOADING, family.load)
    }

    @Test fun someoneInAnotherFamilyIsToldSo() = runTest {
        env.fake.on("GET", INVITE, familyPreview(alreadyInFamily = true))
        val vm = joinGroup()

        vm.start()

        assertEquals(JoinGroupUiState.AlreadyInFamily, vm.state.first { it == JoinGroupUiState.AlreadyInFamily })
        assertTrue(env.fake.requestsTo("POST", ACCEPT).isEmpty())
    }

    @Test fun acceptRefusedAsAlreadyInAFamilySaysSo() = runTest {
        env.fake.on("GET", INVITE, familyPreview())
        env.fake.on("POST", ACCEPT, error(409, "already_in_family"))
        val vm = joinGroup()
        vm.start()
        vm.awaitFamily()

        vm.onEvent(JoinGroupEvent.Join)

        assertEquals(JoinGroupUiState.AlreadyInFamily, vm.state.first { it == JoinGroupUiState.AlreadyInFamily })
        assertTrue(effects.isEmpty())
    }

    @Test fun someoneAlreadyInTheFamilyGoesStraightToHome() = runTest {
        env.fake.on("GET", INVITE, familyPreview(alreadyMember = true))
        val vm = joinGroup()
        val before = env.scheduler.requests.get()

        vm.start()
        awaitEffects(1)

        assertEquals(listOf<JoinGroupEffect>(JoinGroupEffect.OpenFamily), effects.toList())
        // Joined on another phone maybe: the family comes to this one with a sync (code review, Family build).
        assertTrue(env.scheduler.requests.get() > before)
    }

    @Test fun joinFamilyOfflineOrRefusedShowsTheBanner() = runTest {
        env.fake.on("GET", INVITE, familyPreview())
        env.fake.on("POST", ACCEPT, error(409, "limit_reached"))
        val vm = joinGroup()
        vm.start()
        vm.awaitFamily()

        online.value = false
        vm.onEvent(JoinGroupEvent.Join)
        assertEquals(ActionProblem.OFFLINE, (vm.state.value as JoinGroupUiState.ReadyFamily).problem)
        assertTrue(env.fake.requestsTo("POST", ACCEPT).isEmpty())

        online.value = true
        vm.onEvent(JoinGroupEvent.Join)
        val refused = vm.state.first { it is JoinGroupUiState.ReadyFamily && it.problem == ActionProblem.FAILED } as JoinGroupUiState.ReadyFamily
        assertEquals(2, refused.problemCount)
        assertTrue(effects.isEmpty())
        assertNull(env.family.observeFamily().first())
    }

    private companion object {
        const val TOKEN = "AbCdEfGhIjKlMnOpQrStUvWx"
        const val INVITE = "/v1/invites/$TOKEN"
        const val ACCEPT = "/v1/invites/$TOKEN/accept"
    }
}
