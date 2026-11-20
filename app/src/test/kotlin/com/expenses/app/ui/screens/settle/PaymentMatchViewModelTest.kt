package com.expenses.app.ui.screens.settle

import android.app.Application
import com.expenses.app.groups.G1
import com.expenses.app.groups.G2
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_ME2
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.M_RAHUL2
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.groups.match.groupExpense
import com.expenses.app.groups.match.seedGoaWithRahul
import com.expenses.app.groups.match.smsCredit
import com.expenses.app.groups.match.smsDebit
import com.expenses.app.groups.match.untrackedAccount
import com.expenses.app.ui.components.AvatarColor
import java.time.Duration
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Money received (design 03-getting-paid-back/02; docs/groups-build-plan.md 4.4 task 4). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PaymentMatchViewModelTest {
    private val env = GroupsTestEnv()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun viewModel(creditId: Long) = PaymentMatchViewModel(creditId, env.groups, env.expenses, env.clock)

    private suspend fun PaymentMatchViewModel.settled(): PaymentMatchUiState = state.first { it != PaymentMatchUiState.Loading }

    @Test fun anUnclearCreditAsksAboutTheFriendWhoOwesThatAmount() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val credit = env.smsCredit(120_000, "IMPS 1234", at = env.clock.instant().minus(Duration.ofMinutes(5)))
        val vm = viewModel(credit)
        backgroundScope.launch { vm.state.collect {} }

        val shown = vm.settled() as PaymentMatchUiState.Shown

        assertEquals(120_000L, shown.amountMinor)
        assertEquals(5L, shown.minutesAgo)
        assertEquals("Rahul", shown.friendName)
        assertEquals("R", shown.friendInitial)
        assertEquals(OwedFor.Group("Flat 302"), shown.owedFor)
        assertEquals(120_000L, shown.owedMinor)
        // Rahul's colour, as on the Group page and Groups: the first friend stored is green.
        assertEquals(AvatarColor.Green, shown.friendAvatar)
    }

    @Test fun yesClearsItAndOpensAllSquare() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val credit = env.smsCredit(120_000, "IMPS 1234")
        val vm = viewModel(credit)
        backgroundScope.launch { vm.state.collect {} }
        vm.settled()

        vm.onEvent(PaymentMatchEvent.Yes)

        assertEquals(PaymentMatchUiState.Cleared(G1, M_RAHUL, 120_000), vm.state.first { it is PaymentMatchUiState.Cleared })
        val settlement = env.groups.observeGroup(G1).first()!!.settlements.single()
        assertEquals(M_RAHUL, settlement.fromMemberId)
        assertEquals(M_ME, settlement.toMemberId)
        assertEquals(SettleMethod.UPI, settlement.method)
        assertTrue(settlement.matchedFromSms)
        assertNotNull(settlement.confirmedAt)
    }

    @Test fun aFriendOwingInTwoGroupsIsAskedAboutBoth() = runTest {
        env.seedGroup()
        env.seedGoaWithRahul()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        env.groupExpense(G2, M_ME2, 160_000, listOf(M_ME2, M_RAHUL2))
        val credit = env.smsCredit(200_000, null)
        val vm = viewModel(credit)
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(OwedFor.Several(2), (vm.settled() as PaymentMatchUiState.Shown).owedFor)

        vm.onEvent(PaymentMatchEvent.Yes)

        // One screen, naming the group with the largest transfer.
        assertEquals(PaymentMatchUiState.Cleared(G1, M_RAHUL, 200_000), vm.state.first { it is PaymentMatchUiState.Cleared })
        assertEquals(1, env.groups.observeGroup(G1).first()!!.settlements.size)
        assertEquals(1, env.groups.observeGroup(G2).first()!!.settlements.size)
    }

    @Test fun moneyThatMatchesNobodyIsGone() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val credit = env.smsCredit(99_900, "Rahul")
        val vm = viewModel(credit)
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(PaymentMatchUiState.Gone, vm.settled())
    }

    @Test fun moneyIntoAnAccountSwitchedOffIsGoneAndCannotBeCleared() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val credit = env.smsCredit(120_000, "IMPS 1234", accountId = env.untrackedAccount())
        val vm = viewModel(credit)
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(PaymentMatchUiState.Gone, vm.settled())
        vm.onEvent(PaymentMatchEvent.Yes)

        assertTrue(env.groups.observeGroup(G1).first()!!.settlements.isEmpty())
    }

    @Test fun aDebitIsNeverMoneyReceived() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val debit = env.smsDebit(120_000, "rahul@okhdfc", env.clock.instant())
        val vm = viewModel(debit)
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(PaymentMatchUiState.Gone, vm.settled())
    }
}
