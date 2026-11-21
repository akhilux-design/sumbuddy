package com.expenses.app.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.expenses.app.ui.components.MainTab
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Review of SpringBack (7b01210): what the delayed completion does to the back stack, kept as regression tests. While a
 * settle runs the NavController still has the screen swiped away on top (the settle exists because the NavHost's pop
 * starts its own linear finish), so a touch, or a navigation made by code through the app's helpers, ends it first.
 */
@RunWith(RobolectricTestRunner::class)
class SpringBackReviewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private val system = DirectNavigationEventInput()

    private fun host(relay: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.navigationEventDispatcher.addInput(system) }
        compose.setContent {
            nav = rememberNavController()
            val transitions = rememberNavTransitions(signedOut = { false })
            val graph: @Composable () -> Unit = {
                NavHost(
                    navController = nav,
                    startDestination = Home,
                    enterTransition = { transitions.enter(this, pop = false) },
                    exitTransition = { transitions.exit(this, pop = false) },
                    popEnterTransition = { transitions.enter(this, pop = true) },
                    popExitTransition = { transitions.exit(this, pop = true) },
                    predictivePopEnterTransition = { edge -> transitions.predictiveEnter(this, edge) },
                    predictivePopExitTransition = { edge -> transitions.predictiveExit(this, edge) },
                ) {
                    composable<Home> { Screen("home") }
                    composable<WhatWeHaveRead> { Screen("push") }
                    composable<ReviewPayments> { Screen("other") }
                    composable<Groups> { Screen("groups") }
                }
            }
            if (relay) {
                SpringBack(
                    settles = {
                        transitions.previewsBack(nav.currentBackStackEntry?.destination, nav.previousBackStackEntry?.destination)
                    },
                ) { graph() }
            } else {
                graph()
            }
        }
        compose.mainClock.advanceTimeBy(FRAME)
    }

    private fun go(route: Any) {
        compose.runOnUiThread { nav.navigate(route) }
        compose.mainClock.advanceTimeBy(SETTLE)
    }

    /** A back swipe from the left edge to [to], 0.04 a frame, then the finger lets go (committed). */
    private fun swipeBack(to: Float) {
        compose.runOnUiThread { system.backStarted(NavigationEvent(progress = 0f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
        compose.mainClock.advanceTimeByFrame()
        var p = 0f
        while (p < to - 1e-4f) {
            p = minOf(to, p + 0.04f)
            val progress = p
            compose.runOnUiThread { system.backProgressed(NavigationEvent(progress = progress, swipeEdge = NavigationEvent.EDGE_LEFT)) }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.runOnUiThread { system.backCompleted() }
    }

    private fun current(): String = name(nav.currentDestination!!)

    /** The screens on the back stack, bottom first. */
    private fun stack(): List<String> = nav.currentBackStack.value.map { name(it.destination) }.filter { it != "?" }

    private fun name(destination: androidx.navigation.NavDestination): String = destination.let {
        when {
            it.hasRoute(Home::class) -> "home"
            it.hasRoute(WhatWeHaveRead::class) -> "push"
            it.hasRoute(ReviewPayments::class) -> "other"
            it.hasRoute(Groups::class) -> "groups"
            else -> "?"
        }
    }

    /** A finger touches the screen coming back; [lift] lifts it, when its click would land. */
    private fun touch() = compose.onRoot().performTouchInput { down(center) }
    private fun lift() = compose.onRoot().performTouchInput { up() }

    /**
     * A person swipes back from a pushed screen to Home and, as Home slides back in, taps a row on it (here: opens
     * Review payments). Before SpringBack the NavHost popped at once on commit, so the tap opened Review payments.
     * The finger goes down about 64 ms into the settle, and its click navigates when it lifts.
     */
    @Test fun aTapOnTheScreenComingBackWhileTheSwipeSettlesIsKept() {
        host(relay = true)
        go(WhatWeHaveRead)
        swipeBack(to = 0.4f)
        compose.mainClock.advanceTimeBy(FRAME * 4) // about 64 ms into the settle
        touch()
        compose.mainClock.advanceTimeBy(FRAME * 4)
        lift()
        compose.runOnUiThread { nav.navigate(ReviewPayments) }
        compose.mainClock.advanceTimeBy(SETTLE * 2)
        assertEquals("the screen the tap opened", "other", current())
        assertEquals("the screen swiped away is gone", listOf("home", "other"), stack())
    }

    /** A navigation made by code through the app's helpers (openTab, rememberNavigateOnce) ends the settle first. */
    @Test fun aNavigationByCodeWhileTheSwipeSettlesIsKept() {
        host(relay = true)
        go(WhatWeHaveRead)
        swipeBack(to = 0.4f)
        compose.mainClock.advanceTimeBy(FRAME * 4)
        compose.runOnUiThread {
            endBackSettles()
            nav.navigate(ReviewPayments)
        }
        compose.mainClock.advanceTimeBy(SETTLE * 2)
        assertEquals("other", current())
        assertEquals(listOf("home", "other"), stack())
    }

    /** The same without the relay: the tap is kept. */
    @Test fun withoutTheRelayTheTapIsKept() {
        host(relay = false)
        go(WhatWeHaveRead)
        swipeBack(to = 0.4f)
        compose.mainClock.advanceTimeBy(FRAME * 4)
        compose.runOnUiThread { nav.navigate(ReviewPayments) }
        compose.mainClock.advanceTimeBy(SETTLE * 2)
        assertEquals("other", current())
    }

    /**
     * While the swipe settles, the back stack still has the screen the person swiped away; the first touch takes it
     * off at once, before the touch's click or drag can act on the back stack.
     */
    @Test fun theSwipedScreenLeavesTheBackStackWhenTheScreenIsTouched() {
        host(relay = true)
        go(WhatWeHaveRead)
        swipeBack(to = 0.4f)
        compose.mainClock.advanceTimeBy(FRAME * 2)
        assertEquals("still settling", "push", current())
        touch()
        assertEquals("home", current())
        assertEquals(listOf("home"), stack())
        lift()
        compose.mainClock.advanceTimeBy(SETTLE)
        assertEquals("home", current())
    }

    /**
     * Home > a pushed screen, swipe back, and while it settles tap Groups in the dock (MainScaffold's onSelect is
     * openTab, with no navigate-once guard). openTab pops the swiped-away screen with saveState, so it becomes Home's
     * saved stack: tapping Home later brings back the screen the person had swiped away.
     */
    @Test fun aDockTapWhileTheSwipeSettlesDoesNotSaveTheSwipedScreenIntoHome() {
        host(relay = true)
        go(WhatWeHaveRead)
        swipeBack(to = 0.4f)
        compose.mainClock.advanceTimeBy(FRAME * 4)
        compose.runOnUiThread { nav.openTab(MainTab.Groups) }
        compose.mainClock.advanceTimeBy(SETTLE * 2)
        assertEquals("groups", current())
        compose.runOnUiThread { nav.openTab(MainTab.Home) }
        compose.mainClock.advanceTimeBy(SETTLE)
        assertEquals("Home, not the screen swiped away", "home", current())
    }

    /** The same without the relay: Home comes back alone. */
    @Test fun withoutTheRelayADockTapAfterTheSwipeKeepsHomeAlone() {
        host(relay = false)
        go(WhatWeHaveRead)
        swipeBack(to = 0.4f)
        compose.mainClock.advanceTimeBy(FRAME * 4)
        compose.runOnUiThread { nav.openTab(MainTab.Groups) }
        compose.mainClock.advanceTimeBy(SETTLE * 2)
        compose.runOnUiThread { nav.openTab(MainTab.Home) }
        compose.mainClock.advanceTimeBy(SETTLE)
        assertEquals("home", current())
    }

    private companion object {
        const val FRAME = 16L
        const val SETTLE = 1_000L
    }
}

@Composable
private fun Screen(tag: String) {
    Box(Modifier.fillMaxSize().testTag(tag))
}
