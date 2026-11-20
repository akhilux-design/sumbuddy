package com.expenses.app.ui.screens.month

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.toRoute
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.nav.MonthView
import com.expenses.app.ui.screens.activity.ActivityItemId

/**
 * Month view with its ViewModel, for the [MonthView] destination.
 *
 * @param onBack leaves the screen.
 * @param onOpenExpense opens Expense detail for the expense with this id.
 */
@Composable
fun MonthRoute(onBack: () -> Unit, onOpenExpense: (Long) -> Unit) {
    val viewModel = appViewModel { app ->
        val handle = createSavedStateHandle()
        MonthViewModel(handle, handle.toRoute<MonthView>().month, app.expenses, app.clock, app.groups.observeSplitLabels())
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    state?.let { shown ->
        MonthScreen(
            state = shown,
            onEvent = { event ->
                when (event) {
                    MonthEvent.Back -> onBack()
                    is MonthEvent.ItemClicked -> {
                        (event.id as? ActivityItemId.Expense)?.let { onOpenExpense(it.expenseId) }
                    }
                    MonthEvent.PreviousMonth, MonthEvent.NextMonth -> viewModel.onEvent(event)
                }
            },
        )
    }
}
