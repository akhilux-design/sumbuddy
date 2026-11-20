package com.expenses.app.ui.screens.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.nav.AddExpense
import com.expenses.app.ui.nav.AddToExpenses
import com.expenses.app.ui.nav.ExpenseDetail
import com.expenses.app.ui.nav.FamilyMonthView
import com.expenses.app.ui.nav.FamilySettings
import com.expenses.app.ui.nav.Home
import com.expenses.app.ui.nav.MonthView
import com.expenses.app.ui.nav.NeedsReview
import com.expenses.app.ui.nav.ReviewPayments
import com.expenses.app.ui.nav.SetUpFamily
import com.expenses.app.ui.nav.SplitPayment
import com.expenses.app.ui.nav.TurnOnSmsTracking
import com.expenses.app.ui.nav.mainTab
import com.expenses.app.ui.nav.openTab
import com.expenses.app.ui.nav.rememberNavigateOnce
import com.expenses.app.ui.screens.activity.ActivityItemId
import com.expenses.app.ui.screens.onboarding.hasSmsAccess

/** Registers the Home tab. Owned by the home feature. */
fun NavGraphBuilder.homeDestinations(navController: NavController) {
    mainTab<Home>(MainTab.Home, navController) { padding ->
        HomeRoute(contentPadding = padding, navController = navController)
    }
}

/**
 * The Home tab with its ViewModel. Add on a detected payment opens Add to expenses for it; Split opens Split it (Sign
 * in first when signed out; Not now leaves the payment waiting). "Review all" opens Review payments when more than one
 * payment waits. "Owed to you" and "You owe" open the Groups tab. "Turn on" on the tip opens the Privacy promise,
 * which asks for SMS access right after it; when access is already allowed, tracking turns on at once. On the Family
 * side, "Set up Family" opens Set up Family, "Send link again" and "Invite partner" open Family settings, which shares
 * the link at once, and the spent card opens Month view (Family) when there is a family. Each navigation runs once,
 * however fast the person taps.
 *
 * @param contentPadding the space to keep clear of the status bar and the bottom navigation.
 * @param navController moves to the screens Home opens.
 */
@Composable
private fun HomeRoute(contentPadding: PaddingValues, navController: NavController) {
    val viewModel = appViewModel { app ->
        HomeViewModel(app.expenses, app.settings, app.clock, app.groups, app.auth.state, app.network.online, app.family, app.homeView)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val once = rememberNavigateOnce()
    state?.let { shown ->
        HomeScreen(
            state = shown,
            contentPadding = contentPadding,
            onEvent = { event ->
                when (event) {
                    HomeEvent.AddExpense -> once { navController.navigate(AddExpense()) { launchSingleTop = true } }
                    HomeEvent.SpentClicked -> once {
                        // The Family side with a family opens the family month; otherwise the person's own month.
                        if (shown.content is HomeContent.FamilyMonth) {
                            navController.navigate(FamilyMonthView.of(shown.month))
                        } else {
                            navController.navigate(MonthView.of(shown.month))
                        }
                    }
                    HomeEvent.SetUpFamily -> once { navController.navigate(SetUpFamily) { launchSingleTop = true } }
                    HomeEvent.SendFamilyLink -> once { navController.navigate(FamilySettings(sendLink = true)) { launchSingleTop = true } }
                    is HomeEvent.FamilyRowTapped -> once {
                        navController.navigate(ExpenseDetail(event.expenseId)) { launchSingleTop = true }
                    }
                    is HomeEvent.AddDetectedPayment -> once {
                        navController.navigate(AddToExpenses(event.paymentId)) { launchSingleTop = true }
                    }
                    HomeEvent.ReviewAll -> once { navController.navigate(ReviewPayments) { launchSingleTop = true } }
                    is HomeEvent.SplitDetectedPayment -> once {
                        navController.navigate(SplitPayment(event.paymentId)) { launchSingleTop = true }
                    }
                    is HomeEvent.ExpenseTapped -> once {
                        when (val id = event.id) {
                            is ActivityItemId.Expense ->
                                navController.navigate(ExpenseDetail(id.expenseId)) { launchSingleTop = true }
                            is ActivityItemId.Review -> navController.navigate(NeedsReview) { launchSingleTop = true }
                        }
                    }
                    HomeEvent.SplitTotalsClicked -> once { navController.openTab(MainTab.Groups) }
                    HomeEvent.TurnOnTracking -> if (context.hasSmsAccess()) {
                        viewModel.onSmsAccessGranted()
                    } else {
                        once { navController.navigate(TurnOnSmsTracking) { launchSingleTop = true } }
                    }
                    is HomeEvent.ViewChanged,
                    is HomeEvent.IgnoreDetectedPayment,
                    HomeEvent.DismissTrackingTip,
                    -> viewModel.onEvent(event)
                }
            },
        )
    }
}
