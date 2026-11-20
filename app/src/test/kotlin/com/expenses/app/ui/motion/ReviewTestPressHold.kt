package com.expenses.app.ui.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.components.core.RaisedSurface
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Review finding: a 3D face whose own tap disables it (`AppButton(enabled = !state.saving)` on Save, Add, Join...)
 * must still show the whole press (`design/spec/motion.md`, "3D press and release": a press shows for at least
 * 100 ms, and a spring never jumps). Today `rememberPressDrop` restarts its effect when `enabled` turns false and
 * snaps the face back up in the next frame.
 */
@RunWith(RobolectricTestRunner::class)
class ReviewTestPressHold {
    @get:Rule
    val compose = createComposeRule()

    private var enabled by mutableStateOf(true)

    /** A face that, like a Save button, is disabled by its own tap. */
    private fun faceDisabledByItsTap(reducedMotion: Boolean) {
        compose.setContent {
            AppTheme(dark = false, reducedMotion = reducedMotion) {
                RaisedSurface(
                    faceColor = AppTheme.colors.brandPrimary,
                    edgeColor = AppTheme.colors.brandEdge,
                    onClick = { enabled = false },
                    enabled = enabled,
                ) {
                    Box(Modifier.size(120.dp, 44.dp).testTag("face"))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun faceTop(): Float = with(compose.density) {
        compose.onNodeWithTag("face", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y.toDp().value
    }

    @Test fun aFaceDisabledByItsOwnTapStillShowsThePressAndSpringsBack() {
        faceDisabledByItsTap(reducedMotion = false)
        val rest = faceTop()
        compose.onNodeWithTag("face", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(80)
        assertTrue("the face should still be pressed 80 ms after the tap, it is at ${faceTop() - rest} dp", faceTop() > rest + 2f)
        compose.mainClock.advanceTimeBy(600)
        assertEquals(rest, faceTop(), 0.05f)
    }

    @Test fun withRemoveAnimationsAFaceDisabledByItsOwnTapStillHoldsThePress() {
        faceDisabledByItsTap(reducedMotion = true)
        val rest = faceTop()
        compose.onNodeWithTag("face", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(50)
        assertEquals("the 100 ms hold stays with Remove animations on", rest + 4f, faceTop(), 0.2f)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(rest, faceTop(), 0.05f)
    }
}
