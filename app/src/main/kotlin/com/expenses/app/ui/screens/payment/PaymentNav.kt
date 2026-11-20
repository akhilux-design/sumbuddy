package com.expenses.app.ui.screens.payment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.expenses.app.data.category.Category
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.nav.CHOSEN_CATEGORY_KEY
import com.expenses.app.ui.nav.AddExpense
import com.expenses.app.ui.nav.AddToExpenses
import com.expenses.app.ui.nav.ChooseCategory
import com.expenses.app.ui.nav.ExpenseAdded
import com.expenses.app.ui.nav.ExpenseDetail
import com.expenses.app.ui.nav.Home
import com.expenses.app.ui.nav.RecurringSplitOffer
import com.expenses.app.ui.nav.SplitPayment
import com.expenses.app.ui.nav.SplitTyped
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.nav.rememberNavigateOnce
import com.expenses.app.ui.theme.LocalAppHaptics

/**
 * Registers the payment screens: Add to expenses, Choose category, Expense added, Expense detail and Add expense.
 * Owned by the split-flow area (docs/groups-build-plan.md). Needs review is registered by the activity feature with
 * [NeedsReviewRoute].
 */
fun NavGraphBuilder.paymentDestinations(navController: NavController) {
    composable<AddToExpenses> { entry -> AddToExpensesRoute(navController, entry) }
    composable<ChooseCategory> { entry -> ChooseCategoryRoute(navController, entry) }
    composable<ExpenseAdded> { entry -> ExpenseAddedRoute(navController, entry) }
    composable<ExpenseDetail> { entry -> ExpenseDetailRoute(navController, entry) }
    composable<AddExpense> { entry -> AddExpenseRoute(navController, entry) }
}

/**
 * Needs review (design 02-everyday/19-needs-review) with its ViewModel, for the destination `NeedsReview`.
 *
 * @param onBack leaves the screen.
 */
@Composable
fun NeedsReviewRoute(onBack: () -> Unit) {
    val viewModel = appViewModel { app ->
        NeedsReviewViewModel(app.reviews, app.expenses, app.transactions, app.clock, createSavedStateHandle())
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    NeedsReviewScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                NeedsReviewEvent.Back -> onBack()
                else -> viewModel.onEvent(event)
            }
        },
    )
}

@Composable
private fun AddToExpensesRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        val route = createSavedStateHandle().toRoute<AddToExpenses>()
        AddToExpensesViewModel(route.expenseId, app.expenses, app.clock, createSavedStateHandle())
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val once = rememberNavigateOnce()
    val expenseId = entry.toRoute<AddToExpenses>().expenseId
    val added = state as? AddToExpensesUiState.Added
    LaunchedEffect(added) {
        if (added != null && navController.isCurrent(entry)) {
            navController.navigate(ExpenseAdded(added.expenseId)) {
                popUpTo<AddToExpenses> { inclusive = true }
            }
        }
    }
    AddToExpensesScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                AddToExpensesEvent.Back -> navController.leave(entry)
                AddToExpensesEvent.MoreCategories -> once {
                    navController.navigate(ChooseCategory(expenseId)) { launchSingleTop = true }
                }
                // Split it for this payment; Add to expenses stays under it (Sign in first when signed out).
                AddToExpensesEvent.Split -> once {
                    navController.navigate(SplitPayment(expenseId)) { launchSingleTop = true }
                }
                AddToExpensesEvent.SeeExpense -> once {
                    navController.navigate(ExpenseDetail(expenseId)) {
                        popUpTo<AddToExpenses> { inclusive = true }
                        launchSingleTop = true
                    }
                }
                else -> viewModel.onEvent(event)
            }
        },
    )
}

@Composable
private fun ChooseCategoryRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        val route = createSavedStateHandle().toRoute<ChooseCategory>()
        ChooseCategoryViewModel(route.expenseId, route.draft?.let(Category::valueOf), app.expenses)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isDraft = entry.toRoute<ChooseCategory>().draft != null
    LaunchedEffect(state.done) {
        val chosen = state.chosen
        if (!state.done || chosen == null || !navController.isCurrent(entry)) return@LaunchedEffect
        if (isDraft) navController.previousBackStackEntry?.savedStateHandle?.set(CHOSEN_CATEGORY_KEY, chosen.name)
        navController.leave(entry)
    }
    ChooseCategoryScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                ChooseCategoryEvent.Back -> navController.leave(entry)
                is ChooseCategoryEvent.Chosen -> viewModel.onEvent(event)
            }
        },
    )
}

@Composable
private fun ExpenseAddedRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        ExpenseAddedViewModel(createSavedStateHandle().toRoute<ExpenseAdded>().expenseId, app.expenses, app.clock)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val once = rememberNavigateOnce()
    val expenseId = entry.toRoute<ExpenseAdded>().expenseId
    val undone = state as? ExpenseAddedUiState.Undone
    LaunchedEffect(undone) {
        if (undone == null || !navController.isCurrent(entry)) return@LaunchedEffect
        if (undone.backToForm) {
            navController.navigate(AddToExpenses(undone.expenseId)) {
                popUpTo<ExpenseAdded> { inclusive = true }
            }
        } else {
            navController.popBackStack()
        }
    }
    ExpenseAddedScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                ExpenseAddedEvent.Done -> if (navController.isCurrent(entry)) navController.backToHome()
                ExpenseAddedEvent.OpenExpense -> once {
                    navController.navigate(ExpenseDetail(expenseId)) {
                        popUpTo<ExpenseAdded> { inclusive = true }
                        launchSingleTop = true
                    }
                }
                ExpenseAddedEvent.Undo -> viewModel.onEvent(event)
            }
        },
    )
}

@Composable
private fun ExpenseDetailRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        val savedState = createSavedStateHandle()
        ExpenseDetailViewModel(savedState.toRoute<ExpenseDetail>().expenseId, app.expenses, app.groups, app.clock, savedState)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val once = rememberNavigateOnce()
    val expenseId = entry.toRoute<ExpenseDetail>().expenseId
    val removed = state == ExpenseDetailUiState.Removed
    val haptics = LocalAppHaptics.current
    LaunchedEffect(removed) {
        // Only the person's Delete removes it: their dialog action is done (`haptic/confirm`).
        if (removed) {
            haptics.confirm()
            navController.leave(entry)
        }
    }
    // A category picked on Choose category for an unsaved edit comes back here, not to the database.
    val draftCategory by entry.savedStateHandle.getStateFlow<String?>(CHOSEN_CATEGORY_KEY, null)
        .collectAsStateWithLifecycle()
    LaunchedEffect(draftCategory) {
        val name = draftCategory ?: return@LaunchedEffect
        viewModel.onEvent(ExpenseDetailEvent.CategoryChosen(Category.valueOf(name)))
        entry.savedStateHandle[CHOSEN_CATEGORY_KEY] = null
    }
    ExpenseDetailScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                ExpenseDetailEvent.Back -> navController.leave(entry)
                ExpenseDetailEvent.ChangeCategory -> once {
                    val draft = ((state as? ExpenseDetailUiState.Shown)?.edit)?.category?.name
                    navController.navigate(ChooseCategory(expenseId, draft)) { launchSingleTop = true }
                }
                ExpenseDetailEvent.Split -> once {
                    navController.navigate(SplitPayment(expenseId)) { launchSingleTop = true }
                }
                // A split that splits itself: Same split?, with "Ask me each time" to turn it off.
                ExpenseDetailEvent.OpenSplitRule -> once {
                    navController.navigate(RecurringSplitOffer(expenseId)) { launchSingleTop = true }
                }
                ExpenseDetailEvent.Review -> once {
                    navController.navigate(AddToExpenses(expenseId)) {
                        popUpTo<ExpenseDetail> { inclusive = true }
                        launchSingleTop = true
                    }
                }
                else -> viewModel.onEvent(event)
            }
        },
    )
}

@Composable
private fun AddExpenseRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        val savedState = createSavedStateHandle()
        AddExpenseViewModel(savedState.toRoute<AddExpense>().groupId, app.expenses, app.groups, app.clock, savedState)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val once = rememberNavigateOnce()
    val addedId = state.addedId
    LaunchedEffect(addedId) {
        if (addedId != null && navController.isCurrent(entry)) {
            navController.navigate(ExpenseAdded(addedId)) {
                popUpTo<AddExpense> { inclusive = true }
            }
        }
    }
    AddExpenseScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                AddExpenseEvent.Back -> navController.leave(entry)
                // Split it for the typed expense; Add expense stays under it and leaves with Split. Sorted.'s Done.
                AddExpenseEvent.Next -> viewModel.splitDraft()?.let { draft ->
                    once { navController.navigate(SplitTyped(draft.encode())) { launchSingleTop = true } }
                }
                else -> viewModel.onEvent(event)
            }
        },
    )
}

/** Back to Home after Expense added, removing the screens above it; when Home is not on the stack, back once. */
private fun NavController.backToHome() {
    if (!popBackStack<Home>(inclusive = false)) popBackStack()
}
