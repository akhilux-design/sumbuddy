package com.expenses.app.ui.screens.family

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.expenses.app.R
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.nav.ExpenseDetail
import com.expenses.app.ui.nav.FamilyMonthView
import com.expenses.app.ui.nav.FamilySettings
import com.expenses.app.ui.nav.SetUpFamily
import com.expenses.app.ui.nav.SignIn
import com.expenses.app.ui.nav.SignInGate
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.nav.openFamilyMonth
import com.expenses.app.ui.nav.rememberNavigateOnce
import com.expenses.app.ui.screens.onboarding.CollectEffects
import com.expenses.app.ui.screens.signin.ActionProblemHaptic
import com.expenses.app.ui.theme.LocalAppHaptics

/**
 * Registers Set up Family, Family settings and Month view (Family) (docs/family.md 8.3). Set up Family and Family
 * settings are signed in only: signed out, Sign in opens first reading "Share a family month" (06-family/08), and Not
 * now goes back. Owned by the family area.
 */
fun NavGraphBuilder.familyDestinations(navController: NavController) {
    composable<SetUpFamily> { entry ->
        SignInGate(navController, entry, signIn = SignIn(forFamily = true)) { SetUpFamilyRoute(navController, entry) }
    }
    composable<FamilySettings> { entry ->
        SignInGate(navController, entry, signIn = SignIn(forFamily = true)) { FamilySettingsRoute(navController, entry) }
    }
    composable<FamilyMonthView> { entry -> FamilyMonthRoute(navController, entry) }
}

/**
 * Set up Family with its ViewModel. Done, the share sheet opens with the invite and Home shows the Family side (the
 * waiting card), with `haptic/confirm`; a family already there opens Home on the Family side. While "Invite partner"
 * runs, Back waits for it: leaving would cancel it half way.
 */
@Composable
private fun SetUpFamilyRoute(navController: NavController, entry: NavBackStackEntry) {
    val defaultName = stringResource(R.string.family_name_default)
    val viewModel = appViewModel { app -> SetUpFamilyViewModel(app.family, app.network.online, createSavedStateHandle(), defaultName) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalAppHaptics.current
    CollectEffects(viewModel.effect) { effect ->
        if (!navController.isCurrent(entry)) return@CollectEffects
        when (effect) {
            is SetUpFamilyEffect.Share -> FamilyShare.share(context, effect.invite)
            SetUpFamilyEffect.Done -> {
                haptics.confirm()
                navController.openFamilyMonth()
            }
            SetUpFamilyEffect.OpenFamily -> navController.openFamilyMonth()
        }
    }
    ActionProblemHaptic(state.problem, state.problemCount)
    BackHandler(enabled = state.busy) {}
    SetUpFamilyScreen(
        state = state,
        onEvent = { event ->
            when {
                event != SetUpFamilyEvent.Back -> viewModel.onEvent(event)
                !state.busy -> navController.leave(entry)
            }
        },
    )
}

/**
 * Family settings with its ViewModel. Back saves a changed name and leaves (it waits while an online action runs).
 * Leave Family done opens Home on the Family side (now with no family) with `haptic/confirm`; the family leaving this
 * phone for another reason closes the screen.
 */
@Composable
private fun FamilySettingsRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        val saved = createSavedStateHandle()
        FamilySettingsViewModel(app.family, app.network.online, saved.toRoute<FamilySettings>().sendLink, saved, app.appScope)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalAppHaptics.current
    CollectEffects(viewModel.effect) { effect ->
        if (!navController.isCurrent(entry)) return@CollectEffects
        when (effect) {
            is FamilySettingsEffect.Share -> FamilyShare.share(context, effect.invite)
        }
    }
    LaunchedEffect(state) {
        when (state) {
            FamilySettingsUiState.Left -> if (navController.isCurrent(entry)) {
                haptics.confirm()
                navController.openFamilyMonth()
            }
            FamilySettingsUiState.Gone -> navController.leave(entry)
            else -> Unit
        }
    }
    val shown = state as? FamilySettingsUiState.Shown
    ActionProblemHaptic(shown?.problem, shown?.problemCount ?: 0)
    val busy = shown?.busy == true
    BackHandler(enabled = busy) {}
    FamilySettingsScreen(
        state = state,
        onEvent = { event ->
            when {
                event != FamilySettingsEvent.Back -> viewModel.onEvent(event)
                !busy -> {
                    viewModel.onEvent(FamilySettingsEvent.NameDone)
                    navController.leave(entry)
                }
            }
        },
    )
}

/** Month view (Family) with its ViewModel. The person's own rows open Expense detail; the family leaving closes it. */
@Composable
private fun FamilyMonthRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        val saved = createSavedStateHandle()
        FamilyMonthViewModel(saved, saved.toRoute<FamilyMonthView>().month, app.family, app.clock)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val once = rememberNavigateOnce()
    LaunchedEffect(state) {
        if (state == FamilyMonthScreenState.Gone) navController.leave(entry)
    }
    (state as? FamilyMonthScreenState.Shown)?.let { shown ->
        FamilyMonthScreen(
            state = shown.month,
            onEvent = { event ->
                when (event) {
                    FamilyMonthEvent.Back -> navController.leave(entry)
                    is FamilyMonthEvent.RowTapped -> once {
                        navController.navigate(ExpenseDetail(event.expenseId)) { launchSingleTop = true }
                    }
                    FamilyMonthEvent.PreviousMonth, FamilyMonthEvent.NextMonth -> viewModel.onEvent(event)
                }
            },
        )
    }
}
