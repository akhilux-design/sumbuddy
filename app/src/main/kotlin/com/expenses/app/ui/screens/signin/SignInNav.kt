package com.expenses.app.ui.screens.signin

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.nav.PrivacyPromise
import com.expenses.app.ui.nav.SIGNED_IN_KEY
import com.expenses.app.ui.nav.SignIn
import com.expenses.app.ui.nav.SignInName
import com.expenses.app.ui.nav.Welcome
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.screens.onboarding.CollectEffects

/**
 * Registers Sign in and Your name (after sign-in). Owned by the sign-in feature. Screens that need sign-in navigate
 * to [SignIn] and read [SIGNED_IN_KEY] from their own saved state when they are shown again ("Back where they were").
 */
fun NavGraphBuilder.signInDestinations(navController: NavController) {
    composable<SignIn> { entry -> SignInRoute(navController, entry) }
    composable<SignInName> { entry -> SignInNameRoute(navController, entry) }
}

/** Sign in with its ViewModel. Google's sheet is offered once by itself when the screen opens. */
@Composable
private fun SignInRoute(navController: NavController, entry: NavBackStackEntry) {
    val route = entry.toRoute<SignIn>()
    val viewModel = appViewModel { app -> SignInViewModel(app.auth, createSavedStateHandle(), forJoin = route.forJoin, forFamily = route.forFamily) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    // Offered once the screen is resumed, after it has risen: the account sheet does not cover the rise.
    LifecycleResumeEffect(viewModel) {
        activity?.let(viewModel::offerAutomatically)
        onPauseOrDispose { }
    }
    CollectEffects(viewModel.effect) { effect ->
        if (!navController.isCurrent(entry)) return@CollectEffects
        when (effect) {
            SignInEffect.Left -> navController.leave(entry)
            is SignInEffect.SignedIn -> if (effect.isNew) {
                navController.navigate(SignInName(route.fromWelcome)) { popUpTo<SignIn> { inclusive = true } }
            } else {
                navController.afterSignIn(route.fromWelcome)
            }
        }
    }
    SignInScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                SignInEvent.ContinueWithGoogle -> activity?.let(viewModel::continueWithGoogle)
                SignInEvent.NotNow -> viewModel.notNow()
                is SignInEvent.DevSignIn -> viewModel.devSignIn(event.name)
            }
        },
    )
}

/** Your name (after sign-in) with its ViewModel. Continue, or Back, goes back where the person was. */
@Composable
private fun SignInNameRoute(navController: NavController, entry: NavBackStackEntry) {
    val route = entry.toRoute<SignInName>()
    val viewModel = appViewModel { app -> SignInNameViewModel(app.auth, createSavedStateHandle(), app.network.online) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffects(viewModel.saved) { if (navController.isCurrent(entry)) navController.afterSignIn(route.fromWelcome) }
    // Signed in already: Back (the top bar's and the system's alike) keeps the name from the account and goes on, as
    // Continue would. Sign in is no longer on the stack, so the system's own Back would land elsewhere. While Continue
    // saves, Back waits for it, so a profile the server took is also kept on this phone.
    val back = { if (!state.saving && navController.isCurrent(entry)) navController.afterSignIn(route.fromWelcome) }
    val refusals by viewModel.refusals.collectAsStateWithLifecycle()
    ActionProblemHaptic(state.problem, state.problemCount)
    BackHandler(onBack = back)
    SignInNameScreen(state = state, onEvent = viewModel::onEvent, onBack = back, refusals = refusals)
}

/**
 * Back where the person was after signing in: from Welcome, onboarding goes on at Privacy promise (Your name is not
 * asked again); otherwise the screen that opened Sign in shows again and finds [SIGNED_IN_KEY] set.
 */
private fun NavController.afterSignIn(fromWelcome: Boolean) {
    if (fromWelcome) {
        navigate(PrivacyPromise) { popUpTo<Welcome> { inclusive = false } }
    } else {
        popBackStack()
        currentBackStackEntry?.savedStateHandle?.set(SIGNED_IN_KEY, true)
    }
}
