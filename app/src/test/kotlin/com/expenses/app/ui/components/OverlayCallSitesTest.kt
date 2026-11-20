package com.expenses.app.ui.components

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.AnimatedOverlay
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The confirm dialog and the bottom sheet as the screens show them, inside [AnimatedOverlay] (`docs/motion.md` 2.2,
 * 3.5 and 5.1): closing changes the state at once and the overlay leaves with its last content, ignoring taps while it
 * does; with Remove animations on it goes in the same frame; Back-style dismissals are guarded the same way.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayCallSitesTest {
    @get:Rule
    val compose = createComposeRule()

    private var question by mutableStateOf<String?>(null)
    private var sheet by mutableStateOf<String?>(null)
    private var confirms = 0
    private var dialogComposed = false
    private var dismisses = 0

    private fun show(reducedMotion: Boolean) {
        compose.setContent {
            AppTheme(dark = false, reducedMotion = reducedMotion) {
                AnimatedOverlay(question) { title ->
                    DisposableEffect(Unit) {
                        dialogComposed = true
                        onDispose { dialogComposed = false }
                    }
                    ChoiceDialog(
                        icon = AppIcon.Trash,
                        title = title,
                        body = null,
                        confirmLabel = "Delete",
                        dismissLabel = "Keep it",
                        onConfirm = { confirms++ },
                        onDismiss = {
                            dismisses++
                            question = null
                        },
                        overlay = this,
                    )
                }
                AnimatedOverlay(sheet) { name ->
                    BottomSheet(title = name, onDismiss = { sheet = null }, overlay = this) {
                        AppButton(label = "Turn off link", onClick = { confirms++ })
                    }
                }
            }
        }
    }

    @Test fun theDialogLeavesWithItsLastContentAndIgnoresTapsMeanwhile() {
        show(reducedMotion = false)
        question = "Delete this expense?"
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Keep it").performClick()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(FRAME * 2)
        assertEquals(1, dismisses)
        compose.onNodeWithText("Delete this expense?").assertExists()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Keep it").performClick()
        assertEquals("taps while it leaves do nothing", 0, confirms)
        assertEquals(1, dismisses)
        compose.mainClock.advanceTimeBy(SETTLE)
        compose.onNodeWithText("Delete this expense?").assertDoesNotExist()
    }

    @Test fun withRemoveAnimationsTheDialogGoesInTheSameFrame() {
        show(reducedMotion = true)
        question = "Delete this expense?"
        compose.waitForIdle()
        assertTrue(dialogComposed)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Keep it").performClick()
        Snapshot.sendApplyNotifications()
        // A few frames, far shorter than any exit (the scrim alone fades over 150 ms): the state is applied and the
        // dialog is gone.
        compose.mainClock.advanceTimeBy(FRAME * 3)
        assertEquals(1, dismisses)
        assertFalse("the dialog left the composition", dialogComposed)
    }

    @Test fun aChoiceThatKeepsTheStateKeepsTheDialog() {
        show(reducedMotion = false)
        question = "Delete this expense?"
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals(1, confirms)
        compose.onNodeWithText("Delete this expense?").assertExists()
    }

    @Test fun theSheetLeavesWithItsLastContent() {
        show(reducedMotion = false)
        sheet = "Priya Nair"
        compose.waitForIdle()
        compose.onNodeWithText("Priya Nair").assertExists()
        compose.mainClock.autoAdvance = false
        sheet = null
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(FRAME * 2)
        compose.onNodeWithText("Priya Nair").assertExists()
        compose.onNodeWithText("Turn off link").performClick()
        assertEquals("taps while it leaves do nothing", 0, confirms)
        compose.mainClock.advanceTimeBy(SETTLE)
        compose.onNodeWithText("Priya Nair").assertDoesNotExist()
    }

    private companion object {
        const val FRAME = 16L
        const val SETTLE = 1_000L
    }
}
