package com.expenses.app.di

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.expenses.app.ExpensesApplication

/** The [AppContainer] of the running app, read from the creation extras of a ViewModel. */
fun CreationExtras.appContainer(): AppContainer =
    (checkNotNull(this[APPLICATION_KEY]) { "No application in the creation extras" } as ExpensesApplication).container

/**
 * A factory that makes one ViewModel from the [AppContainer]. The lambda runs on [CreationExtras], so it can also
 * call `createSavedStateHandle()` and, for a screen with route arguments, `createSavedStateHandle().toRoute<Route>()`.
 *
 * ```
 * appViewModelFactory { app -> HomeViewModel(app.expenses, app.settings, app.clock) }
 * ```
 */
inline fun <reified VM : ViewModel> appViewModelFactory(
    crossinline create: CreationExtras.(AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { create(appContainer()) }
}

/**
 * The ViewModel of type [VM] for the current navigation destination, made with [create] the first time.
 *
 * ```
 * val viewModel = appViewModel { app -> AddToExpensesViewModel(createSavedStateHandle(), app.expenses) }
 * ```
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: CreationExtras.(AppContainer) -> VM): VM =
    viewModel(factory = appViewModelFactory(create))
