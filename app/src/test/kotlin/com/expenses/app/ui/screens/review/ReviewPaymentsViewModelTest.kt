package com.expenses.app.ui.screens.review

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.repository.TestDatabase
import com.expenses.app.data.repository.nov
import com.expenses.app.data.settings.ReviewSessionStore
import com.expenses.parser.Direction
import java.io.File
import java.nio.file.Files
import java.time.Instant
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReviewPaymentsViewModelTest {
    private val test = TestDatabase()
    private val repo = test.expenses
    private val folder: File = Files.createTempDirectory("review").toFile()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        test.close()
        folder.deleteRecursively()
    }

    /** A ViewModel with a run of its own, written in the test's background. */
    private fun TestScope.viewModel(): ReviewPaymentsViewModel {
        val store = ReviewSessionStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder, "review.preferences_pb") })
        return ReviewPaymentsViewModel(repo, test.clock, store, backgroundScope)
    }

    private fun debit(amountMinor: Long, at: Instant, category: Category = Category.FOOD, direction: Direction = Direction.DEBIT) =
        Expense(
            amountMinor = amountMinor,
            direction = direction,
            counterparty = "Shop $amountMinor",
            category = category,
            occurredAt = at,
            source = ExpenseSource.SMS,
            status = if (direction == Direction.DEBIT) ExpenseStatus.DETECTED else ExpenseStatus.ADDED,
            paidWith = PaidWith.UPI,
            smsReceivedAt = at,
        )

    private suspend fun ReviewPaymentsViewModel.awaitReviewing(cardId: Long): ReviewPaymentsUiState.Reviewing =
        state.first { it is ReviewPaymentsUiState.Reviewing && it.card.id == cardId } as ReviewPaymentsUiState.Reviewing

    private suspend fun awaitStatus(id: Long, status: ExpenseStatus) {
        repo.observe(id).first { it?.expense?.status == status }
    }

    @Test fun swipesUndoAndAddAll() = runTest {
        val a = repo.insert(debit(10_000, nov(9), Category.FOOD))
        val b = repo.insert(debit(20_000, nov(10), Category.TRAVEL))
        val c = repo.insert(debit(30_000, nov(11), Category.FOOD))
        repo.insert(debit(99_000, nov(8), direction = Direction.CREDIT))
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }

        // Oldest first; money in is not in the stack.
        var shown = vm.awaitReviewing(a)
        assertEquals(1, shown.position)
        assertEquals(3, shown.total)
        assertEquals(2, shown.cardsBehind)
        assertFalse(shown.canUndo)

        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Add))
        shown = vm.awaitReviewing(b)
        assertEquals(2, shown.position)
        assertEquals(3, shown.total)
        assertTrue(shown.canUndo)
        awaitStatus(a, ExpenseStatus.ADDED)

        vm.onEvent(ReviewPaymentsEvent.Decide(b, ReviewDecision.Ignore))
        shown = vm.awaitReviewing(c)
        assertEquals(3, shown.position)
        assertEquals(0, shown.cardsBehind)
        awaitStatus(b, ExpenseStatus.IGNORED)

        // Undo takes back the ignore only.
        vm.onEvent(ReviewPaymentsEvent.Undo)
        shown = vm.awaitReviewing(b)
        assertEquals(2, shown.position)
        awaitStatus(b, ExpenseStatus.DETECTED)
        assertEquals(ExpenseStatus.ADDED, repo.get(a)!!.status)

        // Add all: the sheet sums up what is left, by category in Choose category order.
        vm.onEvent(ReviewPaymentsEvent.OpenAddAll)
        val sheet = (vm.state.first { (it as? ReviewPaymentsUiState.Reviewing)?.addAll != null } as ReviewPaymentsUiState.Reviewing).addAll!!
        assertEquals(2, sheet.count)
        assertEquals(50_000L, sheet.totalMinor)
        assertEquals(listOf(Category.FOOD, Category.TRAVEL), sheet.byCategory.map { it.category })

        vm.onEvent(ReviewPaymentsEvent.ConfirmAddAll)
        val sorted = vm.state.first { it is ReviewPaymentsUiState.Sorted } as ReviewPaymentsUiState.Sorted
        assertEquals(3, sorted.addedCount)
        assertEquals(0, sorted.ignoredCount)
        assertEquals(60_000L, sorted.addedMinor)
        assertEquals(YearMonth.of(2026, 11), sorted.month)
        assertTrue(sorted.addedAllInMonth)
        awaitStatus(c, ExpenseStatus.ADDED)
        // The month so far follows the database: all three are November spending now.
        vm.state.first { (it as? ReviewPaymentsUiState.Sorted)?.monthTotalMinor == 60_000L }

        // Undo on All sorted puts every payment of Add all back to waiting, and only those.
        vm.onEvent(ReviewPaymentsEvent.Undo)
        shown = vm.awaitReviewing(b)
        assertEquals(2, shown.position)
        assertEquals(3, shown.total)
        awaitStatus(b, ExpenseStatus.DETECTED)
        awaitStatus(c, ExpenseStatus.DETECTED)
        assertEquals(ExpenseStatus.ADDED, repo.get(a)!!.status)
    }

    @Test fun nothingWaitingLetsTheScreenLeave() = runTest {
        repo.insert(debit(99_000, nov(8), direction = Direction.CREDIT))
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(ReviewPaymentsUiState.NothingWaiting, vm.state.first { it != ReviewPaymentsUiState.Loading })
    }

    @Test fun aPaymentUndoneHereAndThenDecidedElsewhereDoesNotComeBack() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val b = repo.insert(debit(20_000, nov(10)))
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        vm.awaitReviewing(a)

        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Ignore))
        vm.awaitReviewing(b)
        awaitStatus(a, ExpenseStatus.IGNORED)
        vm.onEvent(ReviewPaymentsEvent.Undo)
        vm.awaitReviewing(a)
        awaitStatus(a, ExpenseStatus.DETECTED)

        // Added from another screen (Split it instead, then Add to expenses).
        repo.addWaiting(listOf(a))
        val shown = vm.awaitReviewing(b)
        assertEquals(1, shown.position)
        assertEquals(1, shown.total)
        vm.onEvent(ReviewPaymentsEvent.OpenAddAll)
        val sheet = (vm.state.first { (it as? ReviewPaymentsUiState.Reviewing)?.addAll != null } as ReviewPaymentsUiState.Reviewing).addAll!!
        assertEquals(1, sheet.count)
        assertEquals(20_000L, sheet.totalMinor)
    }

    @Test fun anUndoThatBringsNothingBackLetsTheScreenLeave() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        vm.awaitReviewing(a)

        vm.onEvent(ReviewPaymentsEvent.Decide(a, ReviewDecision.Add))
        vm.state.first { it is ReviewPaymentsUiState.Sorted }
        awaitStatus(a, ExpenseStatus.ADDED)
        // The payment is deleted meanwhile (a scan restarted from a later day), so Undo has nothing to put back.
        repo.delete(a)
        vm.onEvent(ReviewPaymentsEvent.Undo)

        assertEquals(ReviewPaymentsUiState.NothingWaiting, vm.state.first { it == ReviewPaymentsUiState.NothingWaiting })
    }

    @Test fun cancelClosesTheSheetWithoutAdding() = runTest {
        val a = repo.insert(debit(10_000, nov(9)))
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        vm.awaitReviewing(a)

        vm.onEvent(ReviewPaymentsEvent.OpenAddAll)
        vm.state.first { (it as? ReviewPaymentsUiState.Reviewing)?.addAll != null }
        vm.onEvent(ReviewPaymentsEvent.CancelAddAll)
        val shown = vm.state.first { (it as? ReviewPaymentsUiState.Reviewing)?.addAll == null } as ReviewPaymentsUiState.Reviewing

        assertEquals(a, shown.card.id)
        assertEquals(ExpenseStatus.DETECTED, repo.get(a)!!.status)
    }
}
