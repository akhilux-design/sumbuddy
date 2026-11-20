package com.expenses.app.ui.screens.settle

import android.app.Application
import com.expenses.app.data.model.PaidWith
import com.expenses.app.groups.G1
import com.expenses.app.groups.G2
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_ME2
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.M_RAHUL2
import com.expenses.app.groups.match.SettlementMatcher
import com.expenses.app.groups.match.groupExpense
import com.expenses.app.groups.match.seedGoaWithRahul
import com.expenses.app.groups.match.smsCredit
import com.expenses.app.ingest.IngestResult
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** All square (design 03-getting-paid-back/03; docs/groups-build-plan.md 4.4 task 5). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BalanceClearedViewModelTest {
    private val env = GroupsTestEnv()
    private val matcher = SettlementMatcher(RuntimeEnvironment.getApplication(), env.groups, env.expenses, env.db.groupExtrasDao(), env.signedIn, env.clock)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private suspend fun BalanceClearedViewModel.shown(): BalanceClearedUiState.Shown =
        state.first { it is BalanceClearedUiState.Shown } as BalanceClearedUiState.Shown

    /** Rahul owes the person ₹1,200 in Flat 302 and ₹800 in Goa trip. */
    private suspend fun rahulOwesInTwoGroups() {
        env.seedGroup()
        env.seedGoaWithRahul()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        env.groupExpense(G2, M_ME2, 160_000, listOf(M_ME2, M_RAHUL2))
    }

    private suspend fun receive(amountMinor: Long, from: String, paidWith: PaidWith = PaidWith.UPI) {
        val credit = env.smsCredit(amountMinor, from, paidWith = paidWith)
        matcher.onNewPayments(IngestResult(credits = listOf(env.expenses.get(credit)!!)))
    }

    @Test fun aFriendSettledInTwoGroupsIsAllSquareOverEveryGroup() = runTest {
        rahulOwesInTwoGroups()
        receive(200_000, "RAHUL KUMAR")
        // The notification's link names the group with the largest transfer: Flat 302.
        val vm = BalanceClearedViewModel(G1, M_RAHUL, 200_000, env.groups)
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(
            BalanceClearedUiState.Shown(name = "Rahul", amountMinor = 200_000, byUpi = true, friendNetMinor = 0, avatar = AvatarColor.Green),
            vm.shown(),
        )
    }

    @Test fun theBalanceRowShowsWhatTheFriendStillOwesElsewhere() = runTest {
        rahulOwesInTwoGroups()
        receive(120_000, "Rahul")
        val vm = BalanceClearedViewModel(G1, M_RAHUL, 120_000, env.groups)
        backgroundScope.launch { vm.state.collect {} }

        val shown = vm.shown()

        assertEquals(80_000L, shown.friendNetMinor)
        assertEquals(120_000L, shown.amountMinor)
    }

    @Test fun moneyThatCameByBankTransferIsNotCalledUpi() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_PRIYA))
        receive(120_000, "PRIYA SHARMA", PaidWith.BANK_TRANSFER)
        val vm = BalanceClearedViewModel(G1, M_PRIYA, 120_000, env.groups)
        backgroundScope.launch { vm.state.collect {} }

        val shown = vm.shown()

        assertEquals("Priya", shown.name)
        assertEquals(false, shown.byUpi)
        assertEquals(0L, shown.friendNetMinor)
    }

    @Test fun aGroupThatIsGoneGoesHome() = runTest {
        val vm = BalanceClearedViewModel(G1, M_RAHUL, 120_000, env.groups)
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(BalanceClearedUiState.Gone, vm.state.first { it != BalanceClearedUiState.Loading })
    }
}
