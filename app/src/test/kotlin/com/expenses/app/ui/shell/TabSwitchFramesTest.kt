package com.expenses.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.nav.Groups
import com.expenses.app.ui.nav.Home
import com.expenses.app.ui.nav.WhatWeHaveRead
import com.expenses.app.ui.nav.mainTab
import com.expenses.app.ui.nav.rememberNavTransitions
import com.expenses.app.ui.nav.rememberNavigateOnce
import com.expenses.app.ui.theme.AppTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Device check: on a bottom-bar tap from Home to Groups the old tab vanished at once and frames showed only the canvas
 * (and the dock, its pill still on Home) before Groups faded in. One screen never shows empty canvas between two
 * screens (`design/spec/motion.md`, "Tab change"): every frame from the tap to the settle shows the old tab's content,
 * the new one's, or both crossfading, and the pill is on its way in the first frame after the tap.
 *
 * The NavHost is wired as the app wires it (the app's transitions, `mainTab`, [TabMemory]); each screen is one solid
 * colour, and the middle of the screen is read from the drawn frame, one frame at a time with the clock held.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class TabSwitchFramesTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    /** The Groups tab's [rememberNavigateOnce], as its screen would use it for a tap. */
    private var groupsOnce: ((() -> Unit) -> Unit)? = null

    private fun host(reducedMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(dark = false, reducedMotion = reducedMotion) {
                // As AppRoot makes it: the pill moves in a scope that outlives each tab.
                val scope = rememberCoroutineScope()
                val memory = remember { TabMemory(scope = scope) }
                CompositionLocalProvider(LocalTabMemory provides memory) {
                    nav = rememberNavController()
                    val transitions = rememberNavTransitions(signedOut = { false })
                    NavHost(
                        navController = nav,
                        startDestination = Home,
                        enterTransition = { transitions.enter(this, pop = false) },
                        exitTransition = { transitions.exit(this, pop = false) },
                        popEnterTransition = { transitions.enter(this, pop = true) },
                        popExitTransition = { transitions.exit(this, pop = true) },
                    ) {
                        mainTab<Home>(MainTab.Home, nav) { Fill(HOME) }
                        mainTab<Groups>(MainTab.Groups, nav) {
                            groupsOnce = rememberNavigateOnce()
                            Fill(GROUPS)
                        }
                        composable<WhatWeHaveRead> { Fill(PUSHED) }
                    }
                    FollowTabs(nav, memory)
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE)
    }

    @Composable
    private fun Fill(color: Color) {
        Box(Modifier.fillMaxSize().background(color))
    }

    /** How much of the canvas shows in the middle of the screen: 0 when a screen covers it, 1 when nothing does. */
    // Every screen here has no green; the light canvas is almost white.
    private fun canvasShowing(): Float = middle().green / CANVAS_GREEN

    /** The colour in the middle of the screen. */
    private fun middle(): Color {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 3]
    }

    private fun assertShows(what: String, color: Color) {
        val shown = middle()
        assertTrue("$what should show: $shown", abs(shown.red - color.red) + abs(shown.blue - color.blue) < 0.05f)
    }

    /** The Groups tab's width in the dock, in px: 48 dp as an icon, wider as the pill reaches it. */
    private fun groupsWidth(): Int =
        compose.onAllNodesWithContentDescription("Groups").fetchSemanticsNodes().first().size.width

    /** Taps [tab] in the dock on top (the last one drawn), as a finger would. */
    private fun tap(tab: String) {
        val docks = compose.onAllNodesWithContentDescription(tab)
        docks[docks.fetchSemanticsNodes().size - 1].performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Runs [frames] frames and returns how much canvas showed in each. */
    private fun frames(frames: Int): List<Float> = List(frames) {
        compose.mainClock.advanceTimeByFrame()
        canvasShowing()
    }

    private fun assertNoBlankFrame(what: String, shown: List<Float>) {
        val worst = shown.max()
        assertTrue("$what: canvas showed in a frame (${shown.joinToString { "%.2f".format(it) }})", worst < MAX_CANVAS)
    }

    @Test fun aTapOnAnotherTabNeverShowsTheCanvasAlone() {
        host()
        assertTrue(canvasShowing() < MAX_CANVAS)
        tap("Groups")
        assertNoBlankFrame("Home to Groups", frames(FRAMES))
        assertShows("Groups", GROUPS)
        tap("Home")
        assertNoBlankFrame("Groups to Home", frames(FRAMES))
        assertShows("Home", HOME)
    }

    @Test fun backAndForthQuicklyNeverShowsTheCanvasAlone() {
        host()
        tap("Groups")
        val shown = frames(3).toMutableList()
        tap("Home")
        shown += frames(FRAMES)
        assertNoBlankFrame("Home to Groups and back at once", shown)
        assertShows("Home", HOME)
    }

    @Test fun thePillMovesInTheFirstFrameAfterTheTap() {
        host()
        val before = groupsWidth()
        tap("Groups")
        compose.mainClock.advanceTimeByFrame()
        val first = groupsWidth()
        assertTrue("the pill did not move in the first frame: $before then $first px", first > before)
    }

    @Test fun backBetweenTabsNeverShowsTheCanvasAlone() {
        host()
        tap("Groups")
        compose.mainClock.advanceTimeBy(SETTLE)
        compose.runOnUiThread { nav.popBackStack() }
        assertNoBlankFrame("Back from Groups to Home", frames(FRAMES))
        assertShows("Home", HOME)
    }

    @Test fun backFromAPushedScreenToATabNeverShowsTheCanvasAlone() {
        host()
        compose.runOnUiThread { nav.navigate(WhatWeHaveRead) }
        compose.mainClock.advanceTimeBy(SETTLE)
        compose.runOnUiThread { nav.popBackStack() }
        assertNoBlankFrame("Back from a pushed screen to Home", frames(FRAMES))
        assertShows("Home", HOME)
    }

    @Test fun withRemoveAnimationsTheTabsSwapAtOnce() {
        host(reducedMotion = true)
        tap("Groups")
        val shown = frames(3)
        assertNoBlankFrame("Remove animations", shown)
        assertShows("Groups, at once", GROUPS)
    }

    /**
     * The NavHost resumes the new tab only once the old one has gone (the old one stays drawn under the new page), and
     * a tap is never held up by an animation (`docs/motion.md` 3.8): a tab navigates as soon as it is on top, once.
     */
    @Test fun aTabTakesATapAsSoonAsItShows() {
        host()
        tap("Groups")
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread {
            val once = groupsOnce!!
            once { nav.navigate(WhatWeHaveRead) }
            once { nav.navigate(WhatWeHaveRead) }
        }
        val pushed = nav.currentBackStack.value.count { it.destination.hasRoute<WhatWeHaveRead>() }
        assertEquals("one tap on Groups right after it showed should open the next screen once", 1, pushed)
    }

    private companion object {
        val HOME = Color(0xFFFF0000)
        val GROUPS = Color(0xFF0000FF)
        val PUSHED = Color(0xFF000000)

        /** Green of `color/bg/canvas` in the light theme (#F5F7F5). */
        const val CANVAS_GREEN = 0xF7 / 255f

        /** At most this much canvas may show in a frame (a crossfade's blend shows a little). */
        const val MAX_CANVAS = 0.1f
        const val FRAMES = 30
        const val SETTLE = 1_000L
    }
}
