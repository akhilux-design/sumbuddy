package com.expenses.app.ui.screens.family

import androidx.lifecycle.SavedStateHandle
import com.expenses.app.R
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.api.CreateGroupResponse
import com.expenses.app.groups.api.GroupDto
import com.expenses.app.groups.api.LinkResponse
import com.expenses.app.groups.api.MemberDto
import com.expenses.app.groups.error
import com.expenses.app.groups.json
import com.expenses.app.ui.screens.signin.ActionProblem
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Set up Family (06-family/00, 09 to 12; docs/family.md 1.2) on the family data layer over the fake server. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SetUpFamilyViewModelTest {
    private val env = GroupsTestEnv()
    private val online = MutableStateFlow(true)
    private val effects = CopyOnWriteArrayList<SetUpFamilyEffect>()
    private val viewModels = ClearedViewModels()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
        env.close()
    }

    private fun TestScope.setUpFamily(saved: SavedStateHandle = SavedStateHandle()): SetUpFamilyViewModel {
        val vm = viewModels.keep { SetUpFamilyViewModel(env.family, online, saved, defaultName = "Home") }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effect.collect { effects += it } }
        return vm
    }

    /** The server sets the family up: ids made in order are the family (1), the person (2), the placeholder (3), the key (4). */
    private fun createAnswers() {
        val id = env.madeId(1)
        env.fake.on(
            "POST", "/v1/groups",
            json(
                201, CreateGroupResponse.serializer(),
                CreateGroupResponse(
                    GroupDto(id, "family", "Home", ME_USER, version = 1, createdAt = 1_000, ownerMemberId = env.madeId(2)),
                    listOf(
                        MemberDto(env.madeId(2), id, ME_USER, "Arjun Mehta", onApp = true, version = 1),
                        MemberDto(env.madeId(3), id, null, "Partner", version = 1),
                    ),
                ),
            ),
        )
    }

    private fun linkPath(linkId: String) = "/v1/groups/${env.madeId(1)}/members/${env.madeId(3)}/links/$linkId"

    private fun linkAnswer(linkId: String) =
        env.fake.on("PUT", linkPath(linkId), json(201, LinkResponse.serializer(), LinkResponse(linkId, "https://links.test/pay/abcdefghijklmnopqrstuvwx")))

    /** Waits, in real time, until [count] effects arrived. */
    private suspend fun awaitEffects(count: Int) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (effects.size < count) delay(10) }
    }

    private suspend fun SetUpFamilyViewModel.awaitIdle(): SetUpFamilyUiState = withContext(Dispatchers.Default) {
        withTimeout(5_000) { state.first { !it.busy } }
    }

    @Test fun itStartsWithHomeAndCountsThisPhonesEarlierFamilyExpenses() = runTest {
        env.familyPayment(234_000, day = 3)
        env.familyPayment(99_000, day = 9)
        val vm = setUpFamily()

        val state = vm.state.first { it.earlierCount == 2 }
        assertEquals("Home", state.name)
        assertFalse(state.nameError)
    }

    @Test fun aBlankNameSaysSoAndSendsNothing() = runTest {
        val vm = setUpFamily()
        vm.onEvent(SetUpFamilyEvent.NameChanged("   "))
        assertTrue(vm.state.value.nameError)

        vm.onEvent(SetUpFamilyEvent.Invite)

        assertTrue(env.fake.requests.isEmpty())
        vm.onEvent(SetUpFamilyEvent.NameChanged("Our home"))
        assertFalse(vm.state.value.nameError)
    }

    @Test fun offlineNothingIsMadeAndTheBannerShows() = runTest {
        online.value = false
        val vm = setUpFamily()

        vm.onEvent(SetUpFamilyEvent.Invite)

        assertEquals(ActionProblem.OFFLINE, vm.state.value.problem)
        assertEquals(1, vm.state.value.problemCount)
        assertTrue(env.fake.requests.isEmpty())
    }

    @Test fun invitePartnerSetsTheFamilyUpSharesTheLinkAndOpensHome() = runTest {
        createAnswers()
        linkAnswer(env.madeId(5))
        val vm = setUpFamily()

        vm.onEvent(SetUpFamilyEvent.Invite)
        awaitEffects(2)

        val share = effects[0] as SetUpFamilyEffect.Share
        val link = share.invite.link
        assertTrue(link.startsWith("https://links.test/pay/"))
        assertEquals(FamilyInvite(link, "Arjun", "Home"), share.invite)
        assertEquals(SetUpFamilyEffect.Done, effects[1])
        val res = RuntimeEnvironment.getApplication().resources
        val appName = res.getString(R.string.app_name)
        assertEquals(
            listOf(
                "Arjun invited you to Home on $appName: one monthly total for the two of you.",
                "Get the app, then open this link on your phone to join:",
                link,
                "Only expenses you mark Family are shared. Never your SMS.",
            ),
            share.invite.message(res).lines(),
        )
    }

    @Test fun whenOnlyTheLinkFailedTheNextTapOnlyMakesTheLink() = runTest {
        createAnswers()
        // The first try is refused; the same link (or a new one) works the next time.
        env.fake.on(
            "PUT", linkPath(env.madeId(5)),
            error(400, "bad_request"),
            json(201, LinkResponse.serializer(), LinkResponse(env.madeId(5), "https://links.test/pay/abcdefghijklmnopqrstuvwx")),
        )
        val vm = setUpFamily()

        vm.onEvent(SetUpFamilyEvent.Invite)
        val failed = vm.awaitIdle()

        assertEquals(ActionProblem.FAILED, failed.problem)
        assertTrue(effects.isEmpty())
        // The family is set up already; the second tap makes the link only.
        linkAnswer(env.madeId(6))
        linkAnswer(env.madeId(7))
        vm.onEvent(SetUpFamilyEvent.Invite)
        awaitEffects(2)

        assertEquals(1, env.fake.requestsTo("POST", "/v1/groups").size)
        assertTrue(effects[0] is SetUpFamilyEffect.Share)
        assertEquals(SetUpFamilyEffect.Done, effects[1])
    }

    @Test fun aFamilyInTheAccountOpensHomeOnTheFamilySide() = runTest {
        env.fake.on("POST", "/v1/groups", error(409, "already_in_family"))
        val vm = setUpFamily()

        vm.onEvent(SetUpFamilyEvent.Invite)
        awaitEffects(1)

        assertEquals(listOf<SetUpFamilyEffect>(SetUpFamilyEffect.OpenFamily), effects.toList())
        assertTrue(env.scheduler.requests.get() > 0)
    }

    @Test fun aFamilyAlreadyOnThisPhoneOpensHomeAtOnce() = runTest {
        env.seedFamily()
        setUpFamily()
        awaitEffects(1)

        assertEquals(listOf<SetUpFamilyEffect>(SetUpFamilyEffect.OpenFamily), effects.toList())
        assertTrue(env.fake.requests.isEmpty())
    }
}
