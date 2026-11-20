package com.expenses.app.ui.screens.activity

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.repository.ReviewRepository
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.splits.SplitMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Activity with splits: "From splits", the Splits filter and split rows. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ActivityViewModelTest {
    private val env = GroupsTestEnv()
    private var swiggy = 0L
    private var blinkit = 0L

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    /** ₹450 at Swiggy split three ways in Flat 302 (the person's share ₹150), and ₹200 at Blinkit, their own. */
    private suspend fun TestScope.viewModel(): ActivityViewModel {
        env.seedGroup(G1)
        swiggy = env.smsPayment(45_000, "Swiggy")
        env.groups.splitPayment(swiggy, G1, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = false, auto = false)
        blinkit = env.smsPayment(20_000, "Blinkit", status = ExpenseStatus.ADDED)
        val vm = ActivityViewModel(env.expenses, ReviewRepository(env.db.reviewDao()), env.clock, env.groups.observeSplitLabels())
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    private fun ActivityUiState.rows(): List<ActivityItem> = (content as ActivityContent.Days).days.flatMap { it.items }

    @Test fun fromSplitsIsTheShareOfTheMonthsSplitPayments() = runTest {
        val vm = viewModel()
        val state = vm.state.first { it != null && it.fromSplitsPaise > 0 }!!
        assertEquals(35_000L, state.spentPaise)
        assertEquals(15_000L, state.fromSplitsPaise)
    }

    @Test fun aSplitRowReadsAsShared() = runTest {
        val vm = viewModel()
        val rows = vm.state.first { state -> state?.rows()?.any { it.detail is ActivityItemDetail.Split } == true }!!.rows()
        val row = rows.single { it.id == ActivityItemId.Expense(swiggy) }
        assertEquals(ActivityItemDetail.Split(Category.FOOD, 45_000, "Flat 302"), row.detail)
        assertEquals(15_000L, row.amountPaise)
        assertTrue(rows.single { it.id == ActivityItemId.Expense(blinkit) }.detail is ActivityItemDetail.Personal)
    }

    @Test fun theSplitsFilterListsOnlySplitRows() = runTest {
        val vm = viewModel()
        vm.state.first { state -> state?.rows()?.any { it.detail is ActivityItemDetail.Split } == true }
        vm.onEvent(ActivityEvent.FilterSelected(ActivityFilter.Splits))
        val state = vm.state.first { (it?.content as? ActivityContent.Days)?.selectedFilter == ActivityFilter.Splits }!!
        assertEquals(listOf(ActivityItemId.Expense(swiggy)), state.rows().map { it.id })
        // The rest is back under All.
        vm.onEvent(ActivityEvent.FilterSelected(ActivityFilter.All))
        assertEquals(2, vm.state.first { (it?.content as? ActivityContent.Days)?.selectedFilter == ActivityFilter.All }!!.rows().size)
    }
}
