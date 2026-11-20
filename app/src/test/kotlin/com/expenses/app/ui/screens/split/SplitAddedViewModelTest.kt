package com.expenses.app.ui.screens.split

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.RAHUL_USER
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.Person
import com.expenses.app.groups.json
import com.expenses.app.ui.components.AvatarColor
import com.expenses.splits.DirectIds
import com.expenses.splits.SplitMethod
import java.time.LocalDate
import java.time.YearMonth
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Split. Sorted. (02-everyday/13): what it says, and where Done and Undo go. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SplitAddedViewModelTest {
    private val env = GroupsTestEnv()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun TestScope.added(groupExpenseId: String, typed: Boolean = false): SplitAddedViewModel {
        val vm = SplitAddedViewModel(groupExpenseId, typed, env.groups, env.expenses)
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    @Test fun doneLeavesTheFormOfTheSamePaymentToo() {
        // Done goes back to the screen that opened Split it, past Add to expenses of the same payment or Add expense.
        assertEquals(1, splitAddedScreensToLeave(undo = false, below = SplitAddedBelow.OTHER))
        assertEquals(2, splitAddedScreensToLeave(undo = false, below = SplitAddedBelow.SAME_PAYMENT_FORM))
        assertEquals(2, splitAddedScreensToLeave(undo = false, below = SplitAddedBelow.TYPED_FORM))
        // Undo goes back to where the person was: the payment waits there again.
        assertEquals(1, splitAddedScreensToLeave(undo = true, below = SplitAddedBelow.OTHER))
        assertEquals(1, splitAddedScreensToLeave(undo = true, below = SplitAddedBelow.SAME_PAYMENT_FORM))
        assertEquals(1, splitAddedScreensToLeave(undo = true, below = SplitAddedBelow.TYPED_FORM))
    }

    @Test fun showsWhoOwesAndRemembered() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000, "Swiggy")
        val id = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = true, auto = false)

        val shown = added(id).state.first { it is SplitAddedUiState.Shown } as SplitAddedUiState.Shown
        assertEquals(AddedSentence.EachOwesYou(listOf("Priya", "Rahul"), 15_000, "Swiggy"), shown.sentence)
        assertEquals(listOf("Priya", "Rahul"), shown.balances.map { it.name })
        assertEquals(listOf("Flat 302", "Flat 302"), shown.balances.map { it.group })
        assertEquals(listOf(true, true), shown.balances.map { it.owesYou })
        // Each friend's colour, as on Split it and the Group page: Rahul was stored first (green), Priya second (pink).
        assertEquals(listOf(AvatarColor.Pink, AvatarColor.Green), shown.balances.map { it.avatar })
        assertEquals(15_000L, shown.myShareMinor)
        assertEquals(45_000L, shown.totalMinor)
        assertEquals(YearMonth.of(2026, 11), shown.month)
        assertEquals("Swiggy", shown.rememberedFor)
        assertEquals(payment, shown.paymentId)
    }

    @Test fun nothingRememberedWithoutARule() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000, "Swiggy")
        val id = env.groups.splitPayment(payment, G1, SplitMethod.AMOUNTS, mapOf(M_ME to 20_000L, M_RAHUL to 25_000L, M_PRIYA to 0L), rememberRule = false, auto = false)

        val shown = added(id).state.first { it is SplitAddedUiState.Shown } as SplitAddedUiState.Shown
        assertNull(shown.rememberedFor)
        // Not designed: one person owes through this split.
        assertEquals(AddedSentence.OneOwesYou("Rahul", 25_000, "Swiggy"), shown.sentence)
    }

    @Test fun aDirectSplitsRowsDoNotRepeatTheFriendsName() = runTest {
        env.seedGroup()
        val direct = env.groups.directGroupWith(Person(RAHUL_USER, "Rahul", RAHUL_USER, null))
        val me = DirectIds.memberId(direct, ME_USER)
        val rahul = DirectIds.memberId(direct, RAHUL_USER)

        // The person paid: "Rahul" / "owes you" (the screen's caption for a row with no group).
        val payment = env.smsPayment(45_000, "Swiggy")
        val paid = env.groups.splitPayment(payment, direct, SplitMethod.EQUAL, mapOf(me to null, rahul to null), rememberRule = false, auto = false)
        val owed = added(paid).state.first { it is SplitAddedUiState.Shown } as SplitAddedUiState.Shown
        assertEquals(AddedSentence.OneOwesYou("Rahul", 22_500, "Swiggy"), owed.sentence)
        assertEquals(listOf("Rahul"), owed.balances.map { it.name })
        assertEquals(listOf<String?>(null), owed.balances.map { it.group })
        assertEquals(listOf(true), owed.balances.map { it.owesYou })

        // Rahul paid: "Rahul" / "you owe".
        val dinner = env.groups.addGroupExpense(
            direct,
            GroupExpenseDraft("Dinner", Category.FOOD, 60_000, LocalDate.of(2026, 11, 15), rahul, SplitMethod.EQUAL, mapOf(me to null, rahul to null)),
        )
        val owing = added(dinner, typed = true).state.first { it is SplitAddedUiState.Shown } as SplitAddedUiState.Shown
        assertEquals(AddedSentence.YouOwe("Rahul", 30_000, "Dinner"), owing.sentence)
        assertEquals(listOf<String?>(null), owing.balances.map { it.group })
        assertEquals(listOf(false), owing.balances.map { it.owesYou })
    }

    @Test fun whenNobodyOwesAGroupIsNamedAndAFriendIsSplitWith() = runTest {
        env.seedGroup()
        // A named group, and the whole amount is the person's own part: "Added to Flat 302."
        val inGroup = env.groups.splitPayment(
            env.smsPayment(45_000, "Swiggy"), G1, SplitMethod.AMOUNTS, mapOf(M_ME to 45_000L, M_RAHUL to 0L, M_PRIYA to 0L), rememberRule = false, auto = false,
        )
        val group = added(inGroup).state.first { it is SplitAddedUiState.Shown } as SplitAddedUiState.Shown
        assertEquals(AddedSentence.InGroup("Flat 302"), group.sentence)
        assertEquals(emptyList<AddedBalance>(), group.balances)

        // A split with one friend has no group to name: "Split with Rahul.", never "Added to Rahul Menon."
        val direct = env.groups.directGroupWith(Person(RAHUL_USER, "Rahul Menon", RAHUL_USER, null))
        val me = DirectIds.memberId(direct, ME_USER)
        val rahul = DirectIds.memberId(direct, RAHUL_USER)
        val withFriend = env.groups.splitPayment(
            env.smsPayment(30_000, "Zepto"), direct, SplitMethod.AMOUNTS, mapOf(me to 30_000L, rahul to 0L), rememberRule = false, auto = false,
        )
        val friend = added(withFriend).state.first { it is SplitAddedUiState.Shown } as SplitAddedUiState.Shown
        assertEquals(AddedSentence.WithFriend("Rahul"), friend.sentence)
        assertEquals(emptyList<AddedBalance>(), friend.balances)

        // The friend paid and the person's part is nothing: the same line.
        val theirs = env.groups.addGroupExpense(
            direct,
            GroupExpenseDraft("Cab", Category.TRAVEL, 20_000, LocalDate.of(2026, 11, 15), rahul, SplitMethod.AMOUNTS, mapOf(me to 0L, rahul to 20_000L)),
        )
        val nothing = added(theirs, typed = true).state.first { it is SplitAddedUiState.Shown } as SplitAddedUiState.Shown
        assertEquals(AddedSentence.WithFriend("Rahul"), nothing.sentence)
    }

    @Test fun undoPutsThePaymentBackToWaiting() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000, "Swiggy")
        val id = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = false, auto = false)
        val vm = added(id)
        vm.state.first { it is SplitAddedUiState.Shown }

        vm.onEvent(SplitAddedEvent.Undo)
        vm.state.first { it == SplitAddedUiState.Undone }
        val row = env.expenses.get(payment)!!
        assertEquals(ExpenseStatus.DETECTED, row.status)
        assertNull(row.groupExpenseId)
        assertEquals(45_000L, row.myShareMinor)
        assertNull(env.groups.observeSplitLabel(id).first())
    }

    @Test fun undoAfterAndroidStoppedTheAppStillPutsThePaymentBackToWaiting() = runTest {
        env.seedGroup()
        val payment = env.smsPayment(45_000, "Swiggy")
        val id = env.groups.splitPayment(payment, G1, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = false, auto = false)
        // The split was sent, then Android stopped the app: nothing in memory or in the outbox remembers the payment.
        env.fake.on("PUT", "/v1/groups/$G1/expenses/$id", json(201, "{}"))
        env.engine.push()
        val afterRestart = GroupsRepository(
            env.api, env.transactions, env.db.groupDao(), env.db.groupExtrasDao(), env.db.expenseDao(), env.outbox, env.mirrors,
            env.local, env.scheduler, currentUser = { env.me }, clock = env.clock, linkHost = "links.test",
        )
        // Split. Sorted. comes back from its route, which kept the payment's status from before the split.
        val vm = SplitAddedViewModel(id, typed = false, afterRestart, env.expenses, priorStatus = ExpenseStatus.DETECTED)
        backgroundScope.launch { vm.state.collect {} }
        vm.state.first { it is SplitAddedUiState.Shown }

        vm.onEvent(SplitAddedEvent.Undo)
        vm.state.first { it == SplitAddedUiState.Undone }

        val row = env.expenses.get(payment)!!
        assertEquals(ExpenseStatus.DETECTED, row.status)
        assertNull(row.groupExpenseId)
        assertEquals(45_000L, row.myShareMinor)
    }

    @Test fun undoOfATypedSplitRemovesItsRow() = runTest {
        env.seedGroup()
        val id = env.groups.addGroupExpense(
            G1,
            GroupExpenseDraft("Dinner", Category.FOOD, 60_000, LocalDate.of(2026, 11, 15), M_ME, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null)),
        )
        val own = env.expenses.findByGroupExpense(id)!!.id
        val vm = added(id, typed = true)
        vm.state.first { it is SplitAddedUiState.Shown }

        vm.onEvent(SplitAddedEvent.Undo)
        vm.state.first { it == SplitAddedUiState.Undone }
        assertNull(env.expenses.get(own))
    }
}
