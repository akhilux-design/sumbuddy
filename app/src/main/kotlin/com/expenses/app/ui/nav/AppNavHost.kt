package com.expenses.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.navigation
import com.expenses.app.ui.screens.activity.activityDestinations
import com.expenses.app.ui.screens.cardbill.cardBillDestinations
import com.expenses.app.ui.screens.family.familyDestinations
import com.expenses.app.ui.screens.grouppage.groupPageDestinations
import com.expenses.app.ui.screens.groups.groupsDestinations
import com.expenses.app.ui.screens.home.homeDestinations
import com.expenses.app.ui.screens.join.joinDestinations
import com.expenses.app.ui.screens.onboarding.onboardingGraph
import com.expenses.app.ui.screens.payment.paymentDestinations
import com.expenses.app.ui.screens.review.reviewDestinations
import com.expenses.app.ui.screens.settle.settleDestinations
import com.expenses.app.ui.screens.signin.signInDestinations
import com.expenses.app.ui.screens.split.splitDestinations
import com.expenses.app.ui.screens.splitwise.splitwiseDestinations
import com.expenses.app.ui.screens.you.youDestinations

/**
 * The app's navigation graph. Each feature registers its own destinations from its `*Nav.kt` file; this function
 * only puts them together.
 *
 * Screens move by their kind ([ScreenKind], [NavTransitions]): a push slides on the X axis, a modal rises, a result
 * arrives, and tab to tab the screens do not move (the tab's page animates inside `MainScaffold`).
 *
 * @param navController the controller of this host.
 * @param startAtOnboarding true to start at [OnboardingGraph], false to start at [MainGraph]. Read only once.
 * @param signedOut whether nobody is signed in, read when a screen drawn inside [SignInGate] opens: it then only fades
 * in, because it draws nothing until Sign in opens over it.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    startAtOnboarding: Boolean,
    modifier: Modifier = Modifier,
    signedOut: () -> Boolean = { false },
) {
    val transitions = rememberNavTransitions(signedOut)
    NavHost(
        navController = navController,
        startDestination = if (startAtOnboarding) OnboardingGraph else MainGraph,
        modifier = modifier,
        enterTransition = { transitions.enter(this, pop = false) },
        exitTransition = { transitions.exit(this, pop = false) },
        popEnterTransition = { transitions.enter(this, pop = true) },
        popExitTransition = { transitions.exit(this, pop = true) },
        predictivePopEnterTransition = { edge -> transitions.predictiveEnter(this, edge) },
        predictivePopExitTransition = { edge -> transitions.predictiveExit(this, edge) },
    ) {
        onboardingGraph(navController)
        navigation<MainGraph>(startDestination = Home) {
            homeDestinations(navController)
            groupsDestinations(navController)
            activityDestinations(navController)
            youDestinations(navController)
        }
        paymentDestinations(navController)
        reviewDestinations(navController)
        signInDestinations(navController)
        // Groups and splitting (docs/groups-build-plan.md): each area registers its screens from its own file.
        groupPageDestinations(navController)
        splitDestinations(navController)
        settleDestinations(navController)
        joinDestinations(navController)
        splitwiseDestinations(navController)
        familyDestinations(navController)
        cardBillDestinations(navController)
    }
}
