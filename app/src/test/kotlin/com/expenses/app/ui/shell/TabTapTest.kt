package com.expenses.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A tab reached from another tab moves at once, by a tap or by Back (device check: blank frames while the navigation had
 * not resumed the new tab yet). A tab shown by a back swipe waits until the swipe is done, so its preview does not play it.
 */
@RunWith(RobolectricTestRunner::class)
class TabTapTest {
    @get:Rule
    val compose = createComposeRule()

    /** Started, not resumed: as a new destination is while its screen transition runs. */
    private val started = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun show(memory: TabMemory, active: MainTab) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(dark = false, reducedMotion = false) {
                CompositionLocalProvider(LocalTabMemory provides memory, LocalLifecycleOwner provides started) {
                    MainScaffold(active = active, onSelect = {}) { Box(Modifier.fillMaxSize().testTag("content")) }
                }
            }
        }
        compose.mainClock.advanceTimeBy(FRAME)
    }

    private fun contentLeft(): Float = compose.onNodeWithTag("content").getUnclippedBoundsInRoot().left.value

    @Test fun aTappedTabGrowsInBeforeItIsResumed() {
        val memory = TabMemory(last = MainTab.Home)
        memory.changing("home", "groups", MainTab.Home, MainTab.Groups, under = false, predictive = false)
        show(memory, MainTab.Groups)
        val first = contentLeft()
        compose.mainClock.advanceTimeBy(MOVING)
        assertTrue("the content should be growing in: $first then ${contentLeft()}", contentLeft() < first - 1f)
        assertEquals(MainTab.Groups, memory.last)
    }

    @Test fun aTabShownByBackGrowsInBeforeItIsResumed() {
        val memory = TabMemory(last = MainTab.Groups)
        memory.changing("groups", "home", MainTab.Groups, MainTab.Home, under = true, predictive = false)
        show(memory, MainTab.Home)
        val first = contentLeft()
        compose.mainClock.advanceTimeBy(MOVING)
        assertTrue("the content should be growing in: $first then ${contentLeft()}", contentLeft() < first - 1f)
        assertEquals(MainTab.Home, memory.last)
    }

    @Test fun aTabShownByABackSwipeWaitsForTheSwipeToBeDone() {
        val memory = TabMemory(last = MainTab.Groups)
        memory.changing("groups", "home", MainTab.Groups, MainTab.Home, under = true, predictive = true)
        show(memory, MainTab.Home)
        val first = contentLeft()
        compose.mainClock.advanceTimeBy(MOVING)
        assertEquals(first, contentLeft(), 0.01f)
        assertEquals(MainTab.Groups, memory.last)
        // The swipe is done: Home is on top of the back stack.
        compose.runOnUiThread {
            memory.onTop(MainTab.Home)
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(MOVING)
        assertTrue("the content should be growing in: $first then ${contentLeft()}", contentLeft() < first - 1f)
        assertEquals(MainTab.Home, memory.last)
    }

    private companion object {
        const val FRAME = 16L
        const val MOVING = 100L
    }
}
