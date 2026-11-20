package com.expenses.app.ui.motion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.screens.review.ReviewDecision
import com.expenses.app.ui.screens.review.ReviewPaymentsEvent
import com.expenses.app.ui.screens.review.ReviewPaymentsSamples
import com.expenses.app.ui.screens.review.ReviewPaymentsScreen
import com.expenses.app.ui.theme.AppHaptics
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Haptic
import com.expenses.app.ui.theme.LocalAppHaptics
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Review findings on the review swipe (`design/spec/motion.md`, "Review swipe fling"; `docs/motion.md` 2.6 and 3.8).
 */
@RunWith(RobolectricTestRunner::class)
class ReviewTestSwipe {
    @get:Rule
    val compose = createComposeRule()

    private val played = mutableListOf<Haptic>()
    private val events = mutableListOf<ReviewPaymentsEvent>()
    private val recorder = object : AppHaptics {
        override fun play(haptic: Haptic) {
            played += haptic
        }
    }

    // The amount and payee: the card's one focus stop, 20dp inside the card on each side.
    private val cardText = hasText("Swiggy") and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)

    private fun thresholdTicks() = played.filter { it == Haptic.ThresholdIn || it == Haptic.ThresholdOut }

    private fun showWithRemoveAnimations() {
        compose.setContent {
            AppTheme(dark = false, reducedMotion = true) {
                CompositionLocalProvider(LocalAppHaptics provides recorder) {
                    ReviewPaymentsScreen(state = ReviewPaymentsSamples.reviewing, onEvent = { events += it })
                }
            }
        }
    }

    private fun dp(value: Float) = with(compose.density) { value.dp.toPx() }

    /** The commit line in px: 35% of the stack, which is the text's width plus the card's 20dp padding each side. */
    private fun commitLine(): Float {
        val textWidth = compose.onNode(cardText).fetchSemanticsNode().size.width
        return (textWidth + dp(40f)) * 0.35f
    }

    /** Drags past the line (threshold-in), back to 4 dp under it (still armed, inside the 8 dp hysteresis), holds still and lets go. */
    private fun letGoInsideTheHysteresis() {
        val line = commitLine()
        compose.onNode(cardText).performTouchInput {
            val slop = viewConfiguration.touchSlop
            down(center)
            moveBy(Offset(slop + line + dp(4f), 0f), delayMillis = 100) // line + 4: arms, threshold-in
            moveBy(Offset(-dp(8f), 0f), delayMillis = 100) // line - 4: still armed
            moveBy(Offset.Zero, delayMillis = 300) // still: no fling speed
            up()
        }
    }

    /**
     * Let go 4 dp under the commit line while still armed, the card goes back to rest (under the line) but stays armed:
     * no threshold-out plays for crossing back.
     */
    @Test fun aCardLetGoInsideTheHysteresisTicksOutAsItGoesBack() {
        showWithRemoveAnimations()
        letGoInsideTheHysteresis()
        compose.runOnIdle {
            assertEquals(emptyList<ReviewPaymentsEvent>(), events)
            assertEquals(listOf(Haptic.ThresholdIn, Haptic.ThresholdOut), thresholdTicks())
        }
    }

    /** The stale `armed` of that card makes the next drag tick threshold-out on its first move, far from the line. */
    @Test fun theNextSmallDragDoesNotTickForTheLastOne() {
        showWithRemoveAnimations()
        letGoInsideTheHysteresis()
        val before = compose.runOnIdle { thresholdTicks() }
        compose.onNode(cardText).performTouchInput {
            val slop = viewConfiguration.touchSlop
            down(center)
            moveBy(Offset(slop + dp(10f), 0f), delayMillis = 100) // 10 dp: nowhere near the line
            moveBy(Offset.Zero, delayMillis = 300)
            up()
        }
        compose.runOnIdle { assertEquals(before, thresholdTicks()) }
    }

    /**
     * The decision waits for the fly-out spring (about 300 ms to settle 1 dp from 1.5 widths). If the composition goes
     * before that (configuration change, the screen disposed, or the frame clock paused in the background and the
     * process killed), the swipe is lost: the scope that would send it is cancelled.
     */
    @Test fun aDecisionIsSentEvenIfTheScreenGoesDuringTheFlyOut() {
        var shown by mutableStateOf(true)
        compose.setContent {
            AppTheme(dark = false, reducedMotion = false) {
                if (shown) ReviewPaymentsScreen(state = ReviewPaymentsSamples.reviewing, onEvent = { events += it })
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Add").performClick()
        compose.mainClock.advanceTimeBy(50)
        shown = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(listOf(ReviewPaymentsEvent.Decide(2, ReviewDecision.Add)), events)
    }

    /** Control for the test above: with the screen kept, the same tap sends the decision once the card is out. */
    @Test fun controlTheDecisionIsSentOnceTheCardIsOut() {
        compose.setContent {
            AppTheme(dark = false, reducedMotion = false) {
                ReviewPaymentsScreen(state = ReviewPaymentsSamples.reviewing, onEvent = { events += it })
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Add").performClick()
        compose.mainClock.advanceTimeBy(1_050)
        compose.waitForIdle()
        assertEquals(listOf(ReviewPaymentsEvent.Decide(2, ReviewDecision.Add)), events)
    }
}
