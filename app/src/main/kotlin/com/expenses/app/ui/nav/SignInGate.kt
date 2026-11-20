package com.expenses.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.expenses.app.di.appViewModel
import com.expenses.app.groups.auth.AuthRepository
import com.expenses.app.groups.auth.AuthState
import kotlinx.coroutines.flow.StateFlow

/** Who is signed in, for [SignInGate]. */
class SignInGateViewModel(auth: AuthRepository) : ViewModel() {
    /** The session state. */
    val state: StateFlow<AuthState> = auth.state
}

/**
 * Shows [content] only while someone is signed in (product.md "Sign in is asked only when it is first needed").
 * Signed out, it opens [signIn] once, keeping this screen under it; Sign in comes back here with [SIGNED_IN_KEY] set
 * and [content] shows ("Back where they were", flow lane 01). Coming back still signed out (Not now, Back) leaves this
 * screen too, so whatever opened it is as it was: a payment stays waiting (flow lane 02 "Signed in?"), a link is
 * not joined. Nothing is drawn while the session is being read.
 *
 * Used by Split it ([SplitPayment], [SplitTyped]) and Join group ([JoinGroup], with `SignIn(forJoin = true)`).
 *
 * @param navController the app's navigation.
 * @param entry this screen's back stack entry.
 * @param signIn the Sign in screen to open.
 * @param content the screen, drawn while signed in.
 */
@Composable
fun SignInGate(
    navController: NavController,
    entry: NavBackStackEntry,
    signIn: SignIn = SignIn(),
    content: @Composable () -> Unit,
) {
    val viewModel = appViewModel { app -> SignInGateViewModel(app.auth) }
    val auth by viewModel.state.collectAsStateWithLifecycle()
    when (auth) {
        is AuthState.SignedIn -> {
            // A later sign-out (a refused session) starts the gate again.
            LaunchedEffect(entry) {
                entry.savedStateHandle.remove<Boolean>(ASKED_KEY)
                entry.savedStateHandle.remove<Boolean>(SIGNED_IN_KEY)
            }
            content()
        }
        AuthState.Loading -> Unit
        AuthState.SignedOut -> LifecycleResumeEffect(entry, auth) {
            if (navController.isCurrent(entry)) {
                val saved = entry.savedStateHandle
                when {
                    // Just signed in: the session state follows in a moment and the screen shows.
                    saved.get<Boolean>(SIGNED_IN_KEY) == true -> Unit
                    saved.get<Boolean>(ASKED_KEY) != true -> {
                        saved[ASKED_KEY] = true
                        navController.navigate(signIn) { launchSingleTop = true }
                    }
                    else -> navController.leave(entry)
                }
            }
            onPauseOrDispose { }
        }
    }
}

/** Saved state key: Sign in was opened once from this screen. */
private const val ASKED_KEY = "sign_in_gate_asked"
