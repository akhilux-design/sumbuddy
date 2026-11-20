package com.expenses.app.ui.screens.split

import androidx.lifecycle.SavedStateHandle
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.groups.G1
import com.expenses.app.groups.G2
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_ME2
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.M_RAHUL2
import com.expenses.app.groups.RAHUL_USER
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.SplitRule
import com.expenses.app.ui.components.SelfAvatarColor
import com.expenses.app.ui.components.AvatarColor
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

/** Split it (02-everyday/09 to 11): each way of splitting, what is left to assign, the rule prefill and the split made. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SplitViewModelTest {
    private val env = GroupsTestEnv()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun TestScope.splitOf(source: SplitSource): SplitViewModel {
        val vm = SplitViewModel(source, env.groups, env.expenses, env.clock, SavedStateHandle())
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    private suspend fun SplitViewModel.form(check: (SplitUiState.Form) -> Boolean = { true }): SplitUiState.Form =
        state.first { it is SplitUiState.Form && check(it) } as SplitUiState.Form

    private fun SplitUiState.Form.shareOf(memberId: String): Long = people.first { it.memberId == memberId }.shareMinor

    @Test fun equallyIsTheStartAndTheOnlyGroupIsChosen() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000, "Swiggy")
        val vm = splitOf(SplitSource.Payment(payment))

        val form = vm.form { it.people.size == 3 }
        assertEquals(G1, form.chosenGroupId)
        assertEquals(SplitMethod.EQUAL, form.method)
        assertEquals(listOf(M_ME, M_PRIYA, M_RAHUL), form.people.map { it.memberId })
        assertTrue(form.people.first().isMe)
        assertEquals(listOf(15_000L, 15_000L, 15_000L), form.people.map { it.shareMinor })
        assertEquals(SplitNote.YourShare(15_000), form.note)
        assertTrue(form.canSplit)
        // A payment that names its merchant can be remembered; nothing is kept yet.
        assertEquals(false, form.rememberRule)
        // The person's own purple, then each friend's colour by the order they were stored in (Rahul first: green,
        // Priya second: pink): the same colours as on the Group page and the settle screens, whatever the order of
        // the rows.
        assertEquals(listOf(SelfAvatarColor, AvatarColor.Pink, AvatarColor.Green), form.people.map { it.avatar })
    }

    @Test fun amountsSayWhatIsLeftAndKeepTheButtonOff() = runTest {
        env.seedGroup()
        val vm = splitOf(SplitSource.Payment(env.smsPayment(45_000, "Swiggy")))
        vm.form { it.people.size == 3 }

        vm.onEvent(SplitEvent.MethodPicked(SplitMethod.AMOUNTS))
        // The fields start from equal parts that already add up.
        var form = vm.form { it.method == SplitMethod.AMOUNTS }
        assertEquals(listOf("150", "150", "150"), form.people.map { it.input })
        assertEquals(SplitNote.AmountsAddUp(45_000, 15_000), form.note)
        assertTrue(form.canSplit)

        // 11: ₹50 is still left to assign, and "Split ₹450" stays off.
        vm.onEvent(SplitEvent.PartChanged(M_PRIYA, "100"))
        form = vm.form { it.shareOf(M_PRIYA) == 10_000L }
        assertEquals(SplitNote.LeftToAssign(5_000, 45_000), form.note)
        assertTrue(form.note!!.isProblem)
        assertNull(form.inputs)
        assertFalse(form.canSplit)

        // More than the payment is a problem too.
        vm.onEvent(SplitEvent.PartChanged(M_ME, "300"))
        form = vm.form { it.shareOf(M_ME) == 30_000L }
        assertEquals(SplitNote.TooMuch(55_000, 45_000), form.note)
        assertFalse(form.canSplit)

        // 10: they add up to ₹450; only the person's ₹200 counts.
        vm.onEvent(SplitEvent.PartChanged(M_ME, "₹2,00abc"))
        form = vm.form { it.shareOf(M_ME) == 20_000L }
        assertEquals("200", form.people.first { it.isMe }.input)
        assertEquals(SplitNote.AmountsAddUp(45_000, 20_000), form.note)
        assertEquals(mapOf(M_ME to 20_000L, M_PRIYA to 10_000L, M_RAHUL to 15_000L), form.inputs)
        assertTrue(form.canSplit)

        // Picking another way starts its fields again.
        vm.onEvent(SplitEvent.MethodPicked(SplitMethod.EQUAL))
        assertEquals(SplitNote.YourShare(15_000), vm.form { it.method == SplitMethod.EQUAL }.note)
    }

    @Test fun percentagesAndSharesMustBeComplete() = runTest {
        env.seedGroup()
        val vm = splitOf(SplitSource.Payment(env.smsPayment(45_000, "Swiggy")))
        vm.form { it.people.size == 3 }

        vm.onEvent(SplitEvent.MethodPicked(SplitMethod.PERCENT))
        var form = vm.form { it.method == SplitMethod.PERCENT }
        assertTrue(form.canSplit)
        vm.onEvent(SplitEvent.PartChanged(M_ME, "50"))
        vm.onEvent(SplitEvent.PartChanged(M_RAHUL, "25"))
        vm.onEvent(SplitEvent.PartChanged(M_PRIYA, "20"))
        form = vm.form { it.people.first { p -> p.memberId == M_PRIYA }.input == "20" }
        assertEquals(SplitNote.PercentLeft(500), form.note)
        assertFalse(form.canSplit)
        vm.onEvent(SplitEvent.PartChanged(M_PRIYA, "25"))
        form = vm.form { it.inputs != null }
        assertEquals(22_500L, form.shareOf(M_ME))
        assertEquals(SplitNote.YourShare(22_500), form.note)

        vm.onEvent(SplitEvent.MethodPicked(SplitMethod.SHARES))
        form = vm.form { it.method == SplitMethod.SHARES }
        assertEquals(listOf("1", "1", "1"), form.people.map { it.input })
        vm.onEvent(SplitEvent.PartChanged(M_ME, "2"))
        form = vm.form { it.shareOf(M_ME) == 22_500L }
        assertEquals(11_250L, form.shareOf(M_RAHUL))
        vm.onEvent(SplitEvent.PartChanged(M_RAHUL, "0"))
        form = vm.form { it.note == SplitNote.ZeroPart }
        assertFalse(form.canSplit)
    }

    @Test fun splitGivesTheGroupWhatWasChosenAndRemembersIt() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000, "Swiggy")
        val vm = splitOf(SplitSource.Payment(payment))
        vm.form { it.people.size == 3 }
        vm.onEvent(SplitEvent.MethodPicked(SplitMethod.AMOUNTS))
        vm.onEvent(SplitEvent.PartChanged(M_ME, "200"))
        vm.onEvent(SplitEvent.PartChanged(M_PRIYA, "100"))
        vm.onEvent(SplitEvent.RememberChanged(true))
        vm.form { it.canSplit && it.rememberRule == true }

        vm.onEvent(SplitEvent.Split)
        val done = vm.done.first { it != null }!!
        assertFalse(done.sameSplitOffer)
        assertEquals(payment, done.expenseId)
        // How the payment was before, for Undo on Split. Sorted. after Android stopped the app.
        assertEquals(ExpenseStatus.DETECTED, done.priorStatus)
        // Split. Sorted. opened: Split it does not open it again.
        vm.doneHandled()
        assertNull(vm.done.value)

        // The group expense: the payment's amount, the parts as typed, paid by the person.
        val stored = env.groups.observeGroup(G1).first()!!.expenses.single { it.expense.id == done.groupExpenseId }
        assertEquals(45_000L, stored.expense.amountMinor)
        assertEquals(M_ME, stored.expense.paidByMemberId)
        assertEquals(SplitMethod.AMOUNTS, stored.expense.splitMethod)
        assertFalse(stored.expense.auto)
        assertEquals(mapOf(M_ME to 20_000L, M_RAHUL to 15_000L, M_PRIYA to 10_000L), stored.shares.associate { it.memberId to it.shareMinor })
        // The payment now counts only the person's share.
        val row = env.expenses.get(payment)!!
        assertEquals(ExpenseStatus.ADDED, row.status)
        assertEquals(20_000L, row.myShareMinor)
        assertEquals(done.groupExpenseId, row.groupExpenseId)
        // "Do this every time" keeps the rule, asking each time.
        assertEquals(
            SplitRule(G1, SplitMethod.AMOUNTS, mapOf(M_ME to 20_000L, M_RAHUL to 15_000L, M_PRIYA to 10_000L), auto = false),
            env.groups.splitRuleFor("Swiggy").first(),
        )
    }

    @Test fun theRulePrefillsTheGroupAndThePartsThenOffersSameSplit() = runTest {
        env.seedGroup()
        env.seedGroup(G2, myMember = M_ME2, others = listOf(MemberEntity(M_RAHUL2, G2, RAHUL_USER, "Rahul", null, null, true, false, 1)))
        // Last time: Swiggy with Flat 302 by shares, remembered.
        val first = env.smsPayment(40_000, "Swiggy")
        env.groups.splitPayment(first, G1, SplitMethod.SHARES, mapOf(M_ME to 2L, M_RAHUL to 1L, M_PRIYA to 1L), rememberRule = true, auto = false)

        val payment = env.smsPayment(60_000, "Swiggy")
        val vm = splitOf(SplitSource.Payment(payment))
        val form = vm.form { it.people.size == 3 }
        assertEquals(G1, form.chosenGroupId)
        assertEquals(SplitMethod.SHARES, form.method)
        assertEquals(30_000L, form.shareOf(M_ME))
        assertEquals("2", form.people.first { it.isMe }.input)
        assertEquals(true, form.rememberRule)
        // Both groups are chips; "+ New" is drawn by the screen.
        assertEquals(setOf(G1, G2), form.groups.map { it.id }.toSet())

        // Split the same way: Same split? shows in place of Split. Sorted..
        vm.onEvent(SplitEvent.Split)
        val done = vm.done.first { it != null }!!
        assertTrue(done.sameSplitOffer)
        assertEquals(payment, done.expenseId)
    }

    @Test fun aGroupWhereEveryoneElseLeftIsNotOffered() = runTest {
        env.seedGroup()
        // A second group whose only other member has left: the person is alone in it.
        env.seedGroup(G2, myMember = M_ME2, others = listOf(MemberEntity(M_RAHUL2, G2, RAHUL_USER, "Rahul", null, null, true, true, 1)))
        val vm = splitOf(SplitSource.Payment(env.smsPayment(45_000, "Swiggy")))

        // Only Flat 302 is offered; being the only group with someone to split with, it is chosen.
        val form = vm.form { it.people.size == 3 }
        assertEquals(listOf(G1), form.groups.map { it.id })
        assertEquals(G1, form.chosenGroupId)
    }

    @Test fun aRuleForAGroupEveryoneElseLeftIsNotPrefilled() = runTest {
        env.seedGroup()
        env.seedGroup(G2, myMember = M_ME2, others = listOf(MemberEntity(M_RAHUL2, G2, RAHUL_USER, "Rahul", null, null, true, false, 1)))
        env.groups.splitPayment(env.smsPayment(45_000, "Swiggy"), G2, SplitMethod.AMOUNTS, mapOf(M_ME2 to 20_000L, M_RAHUL2 to 25_000L), rememberRule = true, auto = false)
        // Rahul leaves the group the rule splits with.
        env.db.groupDao().upsertMembers(listOf(env.db.groupDao().getMember(M_RAHUL2)!!.copy(removed = true)))
        val vm = splitOf(SplitSource.Payment(env.smsPayment(45_001, "Swiggy")))

        // The rule's group has nobody left to split with: Flat 302 is chosen, in equal parts.
        val form = vm.form { it.people.size == 3 }
        assertEquals(listOf(G1), form.groups.map { it.id })
        assertEquals(G1, form.chosenGroupId)
        assertEquals(SplitMethod.EQUAL, form.method)
    }

    @Test fun aGroupChosenWithNobodyElseInItYetKeepsItsChip() = runTest {
        env.seedGroup()
        // A group just made from "+ New" with nobody added yet.
        env.seedGroup(G2, myMember = M_ME2, others = emptyList())
        val vm = splitOf(SplitSource.Payment(env.smsPayment(45_000, "Swiggy")))
        assertEquals(listOf(G1), vm.form { it.people.size == 3 }.groups.map { it.id })

        vm.onEvent(SplitEvent.GroupChosen(G2))

        val chosen = vm.form { it.chosenGroupId == G2 && it.people.size == 1 }
        assertEquals(listOf(G1, G2), chosen.groups.map { it.id })
    }

    @Test fun withSeveralGroupsAndNoRuleNothingIsChosen() = runTest {
        env.seedGroup()
        env.seedGroup(G2, myMember = M_ME2, others = listOf(MemberEntity(M_RAHUL2, G2, RAHUL_USER, "Rahul", null, null, true, false, 1)))
        val vm = splitOf(SplitSource.Payment(env.smsPayment(45_000, "Swiggy")))
        val form = vm.form()
        assertNull(form.chosenGroupId)
        assertTrue(form.people.isEmpty())
        assertFalse(form.canSplit)

        vm.onEvent(SplitEvent.GroupChosen(G2))
        val chosen = vm.form { it.people.size == 2 }
        assertEquals(G2, chosen.chosenGroupId)
        assertEquals(22_500L, chosen.shareOf(M_ME2))
    }

    @Test fun aTypedExpenseSomeoneElsePaidStaysInTheirGroup() = runTest {
        env.seedGroup()
        val draft = TypedExpense(
            amountMinor = 60_000,
            whatFor = "Dinner at Truffles",
            category = Category.FOOD,
            epochDay = LocalDate.of(2026, 11, 15).toEpochDay(),
            paidByMemberId = M_RAHUL,
            paidWith = PaidWith.CASH,
            groupId = G1,
        )
        val vm = splitOf(SplitSource.Typed(TypedExpense.decode(draft.encode())))
        val form = vm.form { it.people.size == 3 }
        assertFalse(form.canChangeGroup)
        assertEquals("Rahul", form.header.payerName)
        assertEquals(M_RAHUL, form.people.single { it.isPayer }.memberId)
        assertNull(form.rememberRule)

        vm.onEvent(SplitEvent.Split)
        val done = vm.done.first { it != null }!!
        val stored = env.groups.observeGroup(G1).first()!!.expenses.single { it.expense.id == done.groupExpenseId }
        assertEquals(M_RAHUL, stored.expense.paidByMemberId)
        assertEquals("Dinner at Truffles", stored.expense.description)
        // The person's share is a mirror row of their month.
        val mirror = env.expenses.findByGroupExpense(done.groupExpenseId)!!
        assertEquals(ExpenseSource.GROUP, mirror.source)
        assertEquals(20_000L, mirror.myShareMinor)
    }
}
