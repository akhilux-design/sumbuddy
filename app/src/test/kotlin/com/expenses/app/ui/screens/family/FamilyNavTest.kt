package com.expenses.app.ui.screens.family

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.nav.Activity
import com.expenses.app.ui.nav.AppLink
import com.expenses.app.ui.nav.FamilySettings
import com.expenses.app.ui.nav.Groups
import com.expenses.app.ui.nav.Home
import com.expenses.app.ui.nav.MainGraph
import com.expenses.app.ui.nav.SetUpFamily
import com.expenses.app.ui.nav.You
import com.expenses.app.ui.nav.openLink
import com.expenses.app.ui.nav.openTab
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Meera joined Home" (`AppLink.Family`) finishes nothing: the screens it closes are kept as a tab switch keeps them,
 * as for every other link (FamilyNavReviewTest covers the family screens that finished). Same graph shape as
 * `AppNavHost`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FamilyNavTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController

    @Test fun theJoinedNotificationKeepsTheTabItClosed() {
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
        compose.runOnIdle { nav.openTab(MainTab.You) }
        compose.runOnIdle { nav.navigate(FamilySettings()) }

        compose.runOnIdle { nav.openLink(AppLink.Family) }
        compose.runOnIdle { assertTrue("Home opens", nav.currentDestination!!.hasRoute<Home>()) }

        compose.runOnIdle { nav.openTab(MainTab.You) }
        compose.runOnIdle {
            assertTrue("You shows Family settings again, as it was", nav.currentDestination!!.hasRoute<FamilySettings>())
        }
    }
}
