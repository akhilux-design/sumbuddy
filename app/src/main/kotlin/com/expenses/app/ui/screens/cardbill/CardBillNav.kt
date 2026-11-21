package com.expenses.app.ui.screens.cardbill

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
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.nav.CardBillView
import com.expenses.app.ui.nav.GroupPage
import com.expenses.app.ui.nav.RemindOnWhatsApp
import com.expenses.app.ui.nav.SignIn
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.nav.rememberNavigateOnce
import com.expenses.app.ui.screens.grouppage.NoticeHaptics

/**
 * Registers the Card bill view (docs/card-bills.md 2.5). Owned by the card bill area. It opens from Home's CARD BILLS
 * rows, and from the card bill reminder above Home (`openLink`), so Back goes to Home either way.
 */
fun NavGraphBuilder.cardBillDestinations(navController: NavController) {
    composable<CardBillView> { entry -> CardBillRoute(navController, entry) }
}

/**
 * The Card bill view with its ViewModel. A friend's row opens the Group page where their part is largest; Remind
 * everyone may open Remind on WhatsApp; the signed-out card and an ended session open Sign in, which comes back here.
 * A bill that is gone leaves at once (D23).
 */
@Composable
private fun CardBillRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        val saved = createSavedStateHandle()
        val route = saved.toRoute<CardBillView>()
        CardBillViewModel(
            remindOnOpen = route.remind,
            detail = app.cardBillReader.observeBill(route.billId),
            remind = { groupId, memberIds -> app.groups.remind(groupId, memberIds) },
            online = app.network.online,
            clock = app.clock,
            saved = saved,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val once = rememberNavigateOnce()
    val gone = state is CardBillUiState.Gone
    LaunchedEffect(gone) {
        if (gone) navController.leave(entry)
    }
    NoticeHaptics((state as? CardBillUiState.Shown)?.notice)
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            if (!navController.isCurrent(entry)) return@collect
            val route: Any = when (effect) {
                is CardBillEffect.RemindOnWhatsApp -> RemindOnWhatsApp(effect.groupId, effect.memberId)
                CardBillEffect.SignIn -> SignIn()
            }
            navController.navigate(route) { launchSingleTop = true }
        }
    }
    CardBillScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                CardBillEvent.Back -> navController.leave(entry)
                is CardBillEvent.OpenGroup -> once { navController.navigate(GroupPage(event.groupId)) { launchSingleTop = true } }
                CardBillEvent.SignIn -> once { navController.navigate(SignIn()) { launchSingleTop = true } }
                CardBillEvent.RemindEveryone -> viewModel.onEvent(event)
            }
        },
    )
}
