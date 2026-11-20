package com.expenses.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The grey containers tell the buttons inside them that they sit on `color/bg/surface-raised`, so a Secondary
 * button there takes `color/bg/on-raised` and does not look disabled (Turn off link on 02-everyday/130 and 131).
 */
@RunWith(RobolectricTestRunner::class)
class OnRaisedSurfaceTest {
    @get:Rule
    val compose = createComposeRule()

    private var seen: Boolean? = null

    @Composable
    private fun Probe() {
        val onRaised = LocalOnRaisedSurface.current
        SideEffect { seen = onRaised }
    }

    @Test fun aBottomSheetMarksItsContent() {
        compose.setContent { AppTheme(dark = false) { BottomSheet(title = "Priya Nair", onDismiss = {}) { Probe() } } }
        compose.waitForIdle()

        assertEquals(true, seen)
    }

    @Test fun aDialogCardMarksItsContent() {
        compose.setContent { AppTheme(dark = true) { DialogCard(title = "Rename account", onDismiss = {}) { Probe() } } }
        compose.waitForIdle()

        assertEquals(true, seen)
    }

    @Test fun aScreenOnTheCanvasDoesNot() {
        compose.setContent { AppTheme(dark = false) { Probe() } }
        compose.waitForIdle()

        assertEquals(false, seen)
    }
}
