package com.expenses.app.ui.screens.activity

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.nav.Activity
import com.expenses.app.ui.nav.AddExpense
import com.expenses.app.ui.nav.ExpenseDetail
import com.expenses.app.ui.nav.MonthView
import com.expenses.app.ui.nav.NeedsReview
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.nav.mainTab
import com.expenses.app.ui.nav.rememberNavigateOnce
import com.expenses.app.ui.screens.month.MonthRoute
import com.expenses.app.ui.screens.payment.NeedsReviewRoute
import java.time.YearMonth

/** Registers the Activity tab, Month view and Needs review. Owned by the activity feature. */
fun NavGraphBuilder.activityDestinations(navController: NavController) {
    mainTab<Activity>(MainTab.Activity, navController) { padding ->
        val once = rememberNavigateOnce()
        ActivityRoute(
            contentPadding = padding,
            onAdd = { once { navController.navigate(AddExpense()) { launchSingleTop = true } } },
            onOpenMonth = { month -> once { navController.navigate(MonthView.of(month)) } },
            onOpenItem = { id ->
                once {
                    when (id) {
                        is ActivityItemId.Expense ->
                            navController.navigate(ExpenseDetail(id.expenseId)) { launchSingleTop = true }
                        is ActivityItemId.Review -> navController.navigate(NeedsReview) { launchSingleTop = true }
                    }
                }
            },
        )
    }
    composable<MonthView> { entry ->
        val once = rememberNavigateOnce()
        MonthRoute(
            onBack = { navController.leave(entry) },
            onOpenExpense = { id -> once { navController.navigate(ExpenseDetail(id)) { launchSingleTop = true } } },
        )
    }
    composable<NeedsReview> { entry ->
        NeedsReviewRoute(onBack = { navController.leave(entry) })
    }
}

/**
 * The Activity tab with its ViewModel.
 *
 * @param contentPadding the space to keep clear of the status bar and the bottom navigation.
 * @param onAdd opens Add expense.
 * @param onOpenMonth opens Month view for the month.
 * @param onOpenItem opens a tapped row: Expense detail, or Needs review for a message that could not be read.
 */
@Composable
private fun ActivityRoute(
    contentPadding: PaddingValues,
    onAdd: () -> Unit,
    onOpenMonth: (YearMonth) -> Unit,
    onOpenItem: (ActivityItemId) -> Unit,
) {
    val viewModel = appViewModel { app ->
        ActivityViewModel(app.expenses, app.reviews, app.clock, app.groups.observeSplitLabels())
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    state?.let { shown ->
        ActivityScreen(
            state = shown,
            contentPadding = contentPadding,
            onEvent = { event ->
                when (event) {
                    ActivityEvent.AddClicked, ActivityEvent.AddByHandClicked -> onAdd()
                    ActivityEvent.MonthClicked -> onOpenMonth(shown.month)
                    is ActivityEvent.ItemClicked -> onOpenItem(event.id)
                    is ActivityEvent.FilterSelected -> viewModel.onEvent(event)
                }
            },
        )
    }
}
