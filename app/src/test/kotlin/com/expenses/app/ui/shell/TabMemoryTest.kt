package com.expenses.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.theme.AppHaptics
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Haptic
import com.expenses.app.ui.theme.LocalAppHaptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A tab's content animates only when the tab was reached from another tab (`docs/motion.md` 3.6 and 5.1): it starts at
 * 96% and settles at full size. Coming back from a pushed screen (no tab remembered), showing the same tab again, or
 * with Remove animations on, it shows at once. A tap on another tab ticks; a tap on the active one does not.
 */
@RunWith(RobolectricTestRunner::class)
class TabMemoryTest {
    @get:Rule
    val compose = createComposeRule()

    private val played = mutableListOf<Haptic>()
    private val recording = object : AppHaptics {
        override fun play(haptic: Haptic) {
            played += haptic
        }
    }
    private val selected = mutableListOf<MainTab>()

    private fun show(memory: TabMemory?, active: MainTab, reducedMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(dark = false, reducedMotion = reducedMotion) {
                CompositionLocalProvider(LocalTabMemory provides memory, LocalAppHaptics provides recording) {
                    MainScaffold(active = active, onSelect = { selected += it }) {
                        Box(Modifier.fillMaxSize().testTag("content"))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(FRAME)
    }

    /** The content's left edge: 0 at full size, inward while it grows from 96% about its centre. */
    private fun contentLeft(): Dp = compose.onNodeWithTag("content").getUnclippedBoundsInRoot().left

    @Test fun fromAnotherTabTheContentGrowsInAndTheTabIsRemembered() {
        val memory = TabMemory(last = MainTab.Home)
        show(memory, active = MainTab.Groups)
        assertTrue("starts smaller: ${contentLeft()}", contentLeft().value > 1f)
        assertEquals(MainTab.Groups, memory.last)
        compose.mainClock.advanceTimeBy(SETTLE)
        assertEquals(0f, contentLeft().value, 0.5f)
    }

    @Test fun backFromAPushedScreenItShowsAtOnce() {
        val memory = TabMemory(last = null)
        show(memory, active = MainTab.Home)
        assertEquals(0f, contentLeft().value, 0.5f)
        assertEquals(MainTab.Home, memory.last)
    }

    @Test fun theSameTabAgainShowsAtOnce() {
        show(TabMemory(last = MainTab.Activity), active = MainTab.Activity)
        assertEquals(0f, contentLeft().value, 0.5f)
    }

    @Test fun withRemoveAnimationsItShowsAtOnce() {
        show(TabMemory(last = MainTab.Home), active = MainTab.You, reducedMotion = true)
        assertEquals(0f, contentLeft().value, 0.5f)
    }

    @Test fun outsideTheAppRootNothingAnimates() {
        show(memory = null, active = MainTab.Groups)
        assertEquals(0f, contentLeft().value, 0.5f)
    }

    @Test fun onlyAnotherTabTicks() {
        show(TabMemory(), active = MainTab.Home)
        compose.onNodeWithContentDescription("Groups").performClick()
        compose.onNodeWithContentDescription("Home").performClick()
        compose.mainClock.advanceTimeBy(SETTLE)
        assertEquals(listOf(MainTab.Groups, MainTab.Home), selected)
        assertEquals(listOf(Haptic.Selection), played)
    }

    @Test fun arrivingIsDecidedByTheTabBefore() {
        assertTrue(arrivesFromAnotherTab(MainTab.Home, MainTab.Groups))
        assertFalse(arrivesFromAnotherTab(MainTab.Groups, MainTab.Groups))
        assertFalse(arrivesFromAnotherTab(null, MainTab.Groups))
    }

    private companion object {
        const val FRAME = 16L
        const val SETTLE = 1_000L
    }
}
