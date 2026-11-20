package com.expenses.app.ui.screens.join

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.nav.GroupPage
import com.expenses.app.ui.nav.JoinGroup
import com.expenses.app.ui.nav.SignIn
import com.expenses.app.ui.nav.SignInGate
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.nav.openFamilyMonth
import com.expenses.app.ui.screens.onboarding.CollectEffects
import com.expenses.app.ui.screens.signin.ActionProblemHaptic
import com.expenses.app.ui.theme.LocalAppHaptics

/**
 * Registers Join group, opened by a friend's pay link (the App Link). Signed out, Sign in opens first reading "Join a
 * group" (02-everyday/49), and Join group opens again after it ([SignInGate]). Owned by the you-and-join area
 * (docs/groups-build-plan.md).
 */
fun NavGraphBuilder.joinDestinations(navController: NavController) {
    composable<JoinGroup> { entry ->
        SignInGate(navController, entry, signIn = SignIn(forJoin = true)) {
            JoinGroupRoute(navController, entry)
        }
    }
}

/**
 * Join group with its ViewModel, while signed in. Joined (or in the group already), the group's page opens in place
 * of Join group; a family opens Home on the Family side. "Not now", "OK" and Back leave with nothing joined.
 */
@Composable
private fun JoinGroupRoute(navController: NavController, entry: NavBackStackEntry) {
    val token = entry.toRoute<JoinGroup>().token
    val viewModel = appViewModel { app -> JoinGroupViewModel(token, app.groups, app.network.online, app.clock, app.family) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Each time the screen shows signed in: the first time, and again after a sign-in the server asked for.
    LaunchedEffect(viewModel) { viewModel.start() }
    val haptics = LocalAppHaptics.current
    CollectEffects(viewModel.effect) { effect ->
        if (!navController.isCurrent(entry)) return@CollectEffects
        when (effect) {
            is JoinGroupEffect.OpenGroup -> {
                // Joined, or in the group already: what the person started is done (`haptic/confirm`).
                haptics.confirm()
                navController.navigate(GroupPage(effect.groupId)) {
                    popUpTo<JoinGroup> { inclusive = true }
                    launchSingleTop = true
                }
            }
            JoinGroupEffect.OpenFamily -> {
                // Joined the family, or in it already: Home on the Family side, where the family month loads.
                haptics.confirm()
                navController.openFamilyMonth()
            }
        }
    }
    // While "Join group" runs, Back waits for it: leaving would cancel it half way, and a join the server took would
    // then not be pulled until the next sync.
    // A Join that did not happen (offline, refused) shows its banner with the reject haptic.
    val ready = state as? JoinGroupUiState.Ready
    val readyFamily = state as? JoinGroupUiState.ReadyFamily
    ActionProblemHaptic(ready?.problem ?: readyFamily?.problem, ready?.problemCount ?: readyFamily?.problemCount ?: 0)
    val joining = ready?.joining == true || readyFamily?.joining == true
    BackHandler(enabled = joining) {}
    JoinGroupScreen(
        state = state,
        onEvent = { event ->
            when {
                event != JoinGroupEvent.Leave -> viewModel.onEvent(event)
                !joining -> navController.leave(entry)
            }
        },
    )
}
