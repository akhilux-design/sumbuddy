package com.expenses.app.ui.screens.settle

import android.app.Application
import com.expenses.app.groups.FakeServer
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.api.LinkResponse
import com.expenses.app.groups.error
import com.expenses.app.groups.json
import com.expenses.app.groups.match.groupExpense
import com.expenses.app.groups.sync.NetworkStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Remind on WhatsApp (design 03-getting-paid-back/05; docs/groups-build-plan.md 4.4 task 6). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RemindViewModelTest {
    private val env = GroupsTestEnv()
    private val network = object : NetworkStatus {
        val state = MutableStateFlow(true)
        override val online: StateFlow<Boolean> = state
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    /** Priya (no app) owes the person ₹1,150 in Flat 302, as in the design. */
    private suspend fun priyaOwes() {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 230_000, listOf(M_ME, M_PRIYA))
    }

    /** The server answers every link the repository may make for Priya with [response]. */
    private fun linkAnswers(response: MockResponse) {
        for (n in 1..LINK_IDS) env.fake.on("PUT", "/v1/groups/$G1/members/$M_PRIYA/links/${env.madeId(n)}", response)
    }

    private fun linkRequests() = env.fake.requests.filter { it.method == "PUT" && it.requestUrl!!.encodedPath.contains("/links/") }

    private fun viewModel() = RemindViewModel(G1, M_PRIYA, env.groups, network, myName = { "Arjun" })

    private suspend fun RemindViewModel.shown(predicate: (RemindUiState.Shown) -> Boolean = { true }): RemindUiState.Shown =
        state.first { it is RemindUiState.Shown && predicate(it) } as RemindUiState.Shown

    @Test fun theMessageNamesTheFriendThePersonTheAmountAndTheRealLink() = runTest {
        priyaOwes()
        linkAnswers(json(201, LinkResponse.serializer(), LinkResponse("l", "u")))
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }

        val shown = vm.shown { it.link != null }

        assertEquals("Priya", shown.friendName)
        assertEquals("Arjun", shown.myName)
        assertEquals(115_000L, shown.amountMinor)
        assertEquals("Flat 302", shown.groupName)
        assertNull(shown.problem)
        val link = shown.link!!
        assertTrue(link, Regex("https://links\\.test/pay/[A-Za-z0-9_-]{24}").matches(link))
        val message = RemindMessage.of(RuntimeEnvironment.getApplication().resources, shown.friendName, shown.myName, shown.amountMinor, shown.groupName, link)
        assertEquals(
            "Hey Priya You owe Arjun ₹1,150 for Flat 302.\nSee the breakdown and pay by UPI:\n$link\nNo app needed.",
            message.text,
        )
        assertEquals(link.removePrefix("https://"), message.shownLink)

        vm.onEvent(RemindEvent.Send)
        assertEquals(RemindEffect.Send(link), vm.effectFlow.first())
        vm.onEvent(RemindEvent.Copy)
        assertEquals(RemindEffect.Copy(link), vm.effectFlow.first())
        // The link was made once.
        assertEquals(1, linkRequests().size)
    }

    @Test fun sendTappedWhileTheLinkIsBeingMadeSendsOnceItIsThere() = runTest {
        priyaOwes()
        linkAnswers(json(201, LinkResponse.serializer(), LinkResponse("l", "u")))
        val held = HeldLinks(env.fake)
        env.server.dispatcher = held
        try {
            val vm = viewModel()
            backgroundScope.launch { vm.state.collect {} }
            // The screen opened online, so the link is being made; the server has not answered yet.
            assertTrue(withContext(Dispatchers.IO) { held.asked.await(WAIT_SECONDS, TimeUnit.SECONDS) })

            vm.onEvent(RemindEvent.Send)
            held.release()

            val sent = withContext(Dispatchers.IO) { withTimeout(WAIT_SECONDS * 1_000) { vm.effectFlow.first() } }
            val link = vm.shown { it.link != null }.link!!
            assertEquals(RemindEffect.Send(link), sent)
            // Exactly one Send: the next message is the Copy tapped now, not a second Send.
            vm.onEvent(RemindEvent.Copy)
            assertEquals(RemindEffect.Copy(link), vm.effectFlow.first())
            assertEquals(1, linkRequests().size)
        } finally {
            held.release()
        }
    }

    @Test fun offlineShowsTheBannerAndMakesTheLinkWhenBackOnline() = runTest {
        priyaOwes()
        linkAnswers(json(201, LinkResponse.serializer(), LinkResponse("l", "u")))
        network.state.value = false
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }

        val offline = vm.shown()
        assertEquals(SettleProblem.Offline, offline.problem)
        assertNull(offline.link)
        vm.onEvent(RemindEvent.Send)
        assertEquals(SettleProblem.Offline, vm.shown().problem)
        assertTrue(linkRequests().isEmpty())

        network.state.value = true

        val back = vm.shown { it.link != null }
        assertNull(back.problem)
    }

    @Test fun aRefusalShowsCouldNotDoThatNow() = runTest {
        priyaOwes()
        linkAnswers(error(422, "invalid"))
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }

        val shown = vm.shown { it.problem != null }

        assertEquals(SettleProblem.Failed, shown.problem)
        assertNull(shown.link)
    }

    @Test fun signedOutOpensSignIn() = runTest {
        priyaOwes()
        env.session.token = null
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(RemindEffect.SignIn, vm.effectFlow.first())
    }

    @Test fun aFriendWhoOwesNothingLeaves() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_RAHUL, 240_000, listOf(M_ME, M_RAHUL))
        val vm = RemindViewModel(G1, M_RAHUL, env.groups, network, myName = { "Arjun" })
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(RemindUiState.Gone, vm.state.first { it != RemindUiState.Loading })
        assertTrue(linkRequests().isEmpty())
    }

    private companion object {
        /** Ids the repository may give a new link in these tests (a few calls to newId come before it). */
        const val LINK_IDS = 6

        /** How long, in real time, a test waits for the server or the view model. */
        const val WAIT_SECONDS = 10L
    }
}

/** Holds every link request at the server until [release], then answers it as [fake] does; others pass straight. */
private class HeldLinks(private val fake: FakeServer) : Dispatcher() {
    /** Counted down when a link request reached the server. */
    val asked = CountDownLatch(1)
    private val gate = CountDownLatch(1)

    /** Lets the held link requests through. */
    fun release() = gate.countDown()

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.requestUrl!!.encodedPath.contains("/links/")) {
            asked.countDown()
            gate.await(HELD_SECONDS, TimeUnit.SECONDS)
        }
        return fake.dispatch(request)
    }

    private companion object {
        const val HELD_SECONDS = 20L
    }
}
