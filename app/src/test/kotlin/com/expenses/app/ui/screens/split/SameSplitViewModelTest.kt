package com.expenses.app.ui.screens.split

import com.expenses.app.data.model.ExpenseStatus
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
import com.expenses.splits.SplitMethod
import java.time.Instant
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Same split? (02-everyday/14): Save makes the rule split by itself, or, from Expense detail, turns that off. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SameSplitViewModelTest {
    private val env = GroupsTestEnv()
    private val everyone = mapOf<String, Long?>(M_ME to null, M_RAHUL to null, M_PRIYA to null)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun TestScope.offer(expenseId: Long): SameSplitViewModel {
        val vm = SameSplitViewModel(expenseId, env.groups, env.expenses, env.clock)
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    @Test fun yesEveryMonthMakesItSplitItself() = runTest {
        env.seedGroup()
        env.groups.splitPayment(env.smsPayment(2_400_000, "Rent"), G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = false)
        val payment = env.smsPayment(2_400_001, "Rent")
        env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = false)

        val vm = offer(payment)
        val shown = vm.state.first { it is SameSplitUiState.Shown } as SameSplitUiState.Shown
        assertEquals("Rent", shown.payee)
        assertEquals("Flat 302", shown.groupName)
        assertTrue(shown.everyMonth)

        vm.onEvent(SameSplitEvent.Save)
        vm.state.first { it == SameSplitUiState.Saved }
        assertTrue(env.groups.splitRuleFor("Rent").first()!!.auto)
    }

    /** A waiting payment of [amountMinor] to [payee] made on [day] ("2026-10-01"). */
    private suspend fun paymentOn(day: String, amountMinor: Long, payee: String = "Rent"): Long {
        val id = env.smsPayment(amountMinor, payee)
        env.expenses.update(env.expenses.get(id)!!.copy(occurredAt = Instant.parse("${day}T10:00:00Z")))
        return id
    }

    private suspend fun TestScope.shown(expenseId: Long): SameSplitUiState.Shown =
        offer(expenseId).state.first { it is SameSplitUiState.Shown } as SameSplitUiState.Shown

    @Test fun lastMonthTooOnlyWhenTheEarlierSplitWasLastMonth() = runTest {
        // Today is 16 Nov 2026.
        env.seedGroup()
        env.groups.splitPayment(env.smsPayment(2_400_000, "Rent"), G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = false)
        val payment = env.smsPayment(2_400_001, "Rent")
        env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = false)

        // The earlier split is from this month (the walk's case: it was made today): not "last month too".
        assertFalse(shown(payment).splitLastMonth)

        // Nor is one from two months ago.
        env.groups.splitPayment(paymentOn("2026-09-01", 2_400_002), G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = false)
        assertFalse(shown(payment).splitLastMonth)

        // One from October is, also on its last day.
        env.groups.splitPayment(paymentOn("2026-10-31", 2_400_003), G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = false)
        assertTrue(shown(payment).splitLastMonth)
    }

    // "Last month" is the month before the payment's own, not before today: a payment can be reviewed (and split) a
    // month or more after it was made, for example after onboarding read the last 3 months of SMS.

    @Test fun anEarlierSplitInThePaymentsOwnMonthIsNotLastMonthToo() = runTest {
        // Today is 16 Nov 2026. Both October.
        env.seedGroup()
        env.groups.splitPayment(paymentOn("2026-10-05", 2_400_000), G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = false)
        val payment = paymentOn("2026-10-30", 2_400_001)
        env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = false)

        assertFalse(shown(payment).splitLastMonth)
    }

    @Test fun anEarlierSplitInTheMonthBeforeThePaymentIsLastMonthToo() = runTest {
        // Today is 16 Nov 2026. A September split, and the payment of 30 October.
        env.seedGroup()
        env.groups.splitPayment(paymentOn("2026-09-30", 2_400_000), G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = false)
        val payment = paymentOn("2026-10-30", 2_400_001)
        env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = false)

        assertTrue(shown(payment).splitLastMonth)
    }

    @Test fun lastMonthsSplitMustBeOfTheSamePayeeWithTheSameGroup() = runTest {
        env.seedGroup()
        env.seedGroup(G2, myMember = M_ME2, others = listOf(MemberEntity(M_RAHUL2, G2, RAHUL_USER, "Rahul", null, null, true, false, 1)))
        // Last month: another payee split with this group, the same payee split with another group, and the same
        // payee only added, not split.
        env.groups.splitPayment(paymentOn("2026-10-05", 45_000, "Swiggy"), G1, SplitMethod.EQUAL, everyone, rememberRule = false, auto = false)
        env.groups.splitPayment(paymentOn("2026-10-01", 2_400_002), G2, SplitMethod.EQUAL, mapOf(M_ME2 to null, M_RAHUL2 to null), rememberRule = false, auto = false)
        env.expenses.update(env.expenses.get(paymentOn("2026-10-02", 2_400_003))!!.copy(status = ExpenseStatus.ADDED))
        val payment = env.smsPayment(2_400_001, "Rent")
        env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = false)

        assertFalse(shown(payment).splitLastMonth)

        // The same payee as the bank writes it another time ("RENT" for "Rent") is the same payee.
        env.groups.splitPayment(paymentOn("2026-10-03", 2_400_004, "RENT"), G1, SplitMethod.EQUAL, everyone, rememberRule = false, auto = false)
        assertTrue(shown(payment).splitLastMonth)
    }

    @Test fun askMeEachTimeTurnsAnAutomaticSplitOff() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(2_400_000, "Rent")
        env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, everyone, rememberRule = true, auto = true)
        assertTrue(env.groups.splitRuleFor("Rent").first()!!.auto)

        // From Expense detail's split row: "Yes, every month" is chosen at the start.
        val vm = offer(payment)
        assertTrue((vm.state.first { it is SameSplitUiState.Shown } as SameSplitUiState.Shown).everyMonth)
        vm.onEvent(SameSplitEvent.Chosen(everyMonth = false))
        assertFalse((vm.state.first { it is SameSplitUiState.Shown && !it.everyMonth } as SameSplitUiState.Shown).everyMonth)

        vm.onEvent(SameSplitEvent.Save)
        vm.state.first { it == SameSplitUiState.Saved }
        val rule = env.groups.splitRuleFor("Rent").first()!!
        assertFalse(rule.auto)
        // The rule itself stays: the next payment is still offered the same split in one tap.
        assertEquals(G1, rule.groupId)
    }
}
