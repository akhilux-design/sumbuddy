package com.expenses.app.ui.motion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A result screen keeps its page while it leaves (Undo on Expense added and Split added, Remove on the expense, Gone on
 * Balance cleared): one screen never shows empty canvas between two screens.
 */
@RunWith(RobolectricTestRunner::class)
class KeptWhileLeavingTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf("loading")
    private var leaving by mutableStateOf(false)
    private var drawn: String? = null

    private fun show() {
        compose.setContent { drawn = keptWhileLeaving(state, leaving) { it.startsWith("shown") } }
        compose.waitForIdle()
    }

    @Test fun whileLeavingItDrawsTheLastShownState() {
        show()
        state = "shown 1"
        compose.waitForIdle()
        state = "shown 2"
        compose.waitForIdle()
        state = "undone"
        leaving = true
        compose.waitForIdle()
        assertEquals("shown 2", drawn)
    }

    @Test fun otherwiseItDrawsTheState() {
        show()
        assertEquals("loading", drawn)
        state = "shown"
        compose.waitForIdle()
        state = "gone"
        compose.waitForIdle()
        assertEquals("gone", drawn)
    }

    @Test fun leavingBeforeAnythingShowedDrawsTheState() {
        show()
        state = "undone"
        leaving = true
        compose.waitForIdle()
        assertEquals("undone", drawn)
    }
}
