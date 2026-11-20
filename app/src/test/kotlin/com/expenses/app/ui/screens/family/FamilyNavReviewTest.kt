package com.expenses.app.ui.screens.family

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.nav.Activity
import com.expenses.app.ui.nav.FamilySettings
import com.expenses.app.ui.nav.Groups
import com.expenses.app.ui.nav.Home
import com.expenses.app.ui.nav.MainGraph
import com.expenses.app.ui.nav.SetUpFamily
import com.expenses.app.ui.nav.You
import com.expenses.app.ui.nav.openFamilyMonth
import com.expenses.app.ui.nav.openTab
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Code review (Family build): a finished family screen must not come back. `openFamilyMonth()` pops the screens above
 * Home with `saveState = true`, so Set up Family (after Invite partner) and Family settings (after Leave Family) are
 * saved as a tab's back stack and restored the next time that tab opens. Same graph shape as `AppNavHost`: the tabs in
 * `MainGraph` (start Home), the family screens at the root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FamilyNavReviewTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun graph() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = MainGraph) {
                navigation<MainGraph>(startDestination = Home) {
                    composable<Home> {}
                    composable<Groups> {}
                    composable<Activity> {}
                    composable<You> {}
                }
                composable<SetUpFamily> {}
                composable<FamilySettings> {}
            }
        }
    }

    private fun step(action: NavHostController.() -> Unit) = compose.runOnIdle { nav.action() }

    private fun currentIs(what: String, check: (androidx.navigation.NavDestination) -> Boolean) = compose.runOnIdle {
        val now = nav.currentDestination!!
        assertTrue("expected $what, the screen on top is ${now.route}", check(now))
    }

    /** You row → Set up Family → Invite partner (Done opens Home) → the You tab must show You again. */
    @Test fun theYouTabOpensYouAfterSetUpFamilyFinished() {
        graph()
        step { openTab(MainTab.You) }
        step { navigate(SetUpFamily) }
        step { openFamilyMonth() }
        currentIs("Home") { it.hasRoute<Home>() }
        step { openTab(MainTab.You) }
        currentIs("You, not the finished Set up Family") { it.hasRoute<You>() }
    }

    /** You row → Family settings → Leave Family (Left opens Home) → the You tab must show You again. */
    @Test fun theYouTabOpensYouAfterLeavingTheFamily() {
        graph()
        step { openTab(MainTab.You) }
        step { navigate(FamilySettings()) }
        step { openFamilyMonth() }
        step { openTab(MainTab.Activity) }
        step { openTab(MainTab.You) }
        currentIs("You, not Family settings of a family that is gone") { it.hasRoute<You>() }
    }

    /** Home's "Set up Family" card → Invite partner → Activity tab → Home tab must show Home. */
    @Test fun theHomeTabOpensHomeAfterSetUpFamilyFromHomesCard() {
        graph()
        step { navigate(SetUpFamily) }
        step { openFamilyMonth() }
        currentIs("Home") { it.hasRoute<Home>() }
        step { openTab(MainTab.Activity) }
        step { openTab(MainTab.Home) }
        currentIs("Home, not the finished Set up Family") { it.hasRoute<Home>() }
    }
}
