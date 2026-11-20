package com.expenses.app.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.expenses.app.di.appViewModel
import com.expenses.app.groups.auth.AuthState
import com.expenses.app.ui.nav.AppLink
import com.expenses.app.ui.nav.AppNavHost
import com.expenses.app.ui.nav.Home
import com.expenses.app.ui.nav.SignInGateViewModel
import com.expenses.app.ui.nav.mainTab
import com.expenses.app.ui.nav.openLink
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.ViewHaptics
import com.expenses.app.ui.theme.hasRichHaptics
import kotlinx.coroutines.flow.first

/**
 * The top of the app's user interface: the navigation graph, starting at onboarding until it is finished and at
 * Home after that. Shows only the canvas colour while the settings load.
 *
 * @param openLink a screen to open from a notification or a friend's link; null for none. It is followed once
 * onboarding is done, on a fresh stack above Home (Back goes to Home). Before that, a friend's pay link
 * ([AppLink.Join]) waits until onboarding is done, so a friend who installed the app from the link still lands on
 * Join group; any other link is dropped (it is about data this phone does not have yet).
 * @param onOpenLinkHandled called once [openLink] was followed or dropped.
 *
 * Provides [LocalAppHaptics]: [ViewHaptics] on the activity's root view, so a confirm played as a dialog closes is
 * not lost with the dialog's window. Provides [LocalTabMemory], the tab shown last, so a tab reached from another tab
 * slides its pill from there; it is cleared whenever a screen that is not a tab shows.
 *
 * The first screen fades in over 250 ms when the settings arrive (`docs/motion.md` 2.1, cold start); the window
 * behind it is already the canvas colour (`colors_window.xml` in `res/values` and `res/values-night`).
 */
@Composable
fun AppRoot(openLink: AppLink?, onOpenLinkHandled: () -> Unit) {
    val viewModel = appViewModel { app -> AppRootViewModel(app.settings) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val start = state.startAtOnboarding
    val view = LocalView.current
    val context = LocalContext.current
    val haptics = remember(view) { ViewHaptics(view, richHaptics = hasRichHaptics(context)) }
    val gate = appViewModel { app -> SignInGateViewModel(app.auth) }
    val tabScope = rememberCoroutineScope()
    val tabMemory = remember { TabMemory(scope = tabScope) }
    val reduced = LocalReducedMotion.current
    // Shown at once when the settings are already there; otherwise the first screen fades in when they arrive.
    val firstScreen = remember { Animatable(if (start != null) 1f else 0f) }
    LaunchedEffect(start != null) {
        if (start != null) {
            if (reduced) firstScreen.snapTo(1f) else firstScreen.animateTo(1f, Motion.fade(Motion.Duration.Medium))
        }
    }
    CompositionLocalProvider(LocalAppHaptics provides haptics, LocalTabMemory provides tabMemory) {
        Box(modifier = Modifier.fillMaxSize().background(AppTheme.colors.bgCanvas)) {
            if (start != null) {
                val navController = rememberNavController()
                AppNavHost(
                    navController = navController,
                    startAtOnboarding = start,
                    modifier = Modifier.graphicsLayer { alpha = firstScreen.value },
                    signedOut = { gate.state.value !is AuthState.SignedIn },
                )
                FollowTabs(navController, tabMemory)
                LaunchedEffect(openLink, state.onboardingDone) {
                    val link = openLink ?: return@LaunchedEffect
                    when (linkAction(link, state.onboardingDone)) {
                        LinkAction.Wait -> Unit
                        LinkAction.Drop -> onOpenLinkHandled()
                        LinkAction.Open -> {
                            // Onboarding is saved as done a moment before it navigates to Home: wait for Home, or the
                            // link would open on top of onboarding and be popped with it.
                            navController.currentBackStackEntryFlow.first { navController.hasHome() }
                            navController.openLink(link)
                            onOpenLinkHandled()
                        }
                    }
                }
            }
        }
    }
}

/** Tells [memory] which screen is on top of [navController]'s back stack ([TabMemory.onTop]). */
@Composable
internal fun FollowTabs(navController: NavController, memory: TabMemory) {
    LaunchedEffect(navController, memory) {
        navController.currentBackStackEntryFlow.collect { entry -> memory.onTop(entry.destination.mainTab()) }
    }
}

/** What [AppRoot] does with a link. */
internal enum class LinkAction {
    /** Open it now. */
    Open,

    /** Keep it until onboarding is done. */
    Wait,

    /** Forget it. */
    Drop,
}

/** Whether [link] opens now, waits for onboarding, or is dropped. */
internal fun linkAction(link: AppLink, onboardingDone: Boolean): LinkAction = when {
    onboardingDone -> LinkAction.Open
    link is AppLink.Join -> LinkAction.Wait
    else -> LinkAction.Drop
}

private fun NavController.hasHome(): Boolean = runCatching { getBackStackEntry<Home>() }.isSuccess
