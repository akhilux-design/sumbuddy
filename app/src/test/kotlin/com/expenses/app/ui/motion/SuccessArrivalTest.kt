package com.expenses.app.ui.motion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.expenses.app.ui.screens.payment.ExpenseAddedScreen
import com.expenses.app.ui.screens.payment.PaymentSamples
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
 * The success arrival (`design/spec/motion.md`, "Success"): Expense added plays the confirm haptic exactly once, with
 * its sticker pop, and not again when the screen is rebuilt from saved state (rotation, process restore).
 */
@RunWith(RobolectricTestRunner::class)
class SuccessArrivalTest {
    @get:Rule
    val compose = createComposeRule()

    private val played = mutableListOf<Haptic>()

    private val recorder = object : AppHaptics {
        override fun play(haptic: Haptic) {
            played += haptic
        }
    }

    private fun arrive(reducedMotion: Boolean) {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppTheme(dark = false, reducedMotion = reducedMotion) {
                CompositionLocalProvider(LocalAppHaptics provides recorder) {
                    ExpenseAddedScreen(state = PaymentSamples.expenseAdded, onEvent = {})
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(Haptic.Confirm), played) }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(Haptic.Confirm), played) }
    }

    @Test
    fun confirmPlaysOnceAcrossRecreation() = arrive(reducedMotion = false)

    @Test
    fun confirmPlaysOnceWithRemoveAnimationsOn() = arrive(reducedMotion = true)

    @Test
    fun confirmWaitsForThePopAbout100Milliseconds() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(dark = false, reducedMotion = false) {
                CompositionLocalProvider(LocalAppHaptics provides recorder) {
                    ExpenseAddedScreen(state = PaymentSamples.expenseAdded, onEvent = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { assertEquals(emptyList<Haptic>(), played) }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { assertEquals(listOf(Haptic.Confirm), played) }
    }
}
