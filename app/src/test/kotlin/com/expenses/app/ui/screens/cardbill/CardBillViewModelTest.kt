package com.expenses.app.ui.screens.cardbill

import androidx.lifecycle.SavedStateHandle
import com.expenses.app.cardbill.BillState
import com.expenses.app.cardbill.CardBillDetail
import com.expenses.app.cardbill.CardBillMath
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.CardBill
import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.data.OwedPart
import com.expenses.app.groups.data.RemindResult
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.screens.family.ClearedViewModels
import com.expenses.app.ui.screens.grouppage.GroupNotice
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

/** The Card bill view's states and Remind everyone (docs/card-bills.md 2.3 and 2.6). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CardBillViewModelTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now: Instant = LocalDateTime.of(2026, 11, 16, 12, 0).atZone(zone).toInstant()
    private val clock = Clock.fixed(now, zone)
    private val online = MutableStateFlow(true)
    private val calls = CopyOnWriteArrayList<Pair<String, List<String>>>()
    private var answer: (String, List<String>) -> ApiResult<RemindResult> = { _, ids -> ApiResult.Ok(RemindResult(ids, emptyMap()), 200) }
    private var gate: CompletableDeferred<Unit>? = null
    private val viewModels = ClearedViewModels()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private val card = Account(id = 1, bank = "HDFC Bank", type = AccountType.CREDIT_CARD, last4 = "2217", displayName = "HDFC Millennia", firstSeenAt = now)
    private val bill = CardBill(
        id = 7, accountId = 1, bank = "HDFC Bank", cardLast4 = "2217", totalDueMinor = 2_430_000, minimumDueMinor = 121_500,
        dueDate = LocalDate.of(2026, 11, 21), receivedAt = now.minusSeconds(15 * 86_400L), smsId = 1,
    )

    private val rahul = OwedPart("g-goa", "Goa trip", "m-rahul", "rahul", "Rahul Menon", true, false, AvatarColor.Green, 500_000, 3, 410_000)
    private val rahulFlat = OwedPart("g-flat", "Flat 302", "m-rahul-2", "rahul", "Rahul Menon", true, false, AvatarColor.Green, 50_000, 1, 50_000)
    private val priya = OwedPart("g-flat", "Flat 302", "m-priya", "priya", "Priya", false, false, AvatarColor.Pink, 260_000, 5, 260_000)
    private val kiran = OwedPart("g-dinner", "Dinner", "m-kiran", "kiran", "Kiran", false, false, AvatarColor.Blue, 150_000, 1, 150_000)
    private val meera = OwedPart("g-flat", "Flat 302", "m-meera", "meera", "Meera", true, false, AvatarColor.Yellow, 30_000, 1, 30_000)

    private fun detail(parts: List<OwedPart>, signedIn: Boolean = true, paidOn: LocalDate? = null, due: LocalDate? = bill.dueDate) =
        CardBillDetail(
            bill = bill.copy(dueDate = due), account = card, statementDate = LocalDate.of(2026, 11, 1), paidOn = paidOn,
            signedIn = signedIn, friends = CardBillMath.friends(parts), current = true,
        )

    private val bills = MutableStateFlow<CardBillDetail?>(detail(listOf(rahul, priya, kiran)))

    private fun TestScope.viewModel(remindOnOpen: Boolean = false, saved: SavedStateHandle = SavedStateHandle()): CardBillViewModel {
        val vm = viewModels.keep {
            CardBillViewModel(
                remindOnOpen = remindOnOpen,
                detail = bills,
                remind = { groupId, ids ->
                    calls += groupId to ids
                    gate?.await()
                    answer(groupId, ids)
                },
                online = online,
                clock = clock,
                saved = saved,
                ticks = flowOf(now),
            )
        }
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    private suspend fun CardBillViewModel.shown(predicate: (CardBillUiState.Shown) -> Boolean = { true }): CardBillUiState.Shown =
        state.first { it is CardBillUiState.Shown && predicate(it) } as CardBillUiState.Shown

    @Test fun friendsOweShowsTheDrawnView() = runTest {
        val shown = viewModel().shown()
        assertEquals("HDFC Millennia", shown.cardName)
        assertEquals("2217", shown.last4)
        assertEquals(BillState.DueIn(5), shown.status)
        val body = shown.body as CardBillBody.FriendsOwe
        assertEquals(820_000L, body.friendsMinor)
        assertNull(body.oneFriend)
        assertEquals(CardBillCaption.GetBackBy(LocalDate.of(2026, 11, 21), 1_610_000), body.caption)
        assertEquals(listOf("Rahul", "Priya", "Kiran"), body.rows.map { it.name })
        assertEquals(listOf("g-goa", "g-flat", "g-dinner"), body.rows.map { it.groupId })
        assertTrue(shown.canRemind)
    }

    @Test fun nobodyOwesSignedOutOverduePaidAndOneFriend() = runTest {
        val vm = viewModel()
        bills.value = detail(emptyList())
        assertEquals(CardBillBody.NobodyOwes, vm.shown { it.body == CardBillBody.NobodyOwes }.body)
        assertFalse(vm.shown().canRemind)
        bills.value = detail(listOf(rahul), signedIn = false)
        assertEquals(CardBillBody.SignedOut, vm.shown { it.body == CardBillBody.SignedOut }.body)
        bills.value = detail(listOf(rahul), due = LocalDate.of(2026, 11, 14))
        val overdue = vm.shown { it.status is BillState.Overdue }
        assertEquals(CardBillCaption.OwnIs(2_020_000), (overdue.body as CardBillBody.FriendsOwe).caption)
        assertEquals("Rahul", (overdue.body as CardBillBody.FriendsOwe).oneFriend)
        bills.value = detail(listOf(rahul, priya, kiran), paidOn = LocalDate.of(2026, 11, 15))
        val paid = vm.shown { it.status is BillState.Paid }
        assertEquals(CardBillCaption.OwnWas(1_610_000), (paid.body as CardBillBody.FriendsOwe).caption)
        assertTrue("friends still owe: Remind everyone stays", paid.canRemind)
        bills.value = detail(listOf(rahul), due = null)
        assertEquals(CardBillCaption.GetBackNoDate(2_020_000), (vm.shown { it.status == BillState.NoDate }.body as CardBillBody.FriendsOwe).caption)
    }

    @Test fun aGoneBillLeaves() = runTest {
        val vm = viewModel()
        vm.shown()
        bills.value = null
        assertEquals(CardBillUiState.Gone, vm.state.first { it == CardBillUiState.Gone })
    }

    @Test fun remindEveryonePushesOncePerGroupThenOpensWhatsAppForTheTopFriendWithoutTheApp() = runTest {
        bills.value = detail(listOf(rahul, rahulFlat, priya, kiran, meera))
        val vm = viewModel()
        vm.shown()
        val effects = mutableListOf<CardBillEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effect.collect { effects += it } }
        vm.onEvent(CardBillEvent.RemindEveryone)
        assertEquals(
            setOf("g-goa" to listOf("m-rahul"), "g-flat" to listOf("m-rahul-2", "m-meera")),
            calls.toSet(),
        )
        assertEquals(2, calls.size)
        assertEquals(GroupNotice.Reminded(listOf("Rahul", "Meera")), vm.shown { it.notice != null }.notice)
        // Priya (₹2,600) owes more than Kiran (₹1,500).
        assertEquals(listOf<CardBillEffect>(CardBillEffect.RemindOnWhatsApp("g-flat", "m-priya")), effects)
    }

    @Test fun eachBanner() = runTest {
        val vm = viewModel()
        vm.shown()
        answer = { _, _ -> ApiResult.Ok(RemindResult(emptyList(), mapOf("m-rahul" to "recently_reminded")), 200) }
        vm.onEvent(CardBillEvent.RemindEveryone)
        assertEquals(GroupNotice.NotReminded, vm.shown { it.notice == GroupNotice.NotReminded }.notice)
        answer = { _, _ -> ApiResult.Error(ApiError(500, "internal")) }
        vm.onEvent(CardBillEvent.RemindEveryone)
        assertEquals(GroupNotice.Failed, vm.shown { it.notice == GroupNotice.Failed }.notice)
        online.value = false
        vm.onEvent(CardBillEvent.RemindEveryone)
        assertEquals(GroupNotice.Offline, vm.shown { it.notice == GroupNotice.Offline }.notice)
        assertEquals("offline: nothing more was sent", 2, calls.size)
        online.value = true
        assertNull("back online: the offline banner goes", vm.shown { it.notice == null }.notice)
    }

    @Test fun anEndedSessionOpensSignIn() = runTest {
        val vm = viewModel()
        vm.shown()
        val effects = mutableListOf<CardBillEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effect.collect { effects += it } }
        answer = { _, _ -> ApiResult.Error(ApiError(401, "unauthorized")) }
        vm.onEvent(CardBillEvent.RemindEveryone)
        assertEquals(listOf<CardBillEffect>(CardBillEffect.SignIn), effects)
    }

    @Test fun aSecondTapWhileItRunsIsIgnored() = runTest {
        val vm = viewModel()
        vm.shown()
        gate = CompletableDeferred()
        vm.onEvent(CardBillEvent.RemindEveryone)
        assertTrue(vm.shown { it.busy }.busy)
        vm.onEvent(CardBillEvent.RemindEveryone)
        gate!!.complete(Unit)
        vm.shown { !it.busy }
        assertEquals(1, calls.size)
    }

    @Test fun remindFriendsRunsOnceAcrossRecreation() = runTest {
        val saved = SavedStateHandle()
        val vm = viewModel(remindOnOpen = true, saved = saved)
        vm.shown { it.notice != null }
        assertEquals(1, calls.size)
        // The same view after a rotation or after Android stopped the app: the saved state says it ran.
        viewModel(remindOnOpen = true, saved = saved).shown()
        assertEquals(1, calls.size)
    }

    @Test fun remindFriendsDoesNothingWhenNobodyOwes() = runTest {
        bills.value = detail(emptyList())
        viewModel(remindOnOpen = true).shown()
        assertTrue(calls.isEmpty())
    }
}
