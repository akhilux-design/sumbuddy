package com.expenses.app.ui.screens.home

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.expenses.app.ui.components.LocalOnRaisedSurface
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Home's payment card and tip are grey (`color/bg/surface-raised`): Split and Not now on them take
 * `color/bg/on-raised`, so they do not look disabled (02-everyday/00 and 02).
 */
@RunWith(RobolectricTestRunner::class)
class HighlightCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun theCardMarksItsButtonsAsOnARaisedSurface() {
        var seen: Boolean? = null
        compose.setContent {
            AppTheme(dark = false) {
                HighlightCard {
                    val onRaised = LocalOnRaisedSurface.current
                    SideEffect { seen = onRaised }
                }
            }
        }
        compose.waitForIdle()

        assertEquals(true, seen)
    }
}
