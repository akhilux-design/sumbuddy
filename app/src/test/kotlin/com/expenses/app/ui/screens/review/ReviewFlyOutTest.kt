package com.expenses.app.ui.screens.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A decision is sent at once and the next card takes the top while the decided one flies out over it (`docs/motion.md`
 * 3.8). Undo during the fly-out brings the card back from where it is.
 */
@RunWith(RobolectricTestRunner::class)
class ReviewFlyOutTest {
    @get:Rule
    val compose = createComposeRule()

    private val first = ReviewPaymentsSamples.reviewing
    private val next = first.copy(card = first.card.copy(id = 3, payee = "Uber"), position = first.position + 1)
    private var state by mutableStateOf<ReviewPaymentsUiState>(first)
    private val events = mutableListOf<ReviewPaymentsEvent>()

    /** The top card: the one that offers the TalkBack actions. A card flying out is hidden from accessibility. */
    private fun topCard(payee: String) = hasText(payee) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)

    private fun show() {
        compose.setContent {
            AppTheme(dark = false, reducedMotion = false) {
                ReviewPaymentsScreen(
                    state = state,
                    onEvent = { event ->
                        events += event
                        // As the ViewModel does: the next card at once, and Undo brings the last one back.
                        state = when (event) {
                            is ReviewPaymentsEvent.Decide -> next
                            ReviewPaymentsEvent.Undo -> first
                            else -> state
                        }
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test fun theNextCardTakesTheTopWhileTheDecidedOneFliesOut() {
        show()
        compose.onNodeWithText("Add").performClick()
        assertEquals(listOf(ReviewPaymentsEvent.Decide(2, ReviewDecision.Add)), events)
        compose.mainClock.advanceTimeBy(FRAMES)
        compose.onNode(topCard("Uber")).assertExists()
        compose.onNode(topCard("Swiggy")).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(SETTLE)
        compose.onNodeWithText("Swiggy").assertDoesNotExist()
    }

    @Test fun undoDuringTheFlyOutBringsTheCardBackFromWhereItIs() {
        show()
        val rest = compose.onNode(topCard("Swiggy")).getUnclippedBoundsInRoot().left
        compose.onNodeWithText("Add").performClick()
        compose.mainClock.advanceTimeBy(FRAMES)
        compose.onNodeWithText("Undo").performClick()
        assertEquals(listOf(ReviewPaymentsEvent.Decide(2, ReviewDecision.Add), ReviewPaymentsEvent.Undo), events)
        compose.mainClock.advanceTimeBy(FRAME)
        // Part way out, where the fly-out had it: not 1.5 widths off the side, where a card that was already out starts.
        val out = (compose.onNode(topCard("Swiggy")).getUnclippedBoundsInRoot().left - rest).value
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val width = (root.right - root.left).value
        assertTrue("back from $out dp out of $width", out > 10f && out < width)
        compose.mainClock.advanceTimeBy(SETTLE)
        assertEquals(rest.value, compose.onNode(topCard("Swiggy")).getUnclippedBoundsInRoot().left.value, 0.5f)
        compose.onNode(topCard("Uber")).assertDoesNotExist()
    }

    private companion object {
        const val FRAME = 16L
        const val FRAMES = 48L
        const val SETTLE = 1_000L
    }
}
