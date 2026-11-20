package com.expenses.app.ui.screens.review

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The swipe is never the only way: the card offers Add and Ignore to TalkBack, and the buttons do the same. */
@RunWith(RobolectricTestRunner::class)
class ReviewPaymentsAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<ReviewPaymentsEvent>()

    private fun show(reduceMotion: Boolean = true) {
        compose.setContent {
            AppTheme(dark = false) {
                ReviewPaymentsScreen(
                    state = ReviewPaymentsSamples.reviewing,
                    onEvent = { events += it },
                    reduceMotion = reduceMotion,
                )
            }
        }
    }

    // The actions sit on the node TalkBack focuses to read the payment: the merged amount and payee.
    private val card = hasText("Swiggy") and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)

    private fun runAction(label: String) {
        val actions = compose.onNode(card).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == label }.action() }
    }

    @Test
    fun cardHasAddAndIgnoreActions() {
        show()
        val labels = compose.onNode(card).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Add", "Ignore"), labels)
    }

    @Test
    fun actionsAreOnTheFocusStopThatReadsThePayment() {
        show()
        val node = compose.onNode(card, useUnmergedTree = false).fetchSemanticsNode()
        assertTrue(node.config.isMergingSemanticsOfDescendants)
        assertEquals(1, compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes().size)
    }

    @Test
    fun undoWhileTheCardFliesOutDoesNotDecideAnotherPayment() {
        compose.mainClock.autoAdvance = false
        show(reduceMotion = false)
        compose.onNodeWithText("Add").performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Undo").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // The decision is sent once the card is out; Undo during the fly-out is not taken.
        assertEquals(listOf(ReviewPaymentsEvent.Decide(2, ReviewDecision.Add)), events)
    }

    @Test
    fun addActionAddsThePayment() {
        show()
        runAction("Add")
        assertEquals(listOf(ReviewPaymentsEvent.Decide(2, ReviewDecision.Add)), events)
    }

    @Test
    fun ignoreActionIgnoresThePayment() {
        show()
        runAction("Ignore")
        assertEquals(listOf(ReviewPaymentsEvent.Decide(2, ReviewDecision.Ignore)), events)
    }

    @Test
    fun ignoreButtonIgnoresThePayment() {
        show()
        compose.onNodeWithText("Ignore").performClick()
        assertEquals(listOf(ReviewPaymentsEvent.Decide(2, ReviewDecision.Ignore)), events)
    }
}
