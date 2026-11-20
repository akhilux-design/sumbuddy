package com.expenses.app.ui.screens.signin

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.expenses.app.ui.theme.AppHaptics
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Haptic
import com.expenses.app.ui.theme.LocalAppHaptics
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** `haptic/reject` plays for each refused tap, also when the banner it shows was already there (design 2.7). */
@RunWith(RobolectricTestRunner::class)
class ActionProblemHapticTest {
    @get:Rule
    val compose = createComposeRule()

    private val played = mutableListOf<Haptic>()
    private val recorder = object : AppHaptics {
        override fun play(haptic: Haptic) {
            played += haptic
        }
    }
    private var problem by mutableStateOf<ActionProblem?>(null)
    private var count by mutableIntStateOf(0)

    private fun show() {
        compose.setContent {
            AppTheme(dark = false) {
                CompositionLocalProvider(LocalAppHaptics provides recorder) { ActionProblemHaptic(problem, count) }
            }
        }
        compose.waitForIdle()
    }

    private fun refused(with: ActionProblem) {
        problem = with
        count += 1
        compose.waitForIdle()
    }

    @Test fun eachRefusedTapRejectsEvenWithTheSameBanner() {
        show()
        refused(ActionProblem.OFFLINE)
        refused(ActionProblem.OFFLINE)
        assertEquals(listOf(Haptic.Reject, Haptic.Reject), played)
    }

    @Test fun aBannerGoingAwayPlaysNothing() {
        show()
        refused(ActionProblem.FAILED)
        problem = null
        compose.waitForIdle()
        assertEquals(listOf(Haptic.Reject), played)
    }

    @Test fun aBannerAlreadyThereWhenShownPlaysNothing() {
        problem = ActionProblem.OFFLINE
        count = 3
        show()
        assertEquals(emptyList<Haptic>(), played)
    }
}
