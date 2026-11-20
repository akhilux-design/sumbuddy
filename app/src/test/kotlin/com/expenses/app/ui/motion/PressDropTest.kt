package com.expenses.app.ui.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.components.core.RaisedSurface
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The 3D press of [RaisedSurface] (`design/spec/motion.md`, "3D press and release"): a same-frame tap moves the face
 * down 4 dp and back, the click is not delayed, a cancel returns with no hold, and a disabled face never moves.
 */
@RunWith(RobolectricTestRunner::class)
class PressDropTest {
    @get:Rule
    val compose = createComposeRule()

    private var clicks = 0

    private fun face(enabled: Boolean = true) {
        compose.setContent {
            AppTheme(dark = false, reducedMotion = false) {
                RaisedSurface(
                    faceColor = AppTheme.colors.brandPrimary,
                    edgeColor = AppTheme.colors.brandEdge,
                    onClick = { clicks++ },
                    enabled = enabled,
                ) {
                    Box(Modifier.size(120.dp, 44.dp).testTag("face"))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun faceNode() = compose.onNodeWithTag("face", useUnmergedTree = true)

    /** The top of the face in dp from the root. */
    private fun faceTop(): Float = with(compose.density) { faceNode().fetchSemanticsNode().positionInRoot.y.toDp().value }

    @Test fun aQuickTapShowsTheWholePressAndDoesNotDelayTheClick() {
        face()
        val rest = faceTop()
        faceNode().performClick()
        assertEquals(1, clicks)
        compose.mainClock.advanceTimeBy(100)
        assertEquals(rest + 4f, faceTop(), 0.2f)
        compose.mainClock.advanceTimeBy(400)
        assertEquals(rest, faceTop(), 0.05f)
    }

    @Test fun aCancelledPressGoesBackWithNoHold() {
        face()
        val rest = faceTop()
        faceNode().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(150)
        assertEquals(rest + 4f, faceTop(), 0.2f)
        faceNode().performTouchInput {
            moveTo(Offset(-2_000f, -2_000f))
            up()
        }
        compose.mainClock.advanceTimeBy(250)
        assertEquals(rest, faceTop(), 0.05f)
        assertEquals(0, clicks)
    }

    @Test fun aDisabledFaceNeverMoves() {
        face(enabled = false)
        val rest = faceTop()
        faceNode().performClick()
        compose.mainClock.advanceTimeBy(60)
        assertEquals(rest, faceTop(), 0f)
        assertEquals(0, clicks)
    }
}
