package com.expenses.app.ui.screens.grouppage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.nav.GroupPage
import com.expenses.app.ui.nav.GroupSettings
import com.expenses.app.ui.nav.Groups
import com.expenses.app.ui.nav.Home
import com.expenses.app.ui.nav.openTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * When the Group page counts as left ([LeftPageEffect]), which is when "Join undone" (41) goes: another screen of the
 * app takes its place. Not a theme change or a rotation (the screen is made again), the screen going off, or another
 * app in front. It is told once for each time, and before navigation stores the page's saved state.
 */
@RunWith(RobolectricTestRunner::class)
class LeftPageEffectTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private var left = 0

    /** "left" each time the page is left, "stored" each time navigation stores the page's saved state. */
    private val order = mutableListOf<String>()

    /** Home, the Groups tab and a group's page with the effect on it, opened at the page. */
    private fun showThePage() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = Home) {
                composable<Home> {}
                composable<Groups> {}
                composable<GroupPage> { entry ->
                    LeftPageEffect(nav, entry) {
                        left++
                        order += "left"
                    }
                    DisposableEffect(entry) {
                        entry.savedStateRegistry.registerSavedStateProvider(PROBE) {
                            order += "stored"
                            Bundle()
                        }
                        onDispose { entry.savedStateRegistry.unregisterSavedStateProvider(PROBE) }
                    }
                }
                composable<GroupSettings> {}
            }
        }
        compose.runOnIdle { nav.openTab(MainTab.Groups) }
        compose.runOnIdle { nav.navigate(GroupPage("g1")) }
        compose.waitForIdle()
        assertEquals(0, left)
    }

    @Test fun theScreenGoingOffOrAnotherAppInFrontIsNotLeaving() {
        showThePage()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        assertEquals(0, left)
    }

    @Test fun aThemeChangeOrARotationIsNotLeaving() {
        showThePage()

        // The screen is taken down and made again; the page is still the screen on top.
        compose.activityRule.scenario.recreate()

        assertEquals(0, left)
    }

    @Test fun openingAnotherScreenFromThePageIsLeaving() {
        showThePage()

        compose.runOnIdle { nav.navigate(GroupSettings("g1")) }
        compose.waitForIdle()

        assertEquals(1, left)
    }

    @Test fun backIsLeaving() {
        showThePage()

        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()

        assertEquals(1, left)
    }

    @Test fun anotherTabIsLeaving() {
        showThePage()

        compose.runOnIdle { nav.openTab(MainTab.Home) }
        compose.waitForIdle()

        assertEquals(1, left)
    }

    @Test fun anotherTabIsToldBeforeThePagesStateIsStored() {
        showThePage()
        order.clear()

        // The tab's screens are put away with their saved state. What leaving changes (no "Join undone" any more)
        // must be in that state, or the banner would come back when Android stops the app and the tab is opened again.
        compose.runOnIdle { nav.openTab(MainTab.Home) }
        compose.waitForIdle()

        assertTrue("the page's state was stored: $order", "stored" in order)
        assertTrue("left before stored: $order", order.indexOf("left") in 0 until order.indexOf("stored"))
        assertEquals(1, left)
    }

    @Test fun comingBackAndLeavingAgainIsLeavingTwice() {
        showThePage()

        compose.runOnIdle { nav.navigate(GroupSettings("g1")) }
        compose.waitForIdle()
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals(1, left)

        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals(2, left)
    }

    private companion object {
        const val PROBE = "left-page-effect-test"
    }
}
