package com.expenses.app.ui.screens.review

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
 * The commit line of the review card (`design/spec/motion.md`, "Review swipe fling"): dragging past 35% of the stack
 * ticks once (threshold-in), back under it ticks once (threshold-out), and moving about within the 8 dp under the line
 * does not tick again. The ticks play with Remove animations on too.
 */
@RunWith(RobolectricTestRunner::class)
class ReviewDragHapticsTest {
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

    @Test
    fun pastTheLineThenBackTicksInThenOutWithoutChatter() {
        compose.setContent {
            AppTheme(dark = false, reducedMotion = true) {
                CompositionLocalProvider(LocalAppHaptics provides recorder) {
                    ReviewPaymentsScreen(state = ReviewPaymentsSamples.reviewing, onEvent = { events += it })
                }
            }
        }
        val density = compose.density
        val textWidth = compose.onNode(cardText).fetchSemanticsNode().size.width
        val stackWidth = textWidth + with(density) { 40.dp.toPx() }
        val line = stackWidth * 0.35f
        fun dp(value: Float) = with(density) { value.dp.toPx() }

        compose.onNode(cardText).performTouchInput {
            val slop = viewConfiguration.touchSlop
            down(center)
            // The drag starts once past the touch slop; the card then sits at line + 4dp.
            moveBy(Offset(slop + line + dp(4f), 0f), delayMillis = 100)
            moveBy(Offset(-dp(10f), 0f), delayMillis = 100) // line - 6: still armed
            moveBy(Offset(dp(8f), 0f), delayMillis = 100) // line + 2: armed already
            moveBy(Offset(-dp(6f), 0f), delayMillis = 100) // line - 4: still armed
            moveBy(Offset(-dp(8f), 0f), delayMillis = 100) // line - 12: under the hysteresis, disarms
            moveBy(Offset(dp(4f), 0f), delayMillis = 100) // line - 8: under the line, stays disarmed
            moveBy(Offset.Zero, delayMillis = 300)
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(Haptic.ThresholdIn, Haptic.ThresholdOut), played.filter { it != Haptic.Press })
            // Let go under the line: nothing is decided.
            assertEquals(emptyList<ReviewPaymentsEvent>(), events)
        }
    }
}
