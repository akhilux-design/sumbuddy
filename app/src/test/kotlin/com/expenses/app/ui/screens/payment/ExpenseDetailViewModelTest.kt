package com.expenses.app.ui.screens.payment

import androidx.lifecycle.SavedStateHandle
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.OutboxOp
import com.expenses.splits.SplitMethod
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

/** Expense detail of a split (02-everyday/47): the split row, delete for everyone, and edits through the group. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExpenseDetailViewModelTest {
    private val env = GroupsTestEnv()
    private val everyone = mapOf<String, Long?>(M_ME to null, M_RAHUL to null, M_PRIYA to null)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun TestScope.detail(expenseId: Long): ExpenseDetailViewModel {
        val vm = ExpenseDetailViewModel(expenseId, env.expenses, env.groups, env.clock, SavedStateHandle())
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    private suspend fun ExpenseDetailViewModel.shown(check: (ExpenseDetailUiState.Shown) -> Boolean = { true }) =
        state.first { it is ExpenseDetailUiState.Shown && check(it) } as ExpenseDetailUiState.Shown

    @Test fun aSplitShowsItsGroupAndCannotBeSplitAgain() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000, "Swiggy")
        env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, everyone, rememberRule = false, auto = false)

        val shown = detail(payment).shown { it.split != null }
        assertEquals(ExpenseSplit("Flat 302", SplitMethod.EQUAL, 15_000, splitsItself = false, paidByName = null), shown.split)
        assertTrue(shown.details.linked)
        assertFalse(shown.canSplit)
    }

    @Test fun anUnsplitPaymentCanBeSplit() = runTest {
        val payment = env.smsPayment(45_000, "Swiggy", status = ExpenseStatus.ADDED)
        val shown = detail(payment).shown()
        assertNull(shown.split)
        assertTrue(shown.canSplit)
    }

    @Test fun aSplitThatSplitsItselfSaysSo() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(2_400_000, "Rent")
        env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = true)
        assertTrue(detail(payment).shown { it.split != null }.split!!.splitsItself)
    }

    @Test fun deleteOfASplitRemovesItForEveryoneThenStopsThePaymentCounting() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000, "Swiggy")
        val groupExpenseId = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, everyone, rememberRule = false, auto = false)
        val vm = detail(payment)
        vm.shown { it.split != null }

        vm.onEvent(ExpenseDetailEvent.Delete)
        assertTrue(vm.shown { it.confirmingDelete }.confirmingDelete)
        vm.onEvent(ExpenseDetailEvent.ConfirmDelete)
        vm.state.first { it == ExpenseDetailUiState.Removed }

        // The group expense is gone for everyone (its create, never sent, is not sent any more) ...
        assertNull(env.groups.observeSplitLabel(groupExpenseId).first())
        assertTrue(env.groups.observeGroup(G1).first()!!.expenses.none { it.expense.id == groupExpenseId })
        val left = env.db.outboxDao().all().filter { it.entityKind == EntityKind.EXPENSE && it.entityId == groupExpenseId }
        assertTrue(left.none { it.op == OutboxOp.CREATE } || left.any { it.op == OutboxOp.DELETE })
        // ... and the person's payment no longer counts at all.
        val row = env.expenses.get(payment)!!
        assertEquals(ExpenseStatus.IGNORED, row.status)
        assertNull(row.groupExpenseId)
    }

    @Test fun deleteOfAFriendsExpenseRemovesItsMirror() = runTest {
        env.seedGroup()
        val groupExpenseId = env.groups.addGroupExpense(
            G1,
            GroupExpenseDraft("Dinner", Category.FOOD, 60_000, LocalDate.of(2026, 11, 15), M_RAHUL, SplitMethod.EQUAL, everyone),
        )
        val mirror = env.expenses.findByGroupExpense(groupExpenseId)!!
        assertEquals(ExpenseSource.GROUP, mirror.source)
        val vm = detail(mirror.id)
        val shown = vm.shown { it.split != null }
        assertEquals("Rahul", shown.split!!.paidByName)
        assertEquals(20_000L, shown.split!!.myShareMinor)

        vm.onEvent(ExpenseDetailEvent.Delete)
        vm.onEvent(ExpenseDetailEvent.ConfirmDelete)
        vm.state.first { it == ExpenseDetailUiState.Removed }
        assertNull(env.expenses.get(mirror.id))
        assertNull(env.groups.observeSplitLabel(groupExpenseId).first())
    }

    @Test fun editsOfASplitGoThroughTheGroupAndTheNoteStaysOwn() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000, "Swiggy")
        val groupExpenseId = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, everyone, rememberRule = false, auto = false)
        val vm = detail(payment)
        vm.shown { it.split != null }

        vm.onEvent(ExpenseDetailEvent.Edit)
        val edit = vm.shown { it.edit != null }.edit!!
        // A split's amount is the group's.
        assertNull(edit.amountText)
        vm.onEvent(ExpenseDetailEvent.CategoryChosen(Category.GROCERIES))
        vm.onEvent(ExpenseDetailEvent.PayeeChanged("Swiggy Instamart"))
        vm.onEvent(ExpenseDetailEvent.NoteChanged("weekly shop"))
        vm.onEvent(ExpenseDetailEvent.SaveEdit)
        vm.shown { it.edit == null && it.details.note == "weekly shop" }

        val stored = env.groups.observeGroup(G1).first()!!.expenses.single { it.expense.id == groupExpenseId }
        assertEquals(Category.GROCERIES, stored.expense.category)
        assertEquals("Swiggy Instamart", stored.expense.description)
        // The split is unchanged: same way, same shares.
        assertEquals(SplitMethod.EQUAL, stored.expense.splitMethod)
        assertEquals(setOf(15_000L), stored.shares.map { it.shareMinor }.toSet())
        val row = env.expenses.get(payment)!!
        assertEquals(Category.GROCERIES, row.category)
        assertEquals("weekly shop", row.note)
        assertEquals("Swiggy Instamart", row.counterparty)
        assertEquals(15_000L, row.myShareMinor)
        // The note never goes to the group.
        assertTrue(env.db.outboxDao().all().mapNotNull { it.bodyJson }.none { "weekly shop" in it })
    }

    @Test fun aUpiIdTypedAsThePayeeStaysOffTheGroup() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000, "Swiggy")
        val groupExpenseId = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, everyone, rememberRule = false, auto = false)
        val vm = detail(payment)
        vm.shown { it.split != null }

        vm.onEvent(ExpenseDetailEvent.Edit)
        vm.onEvent(ExpenseDetailEvent.PayeeChanged("rahul.k@okaxis"))
        vm.onEvent(ExpenseDetailEvent.SaveEdit)
        vm.shown { it.edit == null && it.details.merchant == "rahul.k@okaxis" }
        assertEquals("Swiggy", env.groups.observeGroup(G1).first()!!.expenses.single { it.expense.id == groupExpenseId }.expense.description)
    }

    @Test fun aCategoryPickedOutsideAnEditGoesThroughTheGroup() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000, "Swiggy")
        val groupExpenseId = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, everyone, rememberRule = false, auto = false)
        val vm = detail(payment)
        vm.shown { it.split != null }

        vm.onEvent(ExpenseDetailEvent.CategoryChosen(Category.FUN))
        vm.shown { it.details.category == Category.FUN }
        assertEquals(Category.FUN, env.groups.observeGroup(G1).first()!!.expenses.single { it.expense.id == groupExpenseId }.expense.category)
    }
}
