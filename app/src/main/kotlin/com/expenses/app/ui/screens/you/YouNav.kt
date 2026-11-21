package com.expenses.app.ui.screens.you

import androidx.compose.foundation.layout.PaddingValues
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.nav.AccountsAndCards
import com.expenses.app.ui.nav.ExpenseDetail
import com.expenses.app.ui.nav.FamilySettings
import com.expenses.app.ui.nav.ImportSplitwise
import com.expenses.app.ui.nav.SetUpFamily
import com.expenses.app.ui.nav.SignIn
import com.expenses.app.ui.nav.TrackingStartDate
import com.expenses.app.ui.nav.TurnOnSmsTracking
import com.expenses.app.ui.nav.WhatWeHaveRead
import com.expenses.app.ui.nav.You
import com.expenses.app.ui.nav.YouAccount
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.nav.openSourceCode
import com.expenses.app.ui.nav.mainTab
import com.expenses.app.ui.nav.rememberNavigateOnce
import com.expenses.app.ui.screens.onboarding.CollectEffects
import com.expenses.app.ui.screens.onboarding.hasSmsAccess
import com.expenses.app.ui.screens.payment.SystemDatePicker
import com.expenses.app.ui.screens.signin.ActionProblemHaptic
import com.expenses.app.ui.theme.LocalAppHaptics

/**
 * Registers the You tab, Accounts and cards, What we've read, Tracking start date and Account. Owned by the you
 * feature (the you-and-join area of docs/groups-build-plan.md).
 */
fun NavGraphBuilder.youDestinations(navController: NavController) {
    mainTab<You>(MainTab.You, navController) { padding ->
        val once = rememberNavigateOnce()
        YouRoute(
            contentPadding = padding,
            onOpenAccounts = { once { navController.navigate(AccountsAndCards) { launchSingleTop = true } } },
            onOpenWhatWeRead = { once { navController.navigate(WhatWeHaveRead) { launchSingleTop = true } } },
            onOpenTrackingStart = { once { navController.navigate(TrackingStartDate) { launchSingleTop = true } } },
            onTurnOnTracking = { once { navController.navigate(TurnOnSmsTracking) { launchSingleTop = true } } },
            onOpenAccount = { once { navController.navigate(YouAccount) { launchSingleTop = true } } },
            onSignIn = { once { navController.navigate(SignIn()) { launchSingleTop = true } } },
            onOpenImport = { once { navController.navigate(ImportSplitwise) { launchSingleTop = true } } },
            onOpenFamily = { hasFamily ->
                once { navController.navigate(if (hasFamily) FamilySettings() else SetUpFamily) { launchSingleTop = true } }
            },
        )
    }
    composable<TurnOnSmsTracking> { entry ->
        TurnOnSmsTrackingRoute(onDone = { navController.leave(entry) })
    }
    composable<AccountsAndCards> { entry ->
        val viewModel = appViewModel { app -> AccountsViewModel(app.accounts) }
        val state by viewModel.state.collectAsStateWithLifecycle()
        state?.let { shown ->
            AccountsScreen(
                state = shown,
                onEvent = { event ->
                    if (event == AccountsEvent.Back) navController.leave(entry) else viewModel.onEvent(event)
                },
            )
        }
    }
    composable<WhatWeHaveRead> { entry ->
        val once = rememberNavigateOnce()
        val context = LocalContext.current
        val viewModel = appViewModel { app -> WhatWeReadViewModel(app.expenses, app.readSms, app.clock) }
        val state by viewModel.state.collectAsStateWithLifecycle()
        state?.let { shown ->
            WhatWeReadScreen(
                state = shown,
                onEvent = { event ->
                    when (event) {
                        WhatWeReadEvent.Back -> navController.leave(entry)
                        WhatWeReadEvent.OpenSourceCode -> openSourceCode(context)
                        is WhatWeReadEvent.OpenPayment -> once {
                            navController.navigate(ExpenseDetail(event.expenseId)) { launchSingleTop = true }
                        }
                    }
                },
            )
        }
    }
    composable<TrackingStartDate> { entry ->
        TrackingStartRoute(onBack = { navController.leave(entry) })
    }
    composable<YouAccount> { entry -> YouAccountRoute(navController, entry) }
}

/**
 * The You tab with its ViewModel. Switching SMS tracking on opens the Privacy promise, which asks for SMS access
 * right after it; when access is already allowed, tracking turns on at once. Signed in, the name opens Account;
 * signed out, "Sign in with Google" opens Sign in, which comes back here.
 *
 * @param contentPadding the space to keep clear of the status bar and the bottom navigation.
 * @param onOpenAccounts opens Accounts and cards.
 * @param onOpenWhatWeRead opens What we've read.
 * @param onOpenTrackingStart opens Tracking start date.
 * @param onTurnOnTracking opens the Privacy promise before Android's SMS dialog.
 * @param onOpenAccount opens Account.
 * @param onSignIn opens Sign in.
 * @param onOpenImport opens Import from Splitwise, which asks to sign in first when needed.
 * @param onOpenFamily opens Family settings when there is a family (true), else Set up Family; both ask to sign in
 * first when needed ("Share a family month").
 */
@Composable
private fun YouRoute(
    contentPadding: PaddingValues,
    onOpenAccounts: () -> Unit,
    onOpenWhatWeRead: () -> Unit,
    onOpenTrackingStart: () -> Unit,
    onTurnOnTracking: () -> Unit,
    onOpenAccount: () -> Unit,
    onSignIn: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenFamily: (hasFamily: Boolean) -> Unit,
) {
    val viewModel = appViewModel { app ->
        YouViewModel(app.settings, app.accounts, app.readSms, app.clock, app.auth.state, app.family.observeFamily())
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    state?.let { shown ->
        YouScreen(
            state = shown,
            contentPadding = contentPadding,
            onEvent = { event ->
                when (event) {
                    YouEvent.OpenAccountsAndCards -> onOpenAccounts()
                    YouEvent.OpenWhatWeRead -> onOpenWhatWeRead()
                    YouEvent.OpenTrackingStartDate -> onOpenTrackingStart()
                    YouEvent.OpenAccount -> onOpenAccount()
                    YouEvent.SignIn -> onSignIn()
                    YouEvent.OpenImport -> onOpenImport()
                    YouEvent.OpenFamily -> onOpenFamily(shown.family != YouFamily.None)
                    YouEvent.OpenSourceCode -> openSourceCode(context)
                    is YouEvent.SetSmsTracking -> when {
                        !event.on -> viewModel.onEvent(event)
                        context.hasSmsAccess() -> viewModel.onSmsAccessGranted()
                        else -> onTurnOnTracking()
                    }
                    is YouEvent.SetReminders -> viewModel.onEvent(event)
                }
            },
        )
    }
}

/**
 * Account with its ViewModel. Save, Sign out and Delete go back to You when they are done, with the confirm haptic;
 * a session the server ended opens Sign in, which comes back here.
 */
@Composable
private fun YouAccountRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        YouAccountViewModel(app.auth, app.groups.observeUnsentCount(), app.network.online, createSavedStateHandle())
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalAppHaptics.current
    CollectEffects(viewModel.effect) { effect ->
        if (!navController.isCurrent(entry)) return@CollectEffects
        when (effect) {
            YouAccountEffect.Leave -> {
                // Save, Sign out or Delete is done (`haptic/confirm`).
                haptics.confirm()
                navController.leave(entry)
            }
            YouAccountEffect.SignIn -> navController.navigate(SignIn()) { launchSingleTop = true }
        }
    }
    val refusals by viewModel.refusals.collectAsStateWithLifecycle()
    ActionProblemHaptic(state.problem, state.problemCount)
    // While a save, sign-out or delete runs, Back waits for it: leaving would clear the ViewModel half way, and a
    // profile the server took would then not be kept on this phone (Account, Home's name).
    val mustFinish = state.busy
    BackHandler(enabled = mustFinish) {}
    YouAccountScreen(
        state = state,
        onEvent = { event ->
            if (event == YouAccountEvent.Back) {
                if (!mustFinish) navController.leave(entry)
            } else {
                viewModel.onEvent(event)
            }
        },
        refusals = refusals,
    )
}

/**
 * Tracking start date with its ViewModel. "Pick a date" opens the system date picker, which offers only days
 * before the current start and stays open across rotation; older messages are read by [WorkOlderPaymentsScanner].
 *
 * @param onBack leaves the screen.
 */
@Composable
private fun TrackingStartRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel = appViewModel { app ->
        TrackingStartViewModel(app.settings, WorkOlderPaymentsScanner(context), app.clock, createSavedStateHandle())
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    state?.let { shown ->
        TrackingStartScreen(
            state = shown,
            onEvent = { event -> if (event == TrackingStartEvent.Back) onBack() else viewModel.onEvent(event) },
        )
        if (shown.pickingDate) {
            SystemDatePicker(
                initial = shown.pickedDate ?: shown.latestPickable,
                latest = shown.latestPickable,
                onPicked = { viewModel.onEvent(TrackingStartEvent.DatePicked(it)) },
                onDismiss = { viewModel.onEvent(TrackingStartEvent.DatePickerClosed) },
            )
        }
    }
}
