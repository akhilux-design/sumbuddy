package com.expenses.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.expenses.app.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The NavHost's transitions as wired by [rememberNavTransitions] (`docs/motion.md` 3.2): a push slides in from 30 dp, a
 * modal rises over a still screen, tab to tab does not move, a signed-out gate only fades, and with Remove animations
 * on nothing moves. Positions are read from the tagged screens with the test clock held.
 */
@RunWith(RobolectricTestRunner::class)
class NavTransitionsTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun host(reducedMotion: Boolean = false, signedOut: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                nav = rememberNavController()
                val transitions = rememberNavTransitions(signedOut = { signedOut })
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
                    composable<Groups> { Screen("groups") }
                    composable<WhatWeHaveRead> { Screen("push") }
                    composable<SignIn> { Screen("modal") }
                    composable<JoinGroup> { Screen("gate") }
                }
            }
        }
        compose.mainClock.advanceTimeBy(FRAME)
    }

    private fun go(route: Any) {
        compose.runOnUiThread { nav.navigate(route) }
        // Two frames: the navigation recomposes, then the transition starts.
        compose.mainClock.advanceTimeBy(FRAME * 2)
    }

    private fun left(tag: String): Dp = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().left

    private fun top(tag: String): Dp = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top

    @Test fun aPushSlidesInFromThirtyDpAndSettles() {
        host()
        go(WhatWeHaveRead)
        val start = left("push")
        assertTrue("starts to the end: $start", start > 1.dp && start <= 30.dp)
        compose.mainClock.advanceTimeBy(SETTLE)
        assertEquals(0f, left("push").value, 0.5f)
    }

    @Test fun backSlidesTheScreenUnderInFromTheStart() {
        host()
        go(WhatWeHaveRead)
        compose.mainClock.advanceTimeBy(SETTLE)
        compose.runOnUiThread { nav.popBackStack() }
        compose.mainClock.advanceTimeBy(FRAME * 2)
        val start = left("home")
        assertTrue("comes from the start: $start", start < (-1).dp && start >= (-30).dp)
        compose.mainClock.advanceTimeBy(SETTLE)
        assertEquals(0f, left("home").value, 0.5f)
    }

    @Test fun aModalRisesOverAStillScreen() {
        host()
        go(SignIn())
        assertTrue("rises from below", top("modal") > 1.dp)
        assertEquals("the screen under it stays still", 0f, left("home").value, 0.01f)
        assertEquals(0f, top("home").value, 0.01f)
        compose.mainClock.advanceTimeBy(SETTLE)
        assertEquals(0f, top("modal").value, 0.5f)
    }

    @Test fun tabToTabDoesNotMove() {
        host()
        go(Groups)
        assertEquals(0f, left("groups").value, 0.01f)
        assertEquals(0f, top("groups").value, 0.01f)
    }

    @Test fun aSignedOutGateOnlyFades() {
        host(signedOut = true)
        go(JoinGroup(token = "t"))
        assertEquals(0f, left("gate").value, 0.01f)
    }

    @Test fun aSignedInGateIsAPlainPush() {
        host(signedOut = false)
        go(JoinGroup(token = "t"))
        assertTrue(left("gate") > 1.dp)
    }

    @Test fun removeAnimationsMovesNothing() {
        host(reducedMotion = true)
        go(WhatWeHaveRead)
        assertEquals(0f, left("push").value, 0.01f)
        go(SignIn())
        assertEquals(0f, top("modal").value, 0.01f)
    }

    @Test fun gateRoutesAreTheScreensDrawnInsideSignInGate() {
        listOf(SplitPayment::class, SplitTyped::class, JoinGroup::class, ImportSplitwise::class, SetUpFamily::class, FamilySettings::class).forEach {
            assertTrue(it.simpleName, isSignInGate(it))
        }
        assertFalse(isSignInGate(Home::class))
        assertFalse(isSignInGate(SignIn::class))
        assertFalse(isSignInGate(FamilyMonthView::class))
    }

    @Test fun aSignedOutGateFadesOnlyGoingForward() {
        assertEquals(Move.GateFade, move(ScreenKind.Push, ScreenKind.Push, pop = false, toSignedOutGate = true))
        assertEquals(Move.GateFade, move(ScreenKind.Tab, ScreenKind.Modal, pop = false, toSignedOutGate = true))
        assertEquals(Move.Back, move(ScreenKind.Push, ScreenKind.Push, pop = true, toSignedOutGate = true))
        assertEquals(Move.None, move(ScreenKind.Push, ScreenKind.Push, pop = false, reducedMotion = true, toSignedOutGate = true))
    }

    private companion object {
        const val FRAME = 16L
        const val SETTLE = 1_000L
    }
}

@androidx.compose.runtime.Composable
private fun Screen(tag: String) {
    Box(Modifier.fillMaxSize().testTag(tag))
}
