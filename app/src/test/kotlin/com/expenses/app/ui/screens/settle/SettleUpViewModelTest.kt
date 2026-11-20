package com.expenses.app.ui.screens.settle

import android.app.Application
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.RAHUL_USER
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.groups.data.SettlementEntity
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.match.SettlementMatcher
import com.expenses.app.groups.match.groupExpense
import com.expenses.app.ui.components.AvatarColor
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Settle up (design 03-getting-paid-back/00, 07; docs/groups-build-plan.md 4.4 task 1). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettleUpViewModelTest {
    private val env = GroupsTestEnv()
    private val extras = env.db.groupExtrasDao()
    private val matcher = SettlementMatcher(RuntimeEnvironment.getApplication(), env.groups, env.expenses, extras, env.signedIn, env.clock)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private suspend fun SettleUpViewModel.shown(): SettleUpUiState.Shown = state.first { it is SettleUpUiState.Shown } as SettleUpUiState.Shown

    /** As the design: four ₹600 dinners Rahul paid, shared with the person, so they owe him ₹1,200. */
    private suspend fun oweRahulForFourDinners() {
        env.seedGroup()
        repeat(4) { env.groupExpense(G1, M_RAHUL, 60_000, listOf(M_ME, M_RAHUL), on = LocalDate.of(2026, 11, 10 + it)) }
    }

    @Test fun aFriendWithAPayableUpiIdCanBePaidByUpi() = runTest {
        oweRahulForFourDinners()
        val vm = SettleUpViewModel(G1, M_RAHUL, env.groups, matcher)
        backgroundScope.launch { vm.state.collect {} }

        val shown = vm.shown()

        assertEquals(
            SettleUpUiState.Shown(
                name = "Rahul",
                amountMinor = 120_000,
                expenseCount = 4,
                groupName = "Flat 302",
                upiId = "rahul@okhdfc",
                friendOnApp = true,
                choice = SettleChoice.Upi,
                // Rahul's colour, as on the Group page and Groups: the first friend stored is green.
                avatar = AvatarColor.Green,
            ),
            shown,
        )
        assertEquals("R", shown.initial)
    }

    @Test fun aFriendWithoutTheAppGetsOnlyPaidAnotherWay() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_PRIYA, 240_000, listOf(M_ME, M_PRIYA))
        val vm = SettleUpViewModel(G1, M_PRIYA, env.groups, matcher)
        backgroundScope.launch { vm.state.collect {} }

        val shown = vm.shown()

        assertNull(shown.upiId)
        assertFalse(shown.friendOnApp)
        assertEquals(SettleChoice.Other, shown.choice)
        assertEquals(120_000L, shown.amountMinor)
        assertEquals(1, shown.expenseCount)
        // Pay by UPI cannot be picked.
        vm.onEvent(SettleUpEvent.Choose(SettleChoice.Upi))
        assertEquals(SettleChoice.Other, vm.shown().choice)
    }

    @Test fun aUpiIdThatCannotBePaidShowsNoUpiId() = runTest {
        env.seedGroup(others = listOf(MemberEntity(M_RAHUL, G1, RAHUL_USER, "Rahul", null, "rahul at hdfc", onApp = true, removed = false, version = 1)))
        env.groupExpense(G1, M_RAHUL, 240_000, listOf(M_ME, M_RAHUL))
        val vm = SettleUpViewModel(G1, M_RAHUL, env.groups, matcher)
        backgroundScope.launch { vm.state.collect {} }

        val shown = vm.shown()

        assertNull(shown.upiId)
        assertTrue(shown.friendOnApp)
        assertEquals(SettleChoice.Other, shown.choice)
    }

    @Test fun theCountIsTheExpensesBetweenTheTwoSinceTheyLastSettled() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_RAHUL, 60_000, listOf(M_ME, M_RAHUL), on = LocalDate.of(2026, 11, 10))
        env.db.groupDao().upsertSettlement(
            SettlementEntity(
                "f0000000-0000-4000-8000-000000000001", G1, M_ME, M_RAHUL, 30_000, SettleMethod.CASH, matchedFromSms = false,
                occurredOn = LocalDate.of(2026, 11, 11), recordedByMemberId = M_ME, confirmedAt = null, deleted = false, version = 1,
                syncState = SyncState.SYNCED,
            ),
        )
        env.groupExpense(G1, M_RAHUL, 100_000, listOf(M_ME, M_RAHUL), on = LocalDate.of(2026, 11, 12))
        env.groupExpense(G1, M_ME, 40_000, listOf(M_ME, M_RAHUL), on = LocalDate.of(2026, 11, 13))
        // Between the person and Priya only: not counted for Rahul.
        env.groupExpense(G1, M_PRIYA, 60_000, listOf(M_ME, M_PRIYA), on = LocalDate.of(2026, 11, 13))
        val vm = SettleUpViewModel(G1, M_RAHUL, env.groups, matcher)
        backgroundScope.launch { vm.state.collect {} }

        val shown = vm.shown()

        assertEquals(2, shown.expenseCount)
        assertEquals(30_000L, shown.amountMinor)
    }

    @Test fun payByUpiRemembersThePaymentAndOpensTheUpiApp() = runTest {
        oweRahulForFourDinners()
        val vm = SettleUpViewModel(G1, M_RAHUL, env.groups, matcher)
        backgroundScope.launch { vm.state.collect {} }
        vm.shown()

        vm.onEvent(SettleUpEvent.Confirm)
        val effect = vm.effectFlow.first() as SettleUpEffect.OpenUpi

        assertEquals("upi://pay?pa=rahul@okhdfc&pn=Rahul&am=1200.00&cu=INR&tn=Flat%20302", effect.uri)
        val pending = extras.allPendingUpi().single()
        assertEquals(G1, pending.groupId)
        assertEquals(M_RAHUL, pending.toMemberId)
        assertEquals(120_000L, pending.amountMinor)
        assertEquals(env.clock.instant(), pending.startedAt)
        // Nothing is recorded until the bank's SMS arrives.
        assertTrue(env.groups.observeGroup(G1).first()!!.settlements.isEmpty())
    }

    @Test fun noUpiAppForgetsThePaymentAndOffersPaidAnotherWay() = runTest {
        oweRahulForFourDinners()
        val vm = SettleUpViewModel(G1, M_RAHUL, env.groups, matcher)
        backgroundScope.launch { vm.state.collect {} }
        vm.shown()
        vm.onEvent(SettleUpEvent.Confirm)
        vm.effectFlow.first()

        vm.onEvent(SettleUpEvent.UpiAppMissing)

        val shown = vm.state.first { it is SettleUpUiState.Shown && it.noUpiApp } as SettleUpUiState.Shown
        assertEquals(SettleChoice.Other, shown.choice)
        awaitTrue("the pending payment is forgotten") { extras.allPendingUpi().isEmpty() }
    }

    @Test fun paidAnotherWayRecordsTheSettlementAndLeaves() = runTest {
        oweRahulForFourDinners()
        val vm = SettleUpViewModel(G1, M_RAHUL, env.groups, matcher)
        backgroundScope.launch { vm.state.collect {} }
        vm.shown()

        vm.onEvent(SettleUpEvent.Choose(SettleChoice.Other))
        assertEquals(SettleChoice.Other, vm.shown().choice)
        vm.onEvent(SettleUpEvent.Confirm)

        assertEquals(SettleUpUiState.Done, vm.state.first { it == SettleUpUiState.Done })
        val settlement = env.groups.observeGroup(G1).first()!!.settlements.single()
        assertEquals(M_ME, settlement.fromMemberId)
        assertEquals(M_RAHUL, settlement.toMemberId)
        assertEquals(120_000L, settlement.amountMinor)
        assertEquals(SettleMethod.OTHER, settlement.method)
        assertFalse(settlement.matchedFromSms)
        // Rahul is on the app: he can confirm it.
        assertNull(settlement.confirmedAt)
        assertEquals(EntityKind.SETTLEMENT, env.outbox.entries().last().entityKind)
    }

    @Test fun nothingOwedLeaves() = runTest {
        env.seedGroup()
        // Rahul owes the person: Settle up has nothing to pay him.
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val vm = SettleUpViewModel(G1, M_RAHUL, env.groups, matcher)
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(SettleUpUiState.Gone, vm.state.first { it != SettleUpUiState.Loading })
    }
}
