package com.expenses.app.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An overlay keeps its last content while it leaves and ignores taps meanwhile; with Remove animations on it goes in
 * the same frame; a Save refused by state keeps it (`docs/motion.md` 3.5 and 5.1).
 */
@RunWith(RobolectricTestRunner::class)
class AnimatedOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    private var shown by mutableStateOf<String?>(null)
    private var saves = 0
    private var cancels = 0

    @OptIn(ExperimentalAnimationApi::class)
    private fun show(reducedMotion: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                AnimatedOverlay(shown) { title ->
                    transition.AnimatedVisibility(visible = { it }, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                        Column(Modifier.ignoreTouchWhileLeaving()) {
                            BasicText(title, Modifier.testTag("title"))
                            // A refused Save keeps the caller's state, so the overlay stays.
                            BasicText("Save", Modifier.size(48.dp).clickable(onClick = guard { saves++ }))
                            BasicText("Cancel", Modifier.size(48.dp).clickable(onClick = guard { cancels++; shown = null }))
                        }
                    }
                }
            }
        }
        shown = "Rename account"
        compose.waitForIdle()
        compose.onNodeWithText("Rename account").assertExists()
    }

    @Test fun nothingShowsWithoutAValue() {
        compose.setContent { AnimatedOverlay<String>(null) { BasicText(it, Modifier.testTag("title")) } }
        compose.onNodeWithTag("title").assertDoesNotExist()
    }

    @Test fun theLastContentStaysWhileItLeavesAndTapsAreIgnored() {
        show(reducedMotion = false)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancels)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(32)

        // Leaving: the last title is still drawn, and neither button reacts.
        compose.onNodeWithText("Rename account").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, saves)
        assertEquals(1, cancels)

        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("title").assertDoesNotExist()
    }

    @Test fun aValueThatComesBackWhileLeavingKeepsTheOverlay() {
        show(reducedMotion = false)
        clearAndRunOneFrame()
        compose.mainClock.advanceTimeBy(48)
        shown = "Rename account"
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Rename account").assertExists()
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, saves)
    }

    /** Clears the overlay's value and runs exactly one frame. */
    private fun clearAndRunOneFrame() {
        compose.mainClock.autoAdvance = false
        shown = null
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test fun withRemoveAnimationsItGoesInTheSameFrame() {
        show(reducedMotion = true)
        clearAndRunOneFrame()
        compose.onNodeWithTag("title").assertDoesNotExist()
    }

    @Test fun withMotionItIsStillThereAfterOneFrame() {
        show(reducedMotion = false)
        clearAndRunOneFrame()
        compose.onNodeWithTag("title").assertExists()
    }

    @Test fun aSaveRefusedByStateKeepsTheOverlay() {
        show(reducedMotion = false)
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(1, saves)
        compose.onNodeWithText("Rename account").assertExists()
    }
}
