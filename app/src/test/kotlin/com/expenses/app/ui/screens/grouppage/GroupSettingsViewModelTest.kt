package com.expenses.app.ui.screens.grouppage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.expenses.app.data.category.Category
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.RAHUL_USER
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.LinkEntity
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.error
import com.expenses.splits.SplitMethod
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Group settings' ViewModel (docs/groups-build-plan.md 4.2 tasks 7 to 9): the owner's tag (also before the owner's
 * member is stored), the not-owner Delete row, Leave and Delete tappable only at 0 and asking first, "link sent" and
 * the friend's link sheet, the rename, and the online-only handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GroupSettingsViewModelTest {
    private val env = GroupsTestEnv()
    private val dao = env.db.groupDao()
    private val mainThread = Executors.newSingleThreadExecutor()
    private val main = mainThread.asCoroutineDispatcher()
    private val writes = CoroutineScope(SupervisorJob() + main)
    private val online = MutableStateFlow(true)

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() {
        runBlocking(main) { store.clear() }
        writes.cancel()
        env.close()
        drainMain()
        Dispatchers.resetMain()
        main.close()
    }

    /** The ViewModels a test made: cleared after it, so nothing of theirs still runs when the main thread goes. */
    private val store = ViewModelStore()
    private var made = 0

    private inline fun <reified T : ViewModel> kept(crossinline make: () -> T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
        }
        return ViewModelProvider(store, factory)["vm${made++}", T::class.java]
    }

    /** Waits until the main thread has run everything handed to it. */
    private fun drainMain() = repeat(DRAIN_ROUNDS) {
        mainThread.submit {}.get()
        Thread.sleep(DRAIN_PAUSE_MS)
    }

    private fun viewModel() = kept { GroupSettingsViewModel(G1, env.groups, online, env.scheduler, writes) }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(TIMEOUT_MS) { block() } }

    private suspend fun GroupSettingsViewModel.shown(predicate: (GroupSettingsUiState.Shown) -> Boolean = { true }): GroupSettingsUiState.Shown =
        state.first { it is GroupSettingsUiState.Shown && predicate(it) } as GroupSettingsUiState.Shown

    /** Rahul paid ₹200 for the person and himself: the person owes Rahul ₹100. */
    private suspend fun personOwesRahul() {
        env.groups.addGroupExpense(
            G1,
            GroupExpenseDraft("Snacks", Category.FOOD, 20_000, LocalDate.of(2026, 11, 12), M_RAHUL, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null)),
        )
    }

    private suspend fun linkForPriya() = env.db.groupExtrasDao().upsertLink(
        LinkEntity(M_PRIYA, G1, "e0000000-0000-4000-8000-000000000099", "abcdefghijklmnopqrstuvwx", env.clock.instant(), env.clock.instant().plus(Duration.ofDays(90))),
    )

    private fun noContent() = MockResponse().setResponseCode(204)

    @Test fun theOwnersRowCarriesTheTagBeforeTheOwnersMemberIsStored() = test {
        // seedGroup stores no ownerMemberId: the owner is the member who is ownerUserId (the person).
        env.seedGroup()
        assertNull(dao.getGroup(G1)!!.ownerMemberId)

        val shown = viewModel().shown()

        // The person first, then the others by name.
        assertEquals(listOf(M_ME to true, M_PRIYA to false, M_RAHUL to false), shown.people.map { it.memberId to it.isOwner })
        assertEquals(listOf(PersonKind.You, PersonKind.Friend, PersonKind.OnApp), shown.people.map { it.kind })
        assertEquals(DeleteRow.Owner(canDelete = true), shown.delete)
    }

    @Test fun someoneElseOwnsTheGroup() = test {
        env.seedGroup()
        dao.upsertGroup(dao.getGroup(G1)!!.copy(ownerUserId = RAHUL_USER, ownerMemberId = M_RAHUL))

        val shown = viewModel().shown()

        assertEquals(listOf(M_RAHUL), shown.people.filter { it.isOwner }.map { it.memberId })
        assertEquals(DeleteRow.NotOwner(ownerName = "Rahul"), shown.delete)
        // 45: the owner right after the person, although Priya comes before Rahul by name.
        assertEquals(listOf(M_ME, M_RAHUL, M_PRIYA), shown.people.map { it.memberId })
    }

    @Test fun theOwnerComesRightAfterThePersonThenTheOthersAsBefore() = test {
        val kiran = "c0000000-0000-4000-8000-000000000004"
        val dev = "c0000000-0000-4000-8000-000000000005"
        val kiranUser = "a0000000-0000-4000-8000-000000000004"
        env.seedGroup(
            others = listOf(
                MemberEntity(dev, G1, null, "Dev", null, null, onApp = false, removed = false, version = 1),
                MemberEntity(kiran, G1, kiranUser, "Kiran", null, null, onApp = true, removed = false, version = 1),
                MemberEntity(M_PRIYA, G1, null, "Meera", null, null, onApp = false, removed = false, version = 1),
                MemberEntity(M_RAHUL, G1, RAHUL_USER, "Rahul", null, null, onApp = true, removed = false, version = 1),
            ),
        )
        dao.upsertGroup(dao.getGroup(G1)!!.copy(ownerUserId = kiranUser, ownerMemberId = kiran))

        val shown = viewModel().shown()

        // You, then Kiran the owner, then Dev, Meera and Rahul by name as before.
        assertEquals(listOf(M_ME, kiran, dev, M_PRIYA, M_RAHUL), shown.people.map { it.memberId })
        assertEquals(listOf(kiran), shown.people.filter { it.isOwner }.map { it.memberId })
    }

    @Test fun leaveAndDeleteAreTappableOnlyAtZero() = test {
        env.seedGroup()
        personOwesRahul()
        val vm = viewModel()
        val owing = vm.shown()
        assertFalse(owing.canLeave)
        assertEquals(DeleteRow.Owner(canDelete = false), owing.delete)

        vm.onEvent(GroupSettingsEvent.Leave)
        vm.onEvent(GroupSettingsEvent.Delete)
        delay(SETTLE_MS)

        assertNull(vm.shown().confirm)
    }

    @Test fun theOwnerMayDeleteWhileNobodyElseHasJoined() = test {
        // 143: Priya and Kiran are friends without the app, and a balance is open.
        val kiran = "c0000000-0000-4000-8000-000000000004"
        env.seedGroup(
            others = listOf(
                MemberEntity(M_PRIYA, G1, null, "Priya Nair", null, null, onApp = false, removed = false, version = 1),
                MemberEntity(kiran, G1, null, "Kiran Rao", null, null, onApp = false, removed = false, version = 1),
            ),
        )
        env.groups.addGroupExpense(
            G1,
            GroupExpenseDraft("Rent", Category.RENT_AND_BILLS, 30_000, LocalDate.of(2026, 11, 12), M_ME, SplitMethod.EQUAL, mapOf(M_ME to null, M_PRIYA to null, kiran to null)),
        )
        val vm = viewModel()
        assertEquals(DeleteRow.Owner(canDelete = true, nobodyElseJoined = true), vm.shown().delete)

        vm.onEvent(GroupSettingsEvent.Delete)
        vm.shown { it.confirm == SettingsConfirm.Delete }
    }

    @Test fun settledAndAloneKeepsTheUsualSubtitle() = test {
        env.seedGroup(others = listOf(MemberEntity(M_PRIYA, G1, null, "Priya Nair", null, null, onApp = false, removed = false, version = 1)))
        assertEquals(DeleteRow.Owner(canDelete = true, nobodyElseJoined = false), viewModel().shown().delete)
    }

    @Test fun leaveAsksFirstThenLeaves() = test {
        env.seedGroup()
        env.fake.on("POST", "/v1/groups/$G1/leave", noContent())
        val vm = viewModel()
        assertTrue(vm.shown().canLeave)

        vm.onEvent(GroupSettingsEvent.Leave)
        vm.shown { it.confirm == SettingsConfirm.Leave }
        assertTrue(env.fake.requests.isEmpty())
        vm.onEvent(GroupSettingsEvent.Confirm)

        vm.state.first { it == GroupSettingsUiState.Gone }
        assertEquals(1, env.fake.requestsTo("POST", "/v1/groups/$G1/leave").size)
    }

    @Test fun deleteAsksFirstThenDeletes() = test {
        env.seedGroup()
        env.fake.on("DELETE", "/v1/groups/$G1", noContent())
        val vm = viewModel()
        vm.shown()

        vm.onEvent(GroupSettingsEvent.Delete)
        vm.shown { it.confirm == SettingsConfirm.Delete }
        vm.onEvent(GroupSettingsEvent.Confirm)

        vm.state.first { it == GroupSettingsUiState.Gone }
    }

    @Test fun stayClosesTheConfirm() = test {
        env.seedGroup()
        val vm = viewModel()
        vm.shown()

        vm.onEvent(GroupSettingsEvent.Leave)
        vm.shown { it.confirm == SettingsConfirm.Leave }
        vm.onEvent(GroupSettingsEvent.CloseConfirm)

        vm.shown { it.confirm == null }
        assertTrue(env.fake.requests.isEmpty())
    }

    @Test fun aBalanceThatChangedMeanwhileKeepsTheScreen() = test {
        env.seedGroup()
        env.fake.on("POST", "/v1/groups/$G1/leave", error(409, "balances_not_settled"))
        val vm = viewModel()
        vm.shown()
        val syncsBefore = env.scheduler.requests.get()

        vm.onEvent(GroupSettingsEvent.Leave)
        vm.shown { it.confirm != null }
        vm.onEvent(GroupSettingsEvent.Confirm)

        val shown = vm.shown { !it.busy && env.fake.requests.isNotEmpty() }
        assertNull(shown.notice)
        assertNull(shown.confirm)
        // The group is pulled again, so the rows re-read.
        assertTrue(env.scheduler.requests.get() > syncsBefore)
    }

    @Test fun leaveOfflineShowsTheOfflineBanner() = test {
        env.seedGroup()
        online.value = false
        val vm = viewModel()
        vm.shown()

        vm.onEvent(GroupSettingsEvent.Leave)
        vm.shown { it.confirm != null }
        vm.onEvent(GroupSettingsEvent.Confirm)

        val shown = vm.shown { it.notice == GroupNotice.Offline }
        assertNull(shown.confirm)
        assertTrue(env.fake.requests.isEmpty())
        // Back online, the banner has nothing more to say.
        online.value = true
        vm.shown { it.notice == null }
    }

    @Test fun aRefusalShowsTheFailureBanner() = test {
        env.seedGroup()
        env.fake.on("DELETE", "/v1/groups/$G1", error(403, "not_owner"))
        val vm = viewModel()
        vm.shown()

        vm.onEvent(GroupSettingsEvent.Delete)
        vm.shown { it.confirm != null }
        vm.onEvent(GroupSettingsEvent.Confirm)

        assertEquals(GroupNotice.Failed, vm.shown { it.notice != null }.notice)
    }

    @Test fun aFriendWithALinkReadsLinkSentAndOpensTheSheet() = test {
        env.seedGroup()
        linkForPriya()
        env.fake.on("DELETE", "/v1/groups/$G1/members/$M_PRIYA/links", noContent())
        val vm = viewModel()
        assertEquals(PersonKind.FriendLinkSent, vm.shown().people.first { it.memberId == M_PRIYA }.kind)

        vm.onEvent(GroupSettingsEvent.PersonTapped(M_PRIYA))
        assertEquals(FriendSheet(M_PRIYA, "Priya"), vm.shown { it.sheet != null }.sheet)
        vm.onEvent(GroupSettingsEvent.TurnOffLink)

        // The sheet closes and the row no longer says "link sent".
        val shown = vm.shown { it.sheet == null && !it.busy && it.people.first { p -> p.memberId == M_PRIYA }.kind == PersonKind.Friend }
        assertNull(shown.notice)
        assertNull(env.db.groupExtrasDao().linkFor(M_PRIYA))
        assertEquals(1, env.fake.requestsTo("DELETE", "/v1/groups/$G1/members/$M_PRIYA/links").size)
    }

    @Test fun anAppUserOpensNoSheet() = test {
        env.seedGroup()
        val vm = viewModel()
        vm.shown()

        vm.onEvent(GroupSettingsEvent.PersonTapped(M_RAHUL))
        vm.onEvent(GroupSettingsEvent.PersonTapped(M_ME))
        delay(SETTLE_MS)

        assertNull(vm.shown().sheet)
    }

    @Test fun aFriendWithoutALinkFromThisPhoneOpensTheSheetAndCanStillTurnOffOthersLinks() = test {
        env.seedGroup()
        env.fake.on("DELETE", "/v1/groups/$G1/members/$M_PRIYA/links", noContent())
        val vm = viewModel()
        assertEquals(PersonKind.Friend, vm.shown().people.first { it.memberId == M_PRIYA }.kind)

        vm.onEvent(GroupSettingsEvent.PersonTapped(M_PRIYA))

        // Priya owes the person nothing yet: Remind has nothing to send.
        assertEquals(FriendSheet(M_PRIYA, "Priya", linkOn = false, canRemind = false), vm.shown { it.sheet != null }.sheet)
        // Another member may have sent Priya a link: Turn off link stops every link of hers, and the sheet closes.
        vm.onEvent(GroupSettingsEvent.TurnOffLink)
        val shown = vm.shown { it.sheet == null && !it.busy }
        assertNull(shown.notice)
        assertEquals(1, env.fake.requestsTo("DELETE", "/v1/groups/$G1/members/$M_PRIYA/links").size)
    }

    @Test fun aFriendWithoutALinkWhoOwesCanBeRemindedAndTheSheetCloses() = test {
        env.seedGroup()
        // The person paid ₹200 for themselves and Priya: Priya owes ₹100.
        env.groups.addGroupExpense(
            G1,
            GroupExpenseDraft("Snacks", Category.FOOD, 20_000, LocalDate.of(2026, 11, 12), M_ME, SplitMethod.EQUAL, mapOf(M_ME to null, M_PRIYA to null)),
        )
        val vm = viewModel()
        vm.shown()

        vm.onEvent(GroupSettingsEvent.PersonTapped(M_PRIYA))
        assertEquals(FriendSheet(M_PRIYA, "Priya", linkOn = false, canRemind = true), vm.shown { it.sheet?.canRemind == true }.sheet)
        // The caller opens Remind on WhatsApp, which makes the link; the sheet is closed when the person comes back.
        vm.onEvent(GroupSettingsEvent.Remind(M_PRIYA))

        vm.shown { it.sheet == null }
    }

    @Test fun cancelClosesTheSheet() = test {
        env.seedGroup()
        linkForPriya()
        val vm = viewModel()
        vm.shown()

        vm.onEvent(GroupSettingsEvent.PersonTapped(M_PRIYA))
        vm.shown { it.sheet != null }
        vm.onEvent(GroupSettingsEvent.CloseSheet)

        vm.shown { it.sheet == null }
        assertTrue(env.fake.requests.isEmpty())
    }

    @Test fun aChangedNameIsSavedWhenTheFieldIsLeft() = test {
        env.seedGroup()
        val vm = viewModel()
        vm.shown()

        vm.onEvent(GroupSettingsEvent.NameChanged("  Flat 303 "))
        vm.onEvent(GroupSettingsEvent.NameDone)

        waitFor { dao.getGroup(G1)!!.name == "Flat 303" }
        assertEquals("Flat 303", vm.shown { it.groupName == "Flat 303" }.nameField.trim())
    }

    @Test fun aBlankNameIsNotSaved() = test {
        env.seedGroup()
        val vm = viewModel()
        vm.shown()

        vm.onEvent(GroupSettingsEvent.NameChanged("   "))
        vm.onEvent(GroupSettingsEvent.NameDone)

        assertTrue(vm.shown { it.nameError }.nameError)
        assertEquals("Flat 302", dao.getGroup(G1)!!.name)
        assertTrue(env.outbox.entries().isEmpty())
    }

    @Test fun aDirectSplitHasNoNameAndNoDelete() = test {
        env.seedGroup(others = listOf(MemberEntity(M_RAHUL, G1, RAHUL_USER, "Rahul", null, null, onApp = true, removed = false, version = 1)))
        dao.upsertGroup(dao.getGroup(G1)!!.copy(kind = GroupKind.DIRECT, name = null))

        val shown = viewModel().shown()

        assertTrue(shown.isDirect)
        assertEquals("Rahul", shown.groupName)
        assertNull(shown.delete)
        assertTrue(shown.canLeave)
        assertTrue(shown.people.none { it.isOwner })
    }

    @Test fun theGroupGoingLeavesTheScreen() = test {
        env.seedGroup()
        val vm = viewModel()
        vm.shown()

        env.local.removeGroup(G1)

        vm.state.first { it == GroupSettingsUiState.Gone }
    }

    /** Waits until [condition] holds, checking every 20 ms. */
    private suspend fun waitFor(condition: suspend () -> Boolean) {
        while (!condition()) delay(20)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L

        /** Long enough for an event that would change the state to have done so. */
        const val SETTLE_MS = 200L
        const val DRAIN_ROUNDS = 3
        const val DRAIN_PAUSE_MS = 20L
    }
}
