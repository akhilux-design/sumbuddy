package com.expenses.app.ui.screens.grouppage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.expenses.app.data.category.Category
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.RAHUL_USER
import com.expenses.app.groups.api.MemberDto
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.data.RejectedChangeEntity
import com.expenses.app.groups.error
import com.expenses.app.groups.json
import com.expenses.app.groups.sync.SyncScheduler
import com.expenses.app.ui.components.AvatarColor
import com.expenses.splits.SplitMethod
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Group page's ViewModel (docs/groups-build-plan.md 4.2): placeholders, a group not here yet, the join banner and
 * Undo, the balances of docs/server.md 6.4, the footer and row targets, Remind, and Try again on a refused split.
 *
 * It runs on a real main thread rather than a test scheduler: the page waits for real time (a sync, 15 seconds), and
 * a test scheduler would skip that wait while the database answers on its own threads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GroupPageViewModelTest {
    private val env = GroupsTestEnv()
    private val dao = env.db.groupDao()
    private val mainThread = Executors.newSingleThreadExecutor()
    private val main = mainThread.asCoroutineDispatcher()
    private val online = MutableStateFlow(true)
    private val sync = FakeSync()
    private val now = MutableStateFlow(env.clock.instant())
    private val vikram = "a0000000-0000-4000-8000-000000000003"

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() {
        runBlocking(main) { store.clear() }
        env.close()
        drainMain()
        Dispatchers.resetMain()
        main.close()
    }

    /** The ViewModels a test made: cleared after it, so nothing of theirs still runs when the main thread goes. */
    private val store = ViewModelStore()
    private var made = 0

    private inline fun <reified T : ViewModel> kept(crossinline make: () -> T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
        }
        return ViewModelProvider(store, factory)["vm${made++}", T::class.java]
    }

    /** Waits until the main thread has run everything handed to it. */
    private fun drainMain() = repeat(DRAIN_ROUNDS) {
        mainThread.submit {}.get()
        Thread.sleep(DRAIN_PAUSE_MS)
    }

    private fun viewModel(waitLimit: Duration = Duration.ofSeconds(30), saved: SavedStateHandle = SavedStateHandle()) = kept {
        GroupPageViewModel(
            groupId = G1,
            groups = env.groups,
            rejectedChanges = env.rejectedChanges,
            expenses = env.expenses,
            online = online,
            sync = sync,
            clock = env.clock,
            ticks = now,
            waitLimit = waitLimit,
            saved = saved,
        )
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(TIMEOUT_MS) { block() } }

    private suspend fun GroupPageViewModel.shown(predicate: (GroupPageUiState.Shown) -> Boolean = { true }): GroupPageUiState.Shown =
        state.first { it is GroupPageUiState.Shown && predicate(it) } as GroupPageUiState.Shown

    private fun draft(
        paidBy: String,
        amount: Long,
        vararg members: String,
        description: String = "Swiggy",
        on: LocalDate = LocalDate.of(2026, 11, 12),
    ) = GroupExpenseDraft(description, Category.FOOD, amount, on, paidBy, SplitMethod.EQUAL, members.associateWith { null })

    /** Vikram took Priya's place through her link an hour ago. */
    private suspend fun vikramInPriyasPlace() {
        val joinedAt = env.clock.instant().minus(Duration.ofHours(1))
        dao.upsertMembers(
            listOf(
                MemberEntity(
                    M_PRIYA, G1, vikram, "Vikram", null, null, onApp = true, removed = false, version = 6,
                    joinedAt = joinedAt, undoUntil = joinedAt.plus(Duration.ofDays(7)), placeName = "Priya",
                ),
            ),
        )
    }

    @Test fun placeholdersWhileTheGroupLoads() = test {
        // Listed by the server, nothing of it pulled yet.
        env.seedGroup(version = 0)
        dao.upsertGroup(dao.getGroup(G1)!!.copy(latestVersion = 9))

        val state = viewModel().state.first { it is GroupPageUiState.Loading && it.title.isNotEmpty() }

        assertEquals(GroupPageUiState.Loading(title = "Flat 302"), state)
    }

    @Test fun aGroupNotHereWhileOfflineCouldNotLoad() = test {
        online.value = false

        val state = viewModel().state.first { it is GroupPageUiState.CouldNotLoad }

        assertEquals(GroupPageUiState.CouldNotLoad(title = ""), state)
        assertEquals(0, sync.requests.get())
    }

    @Test fun aGroupNotHereAfterASyncIsGone() = test {
        val vm = viewModel()
        vm.state.first { it == GroupPageUiState.Loading(title = "") }
        waitFor { sync.requests.get() == 1 && sync.watching() }

        sync.syncRuns()

        vm.state.first { it == GroupPageUiState.Gone }
    }

    @Test fun aGroupNotHereIsGoneAfterTheWait() = test {
        val vm = viewModel(waitLimit = Duration.ofMillis(300))

        vm.state.first { it == GroupPageUiState.Gone }
    }

    @Test fun aGroupThatArrivesIsShown() = test {
        val vm = viewModel()
        waitFor { sync.requests.get() == 1 }

        env.seedGroup()

        assertEquals("Flat 302", vm.shown().title)
    }

    @Test fun aGroupThatGoesWhileShownLeaves() = test {
        env.seedGroup()
        val vm = viewModel()
        vm.shown()

        env.local.removeGroup(G1)

        vm.state.first { it == GroupPageUiState.Gone }
    }

    @Test fun theJoinBannerShowsOnlyToTheOwnerWithinTheWindow() = test {
        env.seedGroup()
        vikramInPriyasPlace()
        val vm = viewModel()

        assertEquals(listOf(JoinBanner(M_PRIYA, "Vikram", "Priya")), vm.shown { it.joins.isNotEmpty() }.joins)

        // The 7 days end while the page is open.
        now.value = env.clock.instant().plus(Duration.ofDays(7))
        vm.shown { it.joins.isEmpty() }

        now.value = env.clock.instant()
        vm.shown { it.joins.isNotEmpty() }
        dao.upsertGroup(dao.getGroup(G1)!!.copy(ownerUserId = RAHUL_USER, ownerMemberId = M_RAHUL))
        vm.shown { it.joins.isEmpty() }
    }

    @Test fun undoShowsJoinUndone() = test {
        env.seedGroup()
        vikramInPriyasPlace()
        val restored = MemberDto(M_PRIYA, G1, null, "Priya", phone = "+919000000001", onApp = false, version = 7)
        env.fake.on("POST", "/v1/groups/$G1/members/$M_PRIYA/undo-join", json(200, MemberDto.serializer(), restored))
        val vm = viewModel()
        vm.shown { it.joins.isNotEmpty() }

        vm.onEvent(GroupPageEvent.UndoJoin(M_PRIYA))

        val shown = vm.shown { it.undone.isNotEmpty() && it.joins.isEmpty() }
        assertEquals(listOf("Priya"), shown.undone)
        assertNull(shown.notice)
    }

    @Test fun joinUndoneNamesTheFriendAlsoWhenThisPhoneNeverSawTheirName() = test {
        // This phone first saw the group after Vikram had joined (a new phone, or signed in again): it holds no name
        // for the place but his.
        val joinedAt = env.clock.instant().minus(Duration.ofHours(1))
        env.seedGroup(
            others = listOf(
                MemberEntity(M_RAHUL, G1, RAHUL_USER, "Rahul", null, null, onApp = true, removed = false, version = 1),
                MemberEntity(
                    M_PRIYA, G1, vikram, "Vikram Shah", null, null, onApp = true, removed = false,
                    version = 6, joinedAt = joinedAt, undoUntil = joinedAt.plus(Duration.ofDays(7)), placeName = null,
                ),
            ),
        )
        val restored = MemberDto(M_PRIYA, G1, null, "Priya Nair", phone = "+919000000001", onApp = false, version = 7)
        env.fake.on("POST", "/v1/groups/$G1/members/$M_PRIYA/undo-join", json(200, MemberDto.serializer(), restored))
        val vm = viewModel()
        vm.shown { it.joins.isNotEmpty() }

        vm.onEvent(GroupPageEvent.UndoJoin(M_PRIYA))

        // "Priya is back as not on the app.", not "Vikram is back": the server's answer says who the place is again.
        assertEquals(listOf("Priya"), vm.shown { it.undone.isNotEmpty() }.undone)
    }

    /** [joiner] took the place of [place] (M_PRIYA) through a link an hour ago; Rahul is [rahul]. */
    private suspend fun joinedAs(joiner: String, place: String, rahul: String) {
        val joinedAt = env.clock.instant().minus(Duration.ofHours(1))
        env.seedGroup(
            others = listOf(
                MemberEntity(M_RAHUL, G1, RAHUL_USER, rahul, null, null, onApp = true, removed = false, version = 1),
                MemberEntity(
                    M_PRIYA, G1, vikram, joiner, null, null, onApp = true, removed = false, version = 6,
                    joinedAt = joinedAt, undoUntil = joinedAt.plus(Duration.ofDays(7)), placeName = place,
                ),
            ),
        )
    }

    @Test fun peopleAreCalledByTheirFirstNames() = test {
        joinedAs(joiner = "Priya Nair", place = "Priya", rahul = "Rahul Menon")

        val shown = viewModel().shown { it.joins.isNotEmpty() }

        // "Priya joined · Not Priya?" as drawn (40), and "Rahul" in the Balance Rows (24).
        assertEquals(JoinBanner(M_PRIYA, "Priya", "Priya"), shown.joins.single())
        assertEquals(setOf("Rahul", "Priya"), shown.balances.map { it.name }.toSet())
        // The colours Groups, Split it and the settle screens give them too: Rahul was stored first (green); the
        // place that was Priya's is second (pink), whoever holds it.
        assertEquals(AvatarColor.Green, shown.balances.single { it.memberId == M_RAHUL }.avatarColor)
        assertEquals(AvatarColor.Pink, shown.balances.single { it.memberId == M_PRIYA }.avatarColor)
    }

    @Test fun twoPeopleWithOneFirstNameKeepTheirWholeNames() = test {
        joinedAs(joiner = "Rahul Kumar", place = "Rahul Verma", rahul = "Rahul Menon")

        val shown = viewModel().shown { it.joins.isNotEmpty() }

        // The owner must see that someone else took Rahul Verma's place.
        assertEquals(JoinBanner(M_PRIYA, "Rahul Kumar", "Rahul Verma"), shown.joins.single())
        assertEquals(setOf("Rahul Menon", "Rahul Kumar"), shown.balances.map { it.name }.toSet())
    }

    @Test fun joinUndoneGoesWhenSomeoneTakesThePlaceAgain() = test {
        env.seedGroup()
        vikramInPriyasPlace()
        val restored = MemberDto(M_PRIYA, G1, null, "Priya", phone = "+919000000001", onApp = false, version = 7)
        env.fake.on("POST", "/v1/groups/$G1/members/$M_PRIYA/undo-join", json(200, MemberDto.serializer(), restored))
        val vm = viewModel()
        vm.shown { it.joins.isNotEmpty() }
        vm.onEvent(GroupPageEvent.UndoJoin(M_PRIYA))
        vm.shown { it.undone.isNotEmpty() }

        // A new link of Priya's was accepted (Remind made one): 40 again, and 41 no longer says what is true.
        vikramInPriyasPlace()

        val shown = vm.shown { it.joins.isNotEmpty() }
        assertTrue(shown.undone.isEmpty())
    }

    @Test fun joinUndoneGoesWhenThePersonLeavesThePage() = test {
        env.seedGroup()
        vikramInPriyasPlace()
        val restored = MemberDto(M_PRIYA, G1, null, "Priya", phone = "+919000000001", onApp = false, version = 7)
        env.fake.on("POST", "/v1/groups/$G1/members/$M_PRIYA/undo-join", json(200, MemberDto.serializer(), restored))
        val vm = viewModel()
        vm.shown { it.joins.isNotEmpty() }
        vm.onEvent(GroupPageEvent.UndoJoin(M_PRIYA))
        vm.shown { it.undone.isNotEmpty() }

        vm.onEvent(GroupPageEvent.Left)

        vm.shown { it.undone.isEmpty() }
    }

    @Test fun joinUndoneIsStillThereAfterAndroidStoppedTheApp() = test {
        env.seedGroup()
        vikramInPriyasPlace()
        val restored = MemberDto(M_PRIYA, G1, null, "Priya", phone = "+919000000001", onApp = false, version = 7)
        env.fake.on("POST", "/v1/groups/$G1/members/$M_PRIYA/undo-join", json(200, MemberDto.serializer(), restored))
        val saved = SavedStateHandle()
        val vm = viewModel(saved = saved)
        vm.shown { it.joins.isNotEmpty() }
        vm.onEvent(GroupPageEvent.UndoJoin(M_PRIYA))
        vm.shown { it.undone.isNotEmpty() }
        drainMain()

        // Another app was in front and Android stopped this one: the page is made again from its saved state.
        val again = viewModel(saved = saved)
        assertEquals(listOf("Priya"), again.shown().undone)

        // Leaving the page forgets it there too.
        again.onEvent(GroupPageEvent.Left)
        again.shown { it.undone.isEmpty() }
        drainMain()
        assertTrue(viewModel(saved = saved).shown().undone.isEmpty())
    }

    @Test fun anUndoRefusalHidesTheBanner() = test {
        env.seedGroup()
        vikramInPriyasPlace()
        env.fake.on("POST", "/v1/groups/$G1/members/$M_PRIYA/undo-join", error(409, "undo_expired"))
        val vm = viewModel()
        vm.shown { it.joins.isNotEmpty() }

        vm.onEvent(GroupPageEvent.UndoJoin(M_PRIYA))

        val shown = vm.shown { it.joins.isEmpty() && !it.busy }
        assertTrue(shown.undone.isEmpty())
        assertNull(shown.notice)
    }

    @Test fun undoOfflineKeepsTheBannerAndSaysSo() = test {
        env.seedGroup()
        vikramInPriyasPlace()
        online.value = false
        val vm = viewModel()
        vm.shown { it.joins.isNotEmpty() }

        vm.onEvent(GroupPageEvent.UndoJoin(M_PRIYA))

        val shown = vm.shown { it.notice == GroupNotice.Offline }
        assertEquals(1, shown.joins.size)
        assertTrue(env.fake.requests.isEmpty())
    }

    @Test fun someoneOwesYouAndSomeoneIsAllSettled() = test {
        env.seedGroup()
        env.groups.addGroupExpense(G1, draft(M_ME, 20_000, M_ME, M_RAHUL))
        val vm = viewModel()

        val shown = vm.shown { it.balances.isNotEmpty() }

        assertEquals(10_000L, shown.card.netMinor)
        assertEquals(3, shown.card.people)
        assertEquals(1, shown.card.expensesThisMonth)
        val rahul = shown.balances.first { it.memberId == M_RAHUL }
        assertEquals(BalanceKind.OwesYou, rahul.kind)
        assertEquals(10_000L, rahul.amountMinor)
        assertEquals(SettleTarget.MarkAsPaid(M_RAHUL), rahul.target)
        val priya = shown.balances.first { it.memberId == M_PRIYA }
        assertEquals(BalanceKind.AllSettled, priya.kind)
        assertNull(priya.target)
        // Nobody is owed by the person: Settle up opens Mark as paid for who owes most; Remind shows.
        assertEquals(GroupFooter(SettleTarget.MarkAsPaid(M_RAHUL), canRemind = true), shown.footer)
    }

    @Test fun youOweAndSomeoneSettlesWithOthers() = test {
        val kiran = "c0000000-0000-4000-8000-000000000004"
        env.seedGroup()
        dao.upsertMembers(listOf(MemberEntity(kiran, G1, null, "Kiran", null, null, onApp = false, removed = false, version = 1)))
        // Rahul paid ₹300 for the person, Priya and himself: both owe Rahul; Kiran has nothing.
        env.groups.addGroupExpense(G1, draft(M_RAHUL, 30_000, M_ME, M_RAHUL, M_PRIYA))
        val vm = viewModel()

        val shown = vm.shown { it.balances.size == 3 }

        assertEquals(-10_000L, shown.card.netMinor)
        assertEquals(
            listOf(M_RAHUL to BalanceKind.YouOwe, M_PRIYA to BalanceKind.SettlesWithOthers, kiran to BalanceKind.AllSettled),
            shown.balances.map { it.memberId to it.kind },
        )
        assertEquals(SettleTarget.SettleUp(M_RAHUL), shown.balances.first().target)
        assertEquals(0L, shown.balances[1].amountMinor)
        assertNull(shown.balances[1].target)
        // 42: Settle up with Rahul, no Remind.
        assertEquals(GroupFooter(SettleTarget.SettleUp(M_RAHUL), canRemind = false), shown.footer)
    }

    @Test fun nobodyOwesAnybodyHasNoFooter() = test {
        env.seedGroup()
        val shown = viewModel().shown()
        assertNull(shown.footer)
        assertEquals(0L, shown.card.netMinor)
    }

    @Test fun aThisMonthRowOpensThePersonsOwnRow() = test {
        env.seedGroup()
        val mine = env.groups.addGroupExpense(G1, draft(M_ME, 20_000, M_ME, M_RAHUL, description = "Swiggy"))
        val theirs = env.groups.addGroupExpense(G1, draft(M_RAHUL, 10_000, M_RAHUL, M_PRIYA, description = "Auto"))
        val vm = viewModel()
        val shown = vm.shown { it.expenses.size == 2 }
        val rows = shown.expenses.associateBy { it.id }
        assertTrue(rows.getValue(mine).opensDetail)
        assertTrue(rows.getValue(mine).paidByMe)
        assertEquals(RowMethod.Split(SplitMethod.EQUAL), rows.getValue(mine).method)
        // Rahul paid for Priya and himself: the person has no row of their own for it.
        assertFalse(rows.getValue(theirs).opensDetail)
        assertEquals("Rahul", rows.getValue(theirs).payerName)

        vm.onEvent(GroupPageEvent.OpenExpense(mine))

        val own = env.expenses.findByGroupExpense(mine)!!
        assertEquals(GroupPageEffect.ExpenseDetail(own.id), vm.effect.first())
    }

    @Test fun tryAgainOnARefusedSplitOfTheirPaymentOpensSplitIt() = test {
        env.seedGroup()
        val payment = env.smsPayment(45_000)
        val id = env.groups.splitPayment(
            payment, G1, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = false, auto = false,
        )
        env.fake.on("PUT", "/v1/groups/$G1/expenses/$id", error(409, "exists_different"))
        env.engine.push()
        val vm = viewModel()
        val row = vm.shown { it.notSaved.isNotEmpty() }.notSaved.single()
        assertEquals("Swiggy", row.description)
        assertTrue(row.canRetry)

        vm.onEvent(GroupPageEvent.RetryChange(row.id))

        assertEquals(GroupPageEffect.SplitPayment(payment), vm.effect.first())
        vm.shown { it.notSaved.isEmpty() }
    }

    @Test fun tryAgainIsHiddenForAChangeThatCannotBeRead() = test {
        env.seedGroup()
        val id = env.groups.addGroupExpense(G1, draft(M_RAHUL, 20_000, M_ME, M_RAHUL, description = "Auto"))
        // An edit whose sent body cannot be read: the row is drawn from the expense, and Try again cannot make it.
        dao.insertRejected(
            RejectedChangeEntity(
                groupId = G1, entityKind = EntityKind.EXPENSE, entityId = id, op = OutboxOp.UPDATE, method = "PUT",
                path = "/v1/groups/$G1/expenses/$id", bodyJson = "{}", restoreJson = null, errorCode = "bad_request",
                rejectedAt = env.clock.instant(),
            ),
        )

        val row = viewModel().shown { it.notSaved.isNotEmpty() }.notSaved.single()

        // The row shows with Dismiss only, and says that it cannot be tried again.
        assertEquals("Auto", row.description)
        assertFalse(row.canRetry)
        assertEquals(NoRetryReason.Other, row.noRetry)
    }

    @Test fun tryAgainIsHiddenOnceSomeoneInTheChangeLeft() = test {
        env.seedGroup()
        val id = env.groups.addGroupExpense(G1, draft(M_RAHUL, 30_000, M_ME, M_RAHUL, M_PRIYA, description = "Snacks"))
        env.fake.on("PUT", "/v1/groups/$G1/expenses/$id", error(400, "bad_request"))
        env.engine.push()
        val vm = viewModel()
        assertTrue(vm.shown { it.notSaved.isNotEmpty() }.notSaved.single().canRetry)

        // Priya leaves: the server would refuse the same expense again.
        dao.upsertMembers(listOf(dao.getMember(M_PRIYA)!!.copy(removed = true)))

        // The row says why there is no Try again.
        assertEquals(NoRetryReason.SomeoneLeft, vm.shown { it.notSaved.singleOrNull()?.canRetry == false }.notSaved.single().noRetry)
        // Also when the page is opened again.
        assertEquals(NoRetryReason.SomeoneLeft, viewModel().shown { it.notSaved.isNotEmpty() }.notSaved.single().noRetry)
    }

    @Test fun anEditThatKeepsSomeoneWhoLeftDoesNotBlameThem() = test {
        env.seedGroup()
        val id = env.groups.addGroupExpense(G1, draft(M_RAHUL, 30_000, M_ME, M_RAHUL, M_PRIYA, description = "Snacks"))
        // Priya left after the expense was made; an edit may keep her (docs/server.md 4.7).
        dao.upsertMembers(listOf(dao.getMember(M_PRIYA)!!.copy(removed = true)))
        val body = env.outbox.entries().single().bodyJson
        dao.insertRejected(
            RejectedChangeEntity(
                groupId = G1, entityKind = EntityKind.EXPENSE, entityId = id, op = OutboxOp.UPDATE, method = "PUT",
                path = "/v1/groups/$G1/expenses/$id", bodyJson = body, restoreJson = null, errorCode = "too_large",
                rejectedAt = env.clock.instant(),
            ),
        )

        val row = viewModel().shown { it.notSaved.isNotEmpty() }.notSaved.single()

        // The refusal would come again, but not because of Priya.
        assertEquals(NoRetryReason.Other, row.noRetry)
    }

    @Test fun aDoubleTapOnTryAgainMakesTheExpenseOnce() = test {
        env.seedGroup()
        val id = env.groups.addGroupExpense(G1, draft(M_RAHUL, 20_000, M_ME, M_RAHUL, description = "Auto"))
        env.fake.on("PUT", "/v1/groups/$G1/expenses/$id", error(409, "exists_different"))
        env.engine.push()
        val vm = viewModel()
        val row = vm.shown { it.notSaved.isNotEmpty() }.notSaved.single()

        vm.onEvent(GroupPageEvent.RetryChange(row.id))
        vm.onEvent(GroupPageEvent.RetryChange(row.id))

        val shown = vm.shown { it.notSaved.isEmpty() && it.expenses.isNotEmpty() }
        drainMain()
        assertEquals(listOf("Auto"), shown.expenses.map { it.description })
        assertEquals(listOf(OutboxOp.CREATE), env.outbox.entries().map { it.op })
        assertEquals(1, dao.activeExpensesOf(G1).size)
    }

    @Test fun thisMonthRowsAreOldestFirst() = test {
        env.seedGroup()
        env.groups.addGroupExpense(G1, draft(M_ME, 45_000, M_ME, M_RAHUL, description = "Swiggy", on = LocalDate.of(2026, 11, 16)))
        env.groups.addGroupExpense(G1, draft(M_ME, 2_400_000, M_ME, M_RAHUL, M_PRIYA, description = "Rent", on = LocalDate.of(2026, 11, 1)))
        env.groups.addGroupExpense(G1, draft(M_RAHUL, 10_000, M_ME, M_RAHUL, description = "Auto", on = LocalDate.of(2026, 11, 12)))
        // Last month's is not in "This month".
        env.groups.addGroupExpense(G1, draft(M_ME, 10_000, M_ME, M_RAHUL, description = "Cab", on = LocalDate.of(2026, 10, 31)))

        val shown = viewModel().shown { it.expenses.isNotEmpty() }

        // As 24 draws them: Rent "1 Nov" above Swiggy "Today".
        assertEquals(listOf("Rent", "Auto", "Swiggy"), shown.expenses.map { it.description })
        assertEquals(3, shown.card.expensesThisMonth)
    }

    @Test fun remindPushesAppUsersWhoOwe() = test {
        env.seedGroup()
        env.groups.addGroupExpense(G1, draft(M_ME, 30_000, M_ME, M_RAHUL, M_PRIYA))
        env.fake.on("POST", "/v1/groups/$G1/reminders", json(200, """{"sent":["$M_RAHUL"],"skipped":[]}"""))
        val vm = viewModel()
        vm.shown { it.footer != null }

        vm.onEvent(GroupPageEvent.Remind)

        assertEquals(GroupNotice.Reminded(listOf("Rahul")), vm.shown { it.notice != null }.notice)
        val sent = env.fake.requestsTo("POST", "/v1/groups/$G1/reminders").single().body.readUtf8()
        assertTrue(sent.contains(M_RAHUL))
        assertFalse(sent.contains(M_PRIYA))
    }

    @Test fun remindOpensWhatsAppWhenOnlyAFriendWithoutTheAppOwes() = test {
        env.seedGroup()
        env.groups.addGroupExpense(G1, draft(M_ME, 20_000, M_ME, M_PRIYA))
        val vm = viewModel()
        vm.shown { it.footer != null }

        vm.onEvent(GroupPageEvent.Remind)

        assertEquals(GroupPageEffect.RemindOnWhatsApp(M_PRIYA), vm.effect.first())
        assertTrue(env.fake.requests.isEmpty())
    }

    @Test fun aDirectSplitIsTitledWithTheFriend() = test {
        // A direct split has the person and one friend.
        env.seedGroup(others = listOf(MemberEntity(M_RAHUL, G1, RAHUL_USER, "Rahul", null, null, onApp = true, removed = false, version = 1)))
        dao.upsertGroup(dao.getGroup(G1)!!.copy(kind = GroupKind.DIRECT, name = null, ownerUserId = ME_USER))

        val shown = viewModel().shown()

        assertEquals("Rahul", shown.title)
        assertTrue(shown.isDirect)
    }

    /** Waits until [condition] holds, checking every 20 ms. */
    private suspend fun waitFor(condition: () -> Boolean) {
        while (!condition()) kotlinx.coroutines.delay(20)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val DRAIN_ROUNDS = 3
        const val DRAIN_PAUSE_MS = 20L
    }
}

/**
 * A sync scheduler whose "a sync runs" the test sets. Every change reaches each watcher (nothing is conflated), so
 * a sync that starts and ends at once is still seen.
 */
private class FakeSync : SyncScheduler {
    val requests = AtomicInteger()
    private val syncing = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)

    /** True once someone watches whether a sync runs. */
    fun watching(): Boolean = syncing.subscriptionCount.value > 0

    /** One sync starts and ends. */
    fun syncRuns() {
        check(syncing.tryEmit(true) && syncing.tryEmit(false))
    }

    override fun requestSync(expedited: Boolean) {
        requests.incrementAndGet()
    }

    override fun requestReminder(groupId: String, memberId: String) = Unit

    override fun requestGroupPull(groupId: String, version: Long) = Unit

    override fun schedulePeriodic() = Unit

    override fun cancelAll() = Unit

    override fun observeSyncing(): Flow<Boolean> = syncing.onStart { emit(false) }
}
