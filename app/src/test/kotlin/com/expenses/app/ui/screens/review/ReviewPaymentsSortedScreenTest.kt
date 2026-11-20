package com.expenses.app.ui.screens.review

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** All sorted (design 38): Done and Back end the run; Undo shows only when there is a decision to take back. */
@RunWith(RobolectricTestRunner::class)
class ReviewPaymentsSortedScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<ReviewPaymentsEvent>()

    private fun show(state: ReviewPaymentsUiState.Sorted) {
        compose.setContent {
            AppTheme(dark = false) { ReviewPaymentsScreen(state = state, onEvent = { events += it }) }
        }
    }

    @Test fun undoTakesBackTheLastDecision() {
        show(ReviewPaymentsSamples.sorted)
        compose.onNodeWithText("Undo").performClick()

        assertEquals(listOf<ReviewPaymentsEvent>(ReviewPaymentsEvent.Undo), events)
    }

    @Test fun aRunOfOnlySplitsShowsNoUndo() {
        show(ReviewPaymentsSamples.sorted.copy(addedCount = 1, addedMinor = 15_000, ignoredCount = 0, canUndo = false))

        compose.onAllNodesWithText("Undo").assertCountEquals(0)
        compose.onNodeWithText("Added 1").assertExists()
    }

    @Test fun doneAndBackEndTheRun() {
        show(ReviewPaymentsSamples.sorted)
        compose.onNodeWithText("Done").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(listOf<ReviewPaymentsEvent>(ReviewPaymentsEvent.Done, ReviewPaymentsEvent.Done), events)
    }
}
