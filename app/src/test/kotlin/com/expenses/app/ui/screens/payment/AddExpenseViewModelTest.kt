package com.expenses.app.ui.screens.payment

import androidx.lifecycle.SavedStateHandle
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.PaidWith
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.ui.screens.split.TypedExpense
import java.time.LocalDate
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Add expense (02-everyday/15): Paid by with and without a group, Split this, and the draft for Split it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddExpenseViewModelTest {
    private val env = GroupsTestEnv()
    private val today = LocalDate.of(2026, 11, 16)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun TestScope.addExpense(groupId: String?): AddExpenseViewModel {
        val vm = AddExpenseViewModel(groupId, env.expenses, env.groups, env.clock, SavedStateHandle())
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    @Test fun withoutAGroupThePayerIsYou() = runTest {
        env.seedGroup()
        val vm = addExpense(groupId = null)
        var state = vm.state.value
        assertFalse(state.splitThis)
        assertTrue(state.payers.isEmpty())
        assertNull(state.paidBy)

        // Split this on: the group is chosen on the next step, so Paid by is still only You.
        vm.onEvent(AddExpenseEvent.SplitThisChanged(true))
        vm.onEvent(AddExpenseEvent.PaidByChosen(M_RAHUL))
        state = vm.state.first { it.splitThis }
        assertTrue(state.payers.isEmpty())
        assertNull(state.paidBy)

        vm.onEvent(AddExpenseEvent.AmountChanged("600"))
        vm.onEvent(AddExpenseEvent.CategoryChosen(Category.FOOD))
        vm.onEvent(AddExpenseEvent.WhatForChanged("Dinner at Truffles "))
        vm.onEvent(AddExpenseEvent.PaidWithChosen(PaidWith.UPI))
        val draft = vm.splitDraft()
        assertEquals(
            TypedExpense(60_000, "Dinner at Truffles", Category.FOOD, today.toEpochDay(), paidByMemberId = null, paidWith = PaidWith.UPI, groupId = null),
            draft,
        )
        // Nothing is stored here: Split it makes the expense.
        assertTrue(env.expenses.observeLatest(10).first().isEmpty())
    }

    @Test fun fromAGroupPaidByListsItsPeopleWhileSplitThisIsOn() = runTest {
        env.seedGroup()
        val vm = addExpense(groupId = G1)
        var state = vm.state.first { it.payers.isNotEmpty() }
        assertTrue(state.splitThis)
        assertEquals(listOf(Payer(M_PRIYA, "Priya"), Payer(M_RAHUL, "Rahul")), state.payers)
        assertNull(state.paidBy)

        vm.onEvent(AddExpenseEvent.PaidByChosen(M_RAHUL))
        state = vm.state.first { it.paidBy == M_RAHUL }
        assertTrue(state.someoneElsePaid)
        vm.onEvent(AddExpenseEvent.AmountChanged("600"))
        val draft = vm.splitDraft()!!
        assertEquals(M_RAHUL, draft.paidByMemberId)
        assertEquals(G1, draft.groupId)
        assertEquals(60_000L, draft.amountMinor)

        // Split this off: Paid by goes back to You, and the expense is the person's own again.
        vm.onEvent(AddExpenseEvent.SplitThisChanged(false))
        state = vm.state.first { !it.splitThis }
        assertNull(state.paidBy)
        assertTrue(state.payers.isEmpty())
        assertNull(vm.splitDraft())

        vm.onEvent(AddExpenseEvent.Add)
        val added = vm.state.first { it.addedId != null }.addedId!!
        val row = env.expenses.get(added)!!
        assertEquals(ExpenseSource.MANUAL, row.source)
        assertEquals(60_000L, row.myShareMinor)
        assertNull(row.groupExpenseId)
    }

    @Test fun nextWithoutAnAmountSaysSo() = runTest {
        val vm = addExpense(groupId = G1)
        assertNull(vm.splitDraft())
        assertTrue(vm.state.first { it.amountError }.amountError)
    }

    @Test fun addDoesNothingWhileSplitThisIsOn() = runTest {
        env.seedGroup()
        val vm = addExpense(groupId = G1)
        vm.onEvent(AddExpenseEvent.AmountChanged("600"))
        vm.onEvent(AddExpenseEvent.Add)
        assertNull(vm.state.value.addedId)
        assertTrue(env.expenses.observeLatest(10).first().isEmpty())
    }
}
