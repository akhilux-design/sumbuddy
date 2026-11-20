package com.expenses.app.ui.screens.splitwise

import android.app.Application
import com.expenses.app.R
import com.expenses.app.groups.FakeServer
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.api.CreateGroupResponse
import com.expenses.app.groups.api.GroupDto
import com.expenses.app.groups.api.ImportResponse
import com.expenses.app.groups.api.LinkResponse
import com.expenses.app.groups.error
import com.expenses.app.groups.json
import com.expenses.splitwise.SplitwiseFile
import com.expenses.splitwise.SwExpense
import com.expenses.splitwise.SwPart
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Group imported (design 02-everyday/31, 141; docs/splitwise-import.md 5.8). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GroupImportedViewModelTest {
    private val env = GroupsTestEnv()
    private val online = MutableStateFlow(true)
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val links = LinkAnswers(env.fake)
    private val res = RuntimeEnvironment.getApplication().resources

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env.server.dispatcher = links
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    /** Flat 302 imported and fully sent: Arjun (the person), Priya Nair and Kiran Rao, two past expenses. */
    private suspend fun imported(sendParts: Boolean = true): String {
        val file = SplitwiseFile(
            people = listOf("Arjun Mehta", "Priya Nair", "Kiran Rao"),
            expenses = listOf(
                SwExpense(2, LocalDate.of(2026, 11, 1), "Rent", "Rent", listOf(SwPart(1, 300_000, mapOf(0 to 100_000L, 1 to 100_000L, 2 to 100_000L)))),
                SwExpense(3, LocalDate.of(2026, 11, 2), "Milk", "Groceries", listOf(SwPart(2, 3_000, mapOf(0 to 1_000L, 1 to 1_000L, 2 to 1_000L)))),
            ),
            payments = emptyList(),
            balancesPaise = listOf(-101_000, 199_000, -98_000),
            leftOutByCurrency = emptyMap(),
            noEffectRows = 0,
        )
        val groupId = env.groups.importSplitwise(file, 0, "Flat 302", "ab".repeat(32))
        if (sendParts) {
            env.fake.on(
                "POST", "/v1/groups",
                json(201, CreateGroupResponse.serializer(), CreateGroupResponse(GroupDto(groupId, "group", "Flat 302", ME_USER, 1, 0), emptyList())),
            )
            env.fake.on("POST", "/v1/groups/$groupId/import", json(200, ImportResponse.serializer(), ImportResponse(true, 2, 3)))
            env.engine.push()
        }
        return groupId
    }

    private fun viewModel(groupId: String) = GroupImportedViewModel(
        groupId, env.groups, online,
        message = { name, lines -> ImportInviteMessage.text(res, name, lines) },
        sleep = { sleeps += it },
    )

    private suspend fun GroupImportedViewModel.shown(predicate: (GroupImportedUiState.Shown) -> Boolean = { true }) =
        state.first { it is GroupImportedUiState.Shown && predicate(it) } as GroupImportedUiState.Shown

    @Test fun showsTheGroupItsExpensesAndItsFriends() = runTest {
        val groupId = imported()
        val vm = viewModel(groupId)
        backgroundScope.launch { vm.state.collect {} }
        val shown = vm.shown()
        assertEquals("Flat 302", shown.groupName)
        assertEquals(2, shown.expenseCount)
        assertEquals(listOf("Priya", "Kiran"), shown.friendNames)
        assertEquals(ImportBanner.None, shown.banner)
    }

    @Test fun shareMakesOneLinkPerFriendInOrderAndOneMessage() = runTest {
        val groupId = imported()
        val members = env.db.groupDao().membersOf(groupId).associateBy { it.name }
        val vm = viewModel(groupId)
        backgroundScope.launch { vm.state.collect {} }
        vm.shown()
        vm.onEvent(GroupImportedEvent.Share)

        val effect = vm.effect.first() as GroupImportedEffect.Share
        assertEquals(groupId, effect.groupId)
        val made = links.paths.map { it.substringAfter("/members/").substringBefore("/links/") }
        assertEquals(listOf(members.getValue("Priya Nair").id, members.getValue("Kiran Rao").id), made)
        val lines = effect.text.lines()
        val app = res.getString(R.string.app_name)
        assertEquals("Flat 302 is on $app now, with our balances from Splitwise.", lines[0])
        assertEquals("Open the link with your name to see what you owe and pay. No app needed.", lines[1])
        assertEquals(4, lines.size)
        assertTrue(lines[2], Regex("^Priya Nair: https://links\\.test/pay/[A-Za-z0-9_-]{24}$").matches(lines[2]))
        assertTrue(lines[3], Regex("^Kiran Rao: https://links\\.test/pay/[A-Za-z0-9_-]{24}$").matches(lines[3]))
    }

    @Test fun aRateLimitedLinkWaitsOnceThenGoesOn() = runTest {
        val groupId = imported()
        links.next += error(429, "rate_limited").setHeader("Retry-After", "5")
        val vm = viewModel(groupId)
        backgroundScope.launch { vm.state.collect {} }
        vm.shown()
        vm.onEvent(GroupImportedEvent.Share)
        assertTrue(vm.effect.first() is GroupImportedEffect.Share)
        assertEquals(listOf(5_000L), sleeps.toList())
        assertEquals(3, links.paths.size)
    }

    @Test fun offlineShowsTheBannerAndMakesNoLink() = runTest {
        val groupId = imported()
        online.value = false
        val vm = viewModel(groupId)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(ImportBanner.Offline, vm.shown().banner)
        vm.onEvent(GroupImportedEvent.Share)
        assertTrue(links.paths.isEmpty())
        online.value = true
        assertEquals(ImportBanner.None, vm.shown { it.banner == ImportBanner.None }.banner)
    }

    @Test fun anyOtherErrorShowsTheProblemBanner() = runTest {
        val groupId = imported()
        links.next += error(500, "internal")
        val vm = viewModel(groupId)
        backgroundScope.launch { vm.state.collect {} }
        vm.shown()
        vm.onEvent(GroupImportedEvent.Share)
        assertEquals(ImportBanner.Problem, vm.shown { it.banner == ImportBanner.Problem && !it.sharing }.banner)
    }

    @Test fun whilePartsWaitNoLinkIsMadeAndTheProblemBannerShows() = runTest {
        val groupId = imported(sendParts = false)
        val vm = viewModel(groupId)
        backgroundScope.launch { vm.state.collect {} }
        vm.shown()
        vm.onEvent(GroupImportedEvent.Share)
        assertEquals(ImportBanner.Problem, vm.shown { it.banner == ImportBanner.Problem }.banner)
        assertTrue(links.paths.isEmpty())
    }

    @Test fun laterOpensTheGroup() = runTest {
        val groupId = imported()
        val vm = viewModel(groupId)
        vm.onEvent(GroupImportedEvent.Later)
        assertEquals(GroupImportedEffect.OpenGroup(groupId), vm.effect.first())
    }
}

/**
 * Answers link requests (`PUT …/links/…`) with the queued [next] answers first, then 201; everything else as [fake].
 * Keeps the paths of the link requests.
 */
private class LinkAnswers(private val fake: FakeServer) : Dispatcher() {
    val next = ConcurrentLinkedQueue<MockResponse>()
    val paths = CopyOnWriteArrayList<String>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        if (request.method != "PUT" || !path.contains("/links/")) return fake.dispatch(request)
        paths += path
        return next.poll() ?: json(201, LinkResponse.serializer(), LinkResponse(path.substringAfterLast('/'), "https://links.test/pay/x"))
    }
}
