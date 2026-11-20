package com.expenses.app.ui.screens.review

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.repository.TestDatabase
import com.expenses.app.data.repository.nov
import com.expenses.app.data.settings.ReviewSession
import com.expenses.app.data.settings.ReviewSessionDecision
import com.expenses.app.data.settings.ReviewSessionStore
import com.expenses.parser.Direction
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

/**
 * The run of Review payments kept in [ReviewSessionStore]: Undo and All sorted span visits (a new ViewModel on the
 * same store is the screen opened again, or after Android stopped the app), a split made from the run counts as
 * added, and Done ends the run. A run left on All sorted another way than Done ends when a new payment waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReviewPaymentsViewModelRunTest {
    private val test = TestDatabase()
    private val repo = test.expenses
    private val folder: File = Files.createTempDirectory("review-run").toFile()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        test.close()
        folder.deleteRecursively()
    }

    private fun debit(amountMinor: Long, at: Instant) = Expense(
        amountMinor = amountMinor,
        direction = Direction.DEBIT,
        counterparty = "Shop $amountMinor",
        category = Category.FOOD,
        occurredAt = at,
        source = ExpenseSource.SMS,
        status = ExpenseStatus.DETECTED,
        paidWith = PaidWith.UPI,
        smsReceivedAt = at,
        originalMessage = "Sent Rs.${amountMinor / 100}.00 from HDFC Bank A/C *4521",
    )

    private fun TestScope.store() =
        ReviewSessionStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder, "review.preferences_pb") })

    private val visits = mutableMapOf<ReviewPaymentsViewModel, Job>()

    /** One visit of the screen: a ViewModel whose state is watched until [close]. */
    private fun TestScope.open(store: ReviewSessionStore): ReviewPaymentsViewModel {
        val vm = ReviewPaymentsViewModel(repo, test.clock, store, backgroundScope)
        visits[vm] = backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    /** The screen of [vm] is left (or Android stopped the app): nothing watches it any more. */
    private fun close(vm: ReviewPaymentsViewModel) {
        visits.remove(vm)?.cancel()
    }

    private suspend fun ReviewPaymentsViewModel.awaitReviewing(cardId: Long): ReviewPaymentsUiState.Reviewing =
        state.first { it is ReviewPaymentsUiState.Reviewing && it.card.id == cardId } as ReviewPaymentsUiState.Reviewing

    private suspend fun ReviewPaymentsViewModel.awaitSorted(): ReviewPaymentsUiState.Sorted =
        state.first { it is ReviewPaymentsUiState.Sorted } as ReviewPaymentsUiState.Sorted

    private suspend fun awaitStatus(id: Long, status: ExpenseStatus) {
        repo.observe(id).first { it?.expense?.status == status }
    }

    /** What GroupsRepository.splitPayment does to the person's payment: added, linked, counting only their share. */
    private suspend fun splitInGroup(id: Long, shareMinor: Long) {
        val row = repo.get(id)!!
        repo.update(row.copy(status = ExpenseStatus.ADDED, groupExpenseId = "ge-$id", myShareMinor = shareMinor))
    }

    /** What GroupsRepository.undoSplit does: the payment waits again as it was. */
    private suspend fun undoSplitInGroup(id: Long) {
        val row = repo.get(id)!!
        repo.update(row.copy(status = ExpenseStatus.DETECTED, groupExpenseId = null, myShareMinor = row.amountMinor))
    }

    @Test fun undoWorksInANewViewModelOnTheSameStore() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val b = repo.insert(debit(20_000, nov(10)))
        val c = repo.insert(debit(30_000, nov(11)))
        val store = store()
        val vm = open(store)
        vm.awaitReviewing(a)
        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Add))
        vm.awaitReviewing(b)
        vm.onEvent(ReviewPaymentsEvent.Decide(b, ReviewDecision.Ignore))
        vm.awaitReviewing(c)
        store.session.first { it.decisions.size == 2 }
        // Android stops the app: the screen is opened again with a new ViewModel.
        close(vm)

        val again = open(store)
        var shown = again.awaitReviewing(c)
        assertEquals(3, shown.position)
        assertEquals(3, shown.total)
        assertTrue(shown.canUndo)

        again.onEvent(ReviewPaymentsEvent.Undo)
        shown = again.awaitReviewing(b)
        assertEquals(2, shown.position)
        assertEquals(3, shown.total)
        awaitStatus(b, ExpenseStatus.DETECTED)
        assertEquals(ExpenseStatus.ADDED, repo.get(a)!!.status)
        // The ignored payment's SMS text stays gone, also after Undo.
        assertNull(repo.get(b)!!.originalMessage)
        store.session.first { it.decisions == listOf(ReviewSessionDecision(added = true, ids = listOf(a))) }
    }

    @Test fun allSortedCountsEveryVisitOfTheRun() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val b = repo.insert(debit(20_000, nov(10)))
        val c = repo.insert(debit(30_000, nov(11)))
        val store = store()
        val vm = open(store)
        vm.awaitReviewing(a)
        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Add))
        vm.awaitReviewing(b)
        store.session.first { it.decisions.size == 1 }
        close(vm)

        val again = open(store)
        assertEquals(2, again.awaitReviewing(b).position)
        again.onEvent(ReviewPaymentsEvent.Decide(b, ReviewDecision.Ignore))
        again.awaitReviewing(c)
        again.onEvent(ReviewPaymentsEvent.Decide(c, ReviewDecision.Add))

        val sorted = again.awaitSorted()
        assertEquals(2, sorted.addedCount)
        assertEquals(1, sorted.ignoredCount)
        assertEquals(40_000L, sorted.addedMinor)
        assertEquals(3, sorted.sortedCount)
        assertTrue(sorted.canUndo)
    }

    @Test fun aPaymentDecidedElsewhereDropsOutOfTheRun() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val b = repo.insert(debit(20_000, nov(10)))
        val store = store()
        val vm = open(store)
        vm.awaitReviewing(a)
        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Add))
        vm.awaitReviewing(b)
        store.session.first { it.decisions.size == 1 }
        awaitStatus(a, ExpenseStatus.ADDED)
        close(vm)
        // Ignored from Expense detail meanwhile.
        repo.ignore(a)

        val again = open(store)
        val shown = again.awaitReviewing(b)
        assertEquals(1, shown.position)
        assertEquals(1, shown.total)
        assertFalse(shown.canUndo)
        store.session.first { it == ReviewSession() }
    }

    @Test fun aSplitFromTheRunCountsAsAddedWithTheShare() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val b = repo.insert(debit(45_000, nov(10)))
        val c = repo.insert(debit(30_000, nov(11)))
        val store = store()
        val vm = open(store)
        vm.awaitReviewing(a)
        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Ignore))
        vm.awaitReviewing(b)

        // "Split it instead", then Split it splits the payment and Done there comes back.
        vm.onEvent(ReviewPaymentsEvent.Split(b))
        store.session.first { it.splitIds == listOf(b) }
        splitInGroup(b, shareMinor = 15_000)
        var shown = vm.awaitReviewing(c)
        assertEquals(3, shown.position)
        assertEquals(3, shown.total)

        vm.onEvent(ReviewPaymentsEvent.Decide(c, ReviewDecision.Add))
        val sorted = vm.awaitSorted()
        assertEquals(2, sorted.addedCount)
        assertEquals(1, sorted.ignoredCount)
        // Only the person's share of the split counts.
        assertEquals(45_000L, sorted.addedMinor)
        awaitStatus(c, ExpenseStatus.ADDED)

        // Undo takes back the Add, then the Ignore before the split; never the split.
        vm.onEvent(ReviewPaymentsEvent.Undo)
        shown = vm.awaitReviewing(c)
        assertEquals(3, shown.position)
        assertTrue(shown.canUndo)
        awaitStatus(c, ExpenseStatus.DETECTED)
        vm.onEvent(ReviewPaymentsEvent.Undo)
        shown = vm.awaitReviewing(a)
        assertEquals(2, shown.position)
        assertEquals(3, shown.total)
        assertFalse(shown.canUndo)
        awaitStatus(a, ExpenseStatus.DETECTED)
        val split = repo.get(b)!!
        assertEquals(ExpenseStatus.ADDED, split.status)
        assertEquals("ge-$b", split.groupExpenseId)
    }

    @Test fun aSplitNotMadeLeavesThePaymentInTheStack() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val b = repo.insert(debit(20_000, nov(10)))
        val store = store()
        val vm = open(store)
        vm.awaitReviewing(a)

        // Back from Split it (or Not now on Sign in): the payment still waits, still the first card of the run.
        vm.onEvent(ReviewPaymentsEvent.Split(a))
        store.session.first { it.splitIds == listOf(a) }
        val shown = vm.awaitReviewing(a)
        assertEquals(1, shown.position)
        assertEquals(2, shown.total)

        // Split, then Undo on Split. Sorted.: it waits again.
        splitInGroup(a, shareMinor = 5_000)
        assertEquals(2, vm.awaitReviewing(b).position)
        undoSplitInGroup(a)
        assertEquals(1, vm.awaitReviewing(a).position)

        // Added here after all: an Add of the run, no longer a split.
        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Add))
        vm.awaitReviewing(b)
        store.session.first { it.splitIds.isEmpty() && it.decisions.size == 1 }
    }

    @Test fun aSplitCountsInTheRunAfterANewViewModel() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val b = repo.insert(debit(20_000, nov(10)))
        val store = store()
        val vm = open(store)
        vm.awaitReviewing(a)
        vm.onEvent(ReviewPaymentsEvent.Split(a))
        store.session.first { it.splitIds == listOf(a) }
        close(vm)
        splitInGroup(a, shareMinor = 4_000)

        val again = open(store)
        val shown = again.awaitReviewing(b)
        assertEquals(2, shown.position)
        assertFalse(shown.canUndo)
        again.onEvent(ReviewPaymentsEvent.Decide(b, ReviewDecision.Add))
        val sorted = again.awaitSorted()
        assertEquals(2, sorted.addedCount)
        assertEquals(24_000L, sorted.addedMinor)
    }

    @Test fun aRunOfOnlySplitsIsSortedWithNothingToUndo() = runTest {
        val a = repo.insert(debit(45_000, nov(9)))
        val store = store()
        val vm = open(store)
        vm.awaitReviewing(a)
        vm.onEvent(ReviewPaymentsEvent.Split(a))
        store.session.first { it.splitIds == listOf(a) }
        splitInGroup(a, shareMinor = 15_000)

        val sorted = vm.awaitSorted()
        assertEquals(1, sorted.addedCount)
        assertEquals(0, sorted.ignoredCount)
        assertEquals(15_000L, sorted.addedMinor)
        // Undo on Split. Sorted. takes a split back; All sorted has nothing to undo.
        assertFalse(sorted.canUndo)
    }

    @Test fun anAddedPaymentSplitSinceIsNotPutBackByUndo() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val b = repo.insert(debit(20_000, nov(10)))
        val store = store()
        val vm = open(store)
        vm.awaitReviewing(a)
        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Add))
        vm.awaitReviewing(b)
        awaitStatus(a, ExpenseStatus.ADDED)
        // Split from Expense detail meanwhile.
        splitInGroup(a, shareMinor = 6_000)

        vm.onEvent(ReviewPaymentsEvent.Undo)
        // It stays split and counts in the run as a split; nothing is left to undo.
        val shown = vm.state.first { (it as? ReviewPaymentsUiState.Reviewing)?.let { r -> r.card.id == b && !r.canUndo } == true }
            as ReviewPaymentsUiState.Reviewing
        assertEquals(2, shown.position)
        assertEquals(ExpenseStatus.ADDED, repo.get(a)!!.status)
        store.session.first { it.decisions.isEmpty() && it.splitIds == listOf(a) }
    }

    @Test fun doneEndsTheRun() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val store = store()
        val vm = open(store)
        vm.awaitReviewing(a)
        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Add))
        vm.awaitSorted()
        store.session.first { it.decisions.size == 1 }

        vm.onEvent(ReviewPaymentsEvent.Done)
        store.session.first { it == ReviewSession() }
        close(vm)

        // A later review starts a new run: nothing of the old one is counted or undone.
        val b = repo.insert(debit(20_000, nov(10)))
        val again = open(store)
        val shown = again.awaitReviewing(b)
        assertEquals(1, shown.position)
        assertEquals(1, shown.total)
        assertFalse(shown.canUndo)
    }

    @Test fun aSortedRunLeftWithoutDoneEndsWhenANewPaymentWaits() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val b = repo.insert(debit(20_000, nov(10)))
        val store = store()
        val vm = open(store)
        vm.awaitReviewing(a)
        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Add))
        vm.awaitReviewing(b)
        vm.onEvent(ReviewPaymentsEvent.Decide(b, ReviewDecision.Ignore))
        vm.awaitSorted()
        store.session.first { it.sorted && it.decisions.size == 2 }
        // A notification opens on a fresh stack, or the app is swiped away: All sorted is left without Done.
        close(vm)

        // Later a new payment waits, and Home offers to review it.
        val c = repo.insert(debit(30_000, nov(11)))
        val again = open(store)
        val shown = again.awaitReviewing(c)
        assertEquals(1, shown.position)
        assertEquals(1, shown.total)
        assertFalse(shown.canUndo)
        store.session.first { it == ReviewSession() }

        // All sorted counts this run only.
        again.onEvent(ReviewPaymentsEvent.Decide(c, ReviewDecision.Add))
        val sorted = again.awaitSorted()
        assertEquals(1, sorted.addedCount)
        assertEquals(0, sorted.ignoredCount)
        assertEquals(30_000L, sorted.addedMinor)
        assertEquals(ExpenseStatus.ADDED, repo.get(a)!!.status)
        assertEquals(ExpenseStatus.IGNORED, repo.get(b)!!.status)
    }

    @Test fun aSortedRunStillOffersUndoWhenNothingNewWaits() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val b = repo.insert(debit(20_000, nov(10)))
        val store = store()
        val vm = open(store)
        vm.awaitReviewing(a)
        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Add))
        vm.awaitReviewing(b)
        vm.onEvent(ReviewPaymentsEvent.Decide(b, ReviewDecision.Add))
        vm.awaitSorted()
        store.session.first { it.sorted && it.decisions.size == 2 }
        // Android stops the app on All sorted and brings it back there.
        close(vm)

        val again = open(store)
        val sorted = again.awaitSorted()
        assertEquals(2, sorted.addedCount)
        assertEquals(30_000L, sorted.addedMinor)
        assertTrue(sorted.canUndo)

        // Undo opens the run again: left now, it carries on next time.
        again.onEvent(ReviewPaymentsEvent.Undo)
        val shown = again.awaitReviewing(b)
        assertEquals(2, shown.position)
        assertEquals(2, shown.total)
        awaitStatus(b, ExpenseStatus.DETECTED)
        store.session.first { !it.sorted && it.decisions == listOf(ReviewSessionDecision(added = true, ids = listOf(a))) }
    }

    @Test fun aPaymentThatCameWhileAllSortedShowedStillEndsTheRun() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val store = store()
        val vm = open(store)
        vm.awaitReviewing(a)
        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Add))
        vm.awaitSorted()
        store.session.first { it.sorted && it.decisions.size == 1 }

        // A payment comes in while All sorted shows: the screen shows it as the run's next card.
        val b = repo.insert(debit(20_000, nov(10)))
        assertEquals(2, vm.awaitReviewing(b).position)
        // Its notification is tapped: the screen is left without a decision.
        close(vm)

        val again = open(store)
        val shown = again.awaitReviewing(b)
        assertEquals(1, shown.position)
        assertEquals(1, shown.total)
        assertFalse(shown.canUndo)
    }
}
