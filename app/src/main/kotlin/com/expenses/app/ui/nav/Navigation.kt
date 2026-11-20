package com.expenses.app.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.shell.MainScaffold

/**
 * Opens a main tab the way a bottom navigation should: one copy of each tab on the back stack, Home at the bottom,
 * and each tab's own back stack and scroll position saved and restored when the person comes back to it.
 */
fun NavController.openTab(tab: MainTab) {
    navigate(tab.route) {
        popUpTo<Home> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** True while [entry] is the screen on top, so a second tap or a late event does not navigate twice. */
fun NavController.isCurrent(entry: NavBackStackEntry): Boolean = currentBackStackEntry?.id == entry.id

/** Goes back from [entry], once, and only while it is the screen on top. */
fun NavController.leave(entry: NavBackStackEntry) {
    if (isCurrent(entry)) popBackStack()
}

/**
 * Whether the screen it is read in may navigate now, for [rememberNavigateOnce]; null where that is "while resumed".
 * A main tab provides it ([mainTab]).
 */
val LocalMayNavigate = staticCompositionLocalOf<(() -> Boolean)?> { null }

/**
 * Returns a function that runs a navigation only while the screen it is called from is resumed. The screen stops
 * being resumed as soon as it navigates, so a double tap opens the next screen once. Use it for every
 * navigate-on-click. A main tab navigates while it is the screen on top instead ([LocalMayNavigate]): a tab is resumed
 * only once the tab it replaced is gone, and a tap is never held up by that (`docs/motion.md` 3.8).
 */
@Composable
fun rememberNavigateOnce(): (navigate: () -> Unit) -> Unit {
    val lifecycleOwner = LocalLifecycleOwner.current
    val mayNavigate = LocalMayNavigate.current
    return remember(lifecycleOwner, mayNavigate) {
        { navigate ->
            val may = mayNavigate?.invoke() ?: lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (may) navigate()
        }
    }
}

/** Leaves onboarding for Home. Onboarding is removed from the back stack, so Back on Home leaves the app. */
fun NavController.finishOnboarding() {
    navigate(MainGraph) {
        popUpTo<OnboardingGraph> { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Registers the destination of one main tab: [content] drawn inside [MainScaffold] with [tab] active in the bottom
 * navigation. [content] receives the padding to keep clear of the status bar and the bottom navigation.
 *
 * Tab to tab the screens do not move ([Move.Tab], from the NavHost's [NavTransitions]): each tab draws its own dock,
 * the old tab stays drawn while the new one's page covers it, and `MainScaffold` animates the page and the pill. The
 * NavHost resumes the new tab only once the old one is gone, so a tab navigates while it is on top ([LocalMayNavigate]).
 */
inline fun <reified T : Any> NavGraphBuilder.mainTab(
    tab: MainTab,
    navController: NavController,
    crossinline content: @Composable (PaddingValues) -> Unit,
) {
    composable<T> { entry ->
        val onTop = remember(entry) {
            { navController.isCurrent(entry) && entry.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        }
        CompositionLocalProvider(LocalMayNavigate provides onTop) {
            MainScaffold(active = tab, onSelect = { navController.openTab(it) }) { padding -> content(padding) }
        }
    }
}
