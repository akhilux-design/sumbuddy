package com.expenses.app.ui.screens.split

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.expenses.app.ui.nav.AddExpense
import com.expenses.app.ui.nav.AddToExpenses
import com.expenses.app.ui.nav.Home
import com.expenses.app.ui.nav.RecurringSplitOffer
import com.expenses.app.ui.nav.ReviewPayments
import com.expenses.app.ui.nav.SplitAdded
import com.expenses.app.ui.nav.SplitPayment
import com.expenses.app.ui.nav.SplitTyped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Leaving Split. Sorted. (02-everyday/13) and Same split? (14): they open in place of Split it, so Done, Back and Undo
 * land on the screen that opened Split it (docs/groups-build-plan.md section 5), and never on Split it again.
 */
@RunWith(RobolectricTestRunner::class)
class SplitNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun show() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = Home) {
                composable<Home> {}
                composable<ReviewPayments> {}
                composable<AddToExpenses> {}
                composable<AddExpense> {}
                composable<SplitPayment> {}
                composable<SplitTyped> {}
                composable<SplitAdded> {}
                composable<RecurringSplitOffer> {}
            }
        }
    }

    /** The screens on the stack, bottom first. */
    private fun screens(): List<NavDestination> = nav.currentBackStack.value.map { it.destination }.filter { it !is NavGraph }

    /** Split it is on top: the split is made and [next] opens in its place. */
    private fun splitDone(next: Any) {
        compose.runOnIdle { nav.openInPlaceOfSplit(nav.currentBackStackEntry!!, next) }
    }

    @Test fun doneOnSplitSortedGoesBackToTheScreenThatOpenedSplitIt() {
        show()
        compose.runOnIdle { nav.navigate(ReviewPayments) }
        compose.runOnIdle { nav.navigate(SplitPayment(7)) }
        splitDone(SplitAdded("g", "DETECTED"))
        compose.runOnIdle {
            assertTrue(nav.currentDestination!!.hasRoute<SplitAdded>())
            assertTrue(nav.previousBackStackEntry!!.destination.hasRoute<ReviewPayments>())
        }

        compose.runOnIdle { nav.leaveSplit(nav.currentBackStackEntry!!, undo = false, paymentId = 7) }

        compose.runOnIdle {
            val screens = screens()
            assertEquals(2, screens.size)
            assertTrue(screens[0].hasRoute<Home>())
            assertTrue(screens[1].hasRoute<ReviewPayments>())
        }
    }

    @Test fun undoOnSplitSortedGoesBackToWhereThePaymentWaits() {
        show()
        compose.runOnIdle { nav.navigate(AddToExpenses(7)) }
        compose.runOnIdle { nav.navigate(SplitPayment(7)) }
        splitDone(SplitAdded("g", "DETECTED"))

        compose.runOnIdle { nav.leaveSplit(nav.currentBackStackEntry!!, undo = true, paymentId = 7) }

        compose.runOnIdle { assertTrue(nav.currentDestination!!.hasRoute<AddToExpenses>()) }
    }

    @Test fun doneLeavesAnAddToExpensesOfTheSamePaymentToo() {
        show()
        compose.runOnIdle { nav.navigate(AddToExpenses(7)) }
        compose.runOnIdle { nav.navigate(SplitPayment(7)) }
        splitDone(SplitAdded("g", "DETECTED"))

        compose.runOnIdle { nav.leaveSplit(nav.currentBackStackEntry!!, undo = false, paymentId = 7) }

        compose.runOnIdle { assertTrue(nav.currentDestination!!.hasRoute<Home>()) }
    }

    @Test fun aTypedSplitLeavesItsAddExpenseOnDone() {
        show()
        compose.runOnIdle { nav.navigate(AddExpense()) }
        compose.runOnIdle { nav.navigate(SplitTyped("{}")) }
        splitDone(SplitAdded("g"))
        compose.runOnIdle { assertTrue(nav.previousBackStackEntry!!.destination.hasRoute<AddExpense>()) }

        compose.runOnIdle { nav.leaveSplit(nav.currentBackStackEntry!!, undo = false, paymentId = null) }

        compose.runOnIdle { assertTrue(nav.currentDestination!!.hasRoute<Home>()) }
    }

    @Test fun backOnSameSplitGoesBackToTheScreenThatOpenedSplitIt() {
        show()
        compose.runOnIdle { nav.navigate(SplitPayment(7)) }
        splitDone(RecurringSplitOffer(7))
        compose.runOnIdle { assertTrue(nav.previousBackStackEntry!!.destination.hasRoute<Home>()) }

        compose.runOnIdle { nav.leaveSplit(nav.currentBackStackEntry!!, undo = false, paymentId = 7) }

        compose.runOnIdle { assertTrue(screens().single().hasRoute<Home>()) }
    }
}
