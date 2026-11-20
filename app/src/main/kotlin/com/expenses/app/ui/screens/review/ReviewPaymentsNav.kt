package com.expenses.app.ui.screens.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.nav.ChooseCategory
import com.expenses.app.ui.nav.Home
import com.expenses.app.ui.nav.ReviewPayments
import com.expenses.app.ui.nav.SplitPayment
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.nav.rememberNavigateOnce

/** Registers Review payments. Owned by the review feature. */
fun NavGraphBuilder.reviewDestinations(navController: NavController) {
    composable<ReviewPayments> { entry -> ReviewPaymentsRoute(navController, entry) }
}

/**
 * Review payments with its ViewModel. The category chip opens Choose category, which stores the choice on the
 * payment without adding it, so the card shows it on return. "Split it instead" puts the payment in the run's splits
 * and opens Split it (Sign in first when signed out); Done on Split. Sorted. comes back here, and the split counts as
 * added. Done on All sorted ends the run and goes back to Home; when nothing waits from the start, the screen leaves.
 */
@Composable
private fun ReviewPaymentsRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        ReviewPaymentsViewModel(app.expenses, app.clock, app.reviewSession, app.appScope)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val once = rememberNavigateOnce()
    LaunchedEffect(state) {
        if (state == ReviewPaymentsUiState.NothingWaiting) navController.leave(entry)
    }
    ReviewPaymentsScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                ReviewPaymentsEvent.Back -> navController.leave(entry)
                ReviewPaymentsEvent.Done -> if (navController.isCurrent(entry)) {
                    viewModel.onEvent(event)
                    if (!navController.popBackStack<Home>(inclusive = false)) navController.popBackStack()
                }
                is ReviewPaymentsEvent.ChangeCategory -> once {
                    navController.navigate(ChooseCategory(event.paymentId)) { launchSingleTop = true }
                }
                is ReviewPaymentsEvent.Split -> once {
                    viewModel.onEvent(event)
                    navController.navigate(SplitPayment(event.paymentId)) { launchSingleTop = true }
                }
                else -> viewModel.onEvent(event)
            }
        },
    )
}
