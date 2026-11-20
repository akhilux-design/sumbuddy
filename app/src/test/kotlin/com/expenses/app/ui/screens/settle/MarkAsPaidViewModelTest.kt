package com.expenses.app.ui.screens.settle

import android.app.Application
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.groups.match.groupExpense
import com.expenses.app.ui.components.AvatarColor
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mark as paid (design 03-getting-paid-back/01; docs/groups-build-plan.md 4.4 task 2). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MarkAsPaidViewModelTest {
    private val env = GroupsTestEnv()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private suspend fun MarkAsPaidViewModel.shown(): MarkAsPaidUiState.Shown = state.first { it is MarkAsPaidUiState.Shown } as MarkAsPaidUiState.Shown

    @Test fun aFriendOnTheAppPaysInCashAndCanConfirm() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val vm = MarkAsPaidViewModel(G1, M_RAHUL, env.groups)
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(
            MarkAsPaidUiState.Shown(
                name = "Rahul",
                amountMinor = 120_000,
                groupName = "Flat 302",
                canConfirm = true,
                how = PaidHow.Cash,
                avatar = AvatarColor.Green,
            ),
            vm.shown(),
        )

        vm.onEvent(MarkAsPaidEvent.Confirm)

        vm.state.first { it == MarkAsPaidUiState.Done }
        val settlement = env.groups.observeGroup(G1).first()!!.settlements.single()
        assertEquals(M_RAHUL, settlement.fromMemberId)
        assertEquals(M_ME, settlement.toMemberId)
        assertEquals(120_000L, settlement.amountMinor)
        assertEquals(SettleMethod.CASH, settlement.method)
        assertFalse(settlement.matchedFromSms)
        // Rahul gets a note and can confirm.
        assertNull(settlement.confirmedAt)
    }

    @Test fun aFriendWithoutTheAppPaidByBankTransfer() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_PRIYA))
        val vm = MarkAsPaidViewModel(G1, M_PRIYA, env.groups)
        backgroundScope.launch { vm.state.collect {} }
        assertFalse(vm.shown().canConfirm)

        vm.onEvent(MarkAsPaidEvent.Choose(PaidHow.Other))
        assertEquals(PaidHow.Other, vm.shown().how)
        vm.onEvent(MarkAsPaidEvent.Confirm)

        vm.state.first { it == MarkAsPaidUiState.Done }
        val settlement = env.groups.observeGroup(G1).first()!!.settlements.single()
        assertEquals(M_PRIYA, settlement.fromMemberId)
        assertEquals(SettleMethod.OTHER, settlement.method)
        // Nobody on the other side can confirm: it is confirmed at once.
        assertNotNull(settlement.confirmedAt)
    }

    @Test fun nothingOwedLeaves() = runTest {
        env.seedGroup()
        // The person owes Rahul: there is nothing for Rahul to have paid.
        env.groupExpense(G1, M_RAHUL, 240_000, listOf(M_ME, M_RAHUL))
        val vm = MarkAsPaidViewModel(G1, M_RAHUL, env.groups)
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(MarkAsPaidUiState.Gone, vm.state.first { it != MarkAsPaidUiState.Loading })
    }
}
