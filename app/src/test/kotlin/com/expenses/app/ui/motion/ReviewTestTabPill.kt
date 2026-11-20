package com.expenses.app.ui.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.shell.LocalTabMemory
import com.expenses.app.ui.shell.MainScaffold
import com.expenses.app.ui.shell.TabMemory
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Review finding: the bottom-bar pill must carry on from where it is when a second tab is tapped mid-slide (owner rule
 * 1: "an interruption continues smoothly instead of jumping or restarting"; it names the nav pill). Each tab draws its
 * own dock and [TabMemory] keeps only the last tab, so the next dock starts with the pill at rest on that tab: it jumps.
 * Each dock is drawn here as the app draws it, one per tab ([key] on the active tab).
 */
@RunWith(RobolectricTestRunner::class)
class ReviewTestTabPill {
    @get:Rule
    val compose = createComposeRule()

    private var active by mutableStateOf(MainTab.Home)

    /** Shows [tab]'s screen, as a tap on its tab does (a new destination, so a new dock). */
    private fun show(tab: MainTab) {
        active = tab
        Snapshot.sendApplyNotifications()
    }

    /** The Groups tab's width in dp: 48 as an icon, the pill's width when active, in between while the pill slides. */
    private fun groupsWidth(): Float = with(compose.density) {
        compose.onNodeWithContentDescription("Groups").fetchSemanticsNode().size.width.toDp().value
    }

    @Test fun aTabTappedWhileThePillSlidesCarriesThePillOnFromWhereItIs() {
        val memory = TabMemory()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(dark = false, reducedMotion = false) {
                CompositionLocalProvider(LocalTabMemory provides memory) {
                    key(active) {
                        MainScaffold(active = active, onSelect = {}) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE)
        // Groups at rest, as the active tab: the full pill.
        show(MainTab.Groups)
        compose.mainClock.advanceTimeBy(SETTLE)
        val full = groupsWidth()
        show(MainTab.Home)
        compose.mainClock.advanceTimeBy(SETTLE)

        // Home to Groups, and Activity tapped while the pill is on its way.
        show(MainTab.Groups)
        compose.mainClock.advanceTimeBy(MID_SLIDE)
        val midSlide = groupsWidth()
        assertTrue("the pill should be mid-slide: $midSlide of $full dp", midSlide < full - 8f && midSlide > 52f)
        show(MainTab.Activity)
        compose.mainClock.advanceTimeBy(FRAME)
        assertEquals("the Groups tab jumped from mid-slide to the full pill", midSlide, groupsWidth(), 4f)
    }

    private companion object {
        const val FRAME = 16L
        const val MID_SLIDE = 64L
        const val SETTLE = 1_000L
    }
}
