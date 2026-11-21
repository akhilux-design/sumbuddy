package com.expenses.app.ui.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A loading state that data replaces quickly does not fade through (`docs/motion.md` 3.10): before its placeholders
 * could show (200 ms), the content takes its place at once, so the screen is never faded out to nothing and back.
 * After that, and between two real states, the swap fades through (out 90 ms, in 160 ms after 90 ms).
 */
@RunWith(RobolectricTestRunner::class)
class FadeThroughContentTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf("loading")

    private fun show(markLoading: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FadeThroughContent(
                targetState = state,
                isLoading = if (markLoading) ({ it == "loading" }) else ({ false }),
            ) { shown -> Box(Modifier.size(10.dp).testTag(shown)) }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    /** Swaps to [to], then lets [millis] of frames pass. */
    private fun swapTo(to: String, millis: Long = 32) {
        state = to
        compose.mainClock.advanceTimeBy(millis)
    }

    @Test fun dataThatComesQuicklyReplacesTheLoadingStateAtOnce() {
        show()
        compose.mainClock.advanceTimeBy(100)
        swapTo("shown")
        compose.onNodeWithTag("loading").assertDoesNotExist()
        compose.onNodeWithTag("shown").assertExists()
    }

    @Test fun aLoadingStateThatShowedItsPlaceholdersFadesThrough() {
        show()
        compose.mainClock.advanceTimeBy(300)
        swapTo("shown")
        compose.onNodeWithTag("loading").assertExists() // still fading out
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("loading").assertDoesNotExist()
    }

    @Test fun twoRealStatesFadeThrough() {
        show()
        swapTo("shown")
        compose.mainClock.advanceTimeBy(400)
        swapTo("sorted")
        compose.onNodeWithTag("shown").assertExists() // still fading out
    }

    @Test fun withoutALoadingStateAQuickSwapFadesThroughAsBefore() {
        // What every swap did before 2026-11-22: the frame faded out and the content in, so the screen went nearly blank.
        show(markLoading = false)
        compose.mainClock.advanceTimeBy(100)
        swapTo("shown")
        compose.onNodeWithTag("loading").assertExists()
    }
}
