package com.expenses.app.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Loading placeholders (`design/spec/motion.md`, "Placeholder pulse"; `docs/motion.md` 5.1): they are in the tree at
 * once, fade in only after 200 ms and pulse; with Remove animations on they show after the same 200 ms, at once, and
 * stay still.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceholderMotionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var motion: PlaceholderMotion

    private fun show(reducedMotion: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(dark = false) {
                CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                    motion = rememberPlaceholderMotion()
                    PlaceholderCard(description = "Loading groups")
                }
            }
        }
    }

    @Test fun withRemoveAnimationsItWaitsTheSameDelayThenShowsAtOnceAndStaysStill() {
        show(reducedMotion = true)
        // In the tree at once, so screen readers hear "Loading groups", but not drawn: data that comes within 200 ms
        // replaces it before it ever shows, also with Remove animations on (no flash of the loading state).
        compose.onNodeWithContentDescription("Loading groups").assertExists()
        compose.mainClock.advanceTimeBy(16L)
        assertEquals(0f, motion.shown.value, 0f)
        compose.mainClock.advanceTimeBy(150L)
        assertEquals("still hidden inside the 200 ms delay", 0f, motion.shown.value, 0f)
        compose.mainClock.advanceTimeBy(100L)
        assertEquals("then shown at once, with no fade", 1f, motion.shown.value, 0f)
        listOf(16L, 450L, 900L, 1_350L).forEach { step ->
            compose.mainClock.advanceTimeBy(step)
            assertEquals(1f, motion.shown.value, 0f)
            assertEquals(1f, motion.pulse.value, 0f)
        }
    }

    @Test fun otherwiseItIsInTheTreeAtOnceButShowsOnlyAfterTheDelay() {
        show(reducedMotion = false)
        compose.onNodeWithContentDescription("Loading groups").assertExists()
        compose.mainClock.advanceTimeBy(150L)
        assertEquals("still hidden inside the 200 ms delay", 0f, motion.shown.value, 0.01f)
        compose.mainClock.advanceTimeBy(400L)
        assertEquals(1f, motion.shown.value, 0.01f)
    }

    @Test fun itPulsesBetweenFullAndFiftyFivePercent() {
        show(reducedMotion = false)
        compose.mainClock.advanceTimeBy(16L)
        compose.mainClock.advanceTimeBy(900L)
        assertTrue("dims to 55%: ${motion.pulse.value}", motion.pulse.value < 0.6f)
        compose.mainClock.advanceTimeBy(900L)
        assertTrue("back to full: ${motion.pulse.value}", motion.pulse.value > 0.95f)
    }
}
