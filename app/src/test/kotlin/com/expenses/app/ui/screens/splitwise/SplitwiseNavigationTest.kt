package com.expenses.app.ui.screens.splitwise

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.expenses.app.ui.nav.ChoosePeople
import com.expenses.app.ui.nav.GroupImported
import com.expenses.app.ui.nav.GroupPage
import com.expenses.app.ui.nav.Groups
import com.expenses.app.ui.nav.Home
import com.expenses.app.ui.nav.ImportSplitwise
import com.expenses.app.ui.nav.NewGroup
import com.expenses.app.ui.nav.You
import com.expenses.app.ui.screens.groups.offersImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where Import from Splitwise is offered and where it leads (docs/splitwise-import.md 5.6, 5.9): New group offers it
 * only when opened from the Groups tab with nobody filled in (not from a split, 142); Group imported takes the place of
 * Import and of New group under it; the group's page opens on the Groups tab, so Back lands there.
 */
@RunWith(RobolectricTestRunner::class)
class SplitwiseNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun show() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = Home) {
                composable<Home> {}
                composable<Groups> {}
                composable<You> {}
                composable<ChoosePeople> {}
                composable<NewGroup> {}
                composable<ImportSplitwise> {}
                composable<GroupImported> {}
                composable<GroupPage> {}
            }
        }
    }

    private fun current() = nav.currentBackStackEntry!!

    @Test fun newGroupFromTheGroupsTabOffersTheImport() {
        show()
        compose.runOnIdle { nav.navigate(Groups) }
        compose.runOnIdle { nav.navigate(NewGroup()) }
        compose.runOnIdle { assertTrue(nav.offersImport(current().toRoute<NewGroup>())) }
    }

    @Test fun newGroupFromASplitDoesNot() {
        show()
        compose.runOnIdle { nav.navigate(ChoosePeople()) }
        compose.runOnIdle { nav.navigate(NewGroup.with(listOf("a", "b"))) }
        compose.runOnIdle { assertFalse(nav.offersImport(current().toRoute<NewGroup>())) }
        // Make a group with nobody picked is still from a split.
        compose.runOnIdle { nav.navigate(NewGroup()) }
        compose.runOnIdle { assertFalse(nav.offersImport(current().toRoute<NewGroup>())) }
    }

    @Test fun groupImportedTakesThePlaceOfImportAndNewGroup() {
        show()
        compose.runOnIdle { nav.navigate(Groups) }
        compose.runOnIdle { nav.navigate(NewGroup()) }
        compose.runOnIdle { nav.navigate(ImportSplitwise) }
        compose.runOnIdle { nav.openImported(current(), "g1") }
        compose.runOnIdle {
            assertEquals("g1", current().toRoute<GroupImported>().groupId)
            assertTrue(nav.previousBackStackEntry!!.destination.hasRoute<Groups>())
        }
    }

    @Test fun fromYouGroupImportedTakesThePlaceOfImportOnly() {
        show()
        compose.runOnIdle { nav.navigate(You) }
        compose.runOnIdle { nav.navigate(ImportSplitwise) }
        compose.runOnIdle { nav.openImported(current(), "g1") }
        compose.runOnIdle { assertTrue(nav.previousBackStackEntry!!.destination.hasRoute<You>()) }
    }

    @Test fun theGroupOpensOnTheGroupsTabAndBackLandsThere() {
        show()
        compose.runOnIdle { nav.navigate(You) }
        compose.runOnIdle { nav.navigate(ImportSplitwise) }
        compose.runOnIdle { nav.navigate(GroupImported("g1")) }
        compose.runOnIdle { nav.openOnGroupsTab("g1") }
        compose.runOnIdle {
            assertEquals("g1", current().toRoute<GroupPage>().groupId)
            assertTrue(nav.previousBackStackEntry!!.destination.hasRoute<Groups>())
        }
        compose.runOnIdle { nav.popBackStack() }
        compose.runOnIdle { assertTrue(nav.currentDestination!!.hasRoute<Groups>()) }
        compose.runOnIdle { nav.popBackStack() }
        compose.runOnIdle { assertTrue(nav.currentDestination!!.hasRoute<Home>()) }
    }
}
