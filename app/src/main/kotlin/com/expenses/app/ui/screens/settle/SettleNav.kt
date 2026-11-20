package com.expenses.app.ui.screens.settle

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.expenses.app.R
import com.expenses.app.di.appViewModel
import com.expenses.app.groups.sync.GroupNotifications
import com.expenses.app.ui.nav.BalanceCleared
import com.expenses.app.ui.nav.MarkAsPaid
import com.expenses.app.ui.nav.PaymentMatch
import com.expenses.app.ui.nav.RemindOnWhatsApp
import com.expenses.app.ui.nav.SettleUp
import com.expenses.app.ui.nav.SignIn
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave

/**
 * Registers Settle up, Mark as paid, Money received, All square and Remind on WhatsApp (design
 * 03-getting-paid-back/00 to 03, 05, 07). Owned by the settle-up area (docs/groups-build-plan.md).
 */
fun NavGraphBuilder.settleDestinations(navController: NavController) {
    composable<SettleUp> { entry -> SettleUpRoute(navController, entry) }
    composable<MarkAsPaid> { entry -> MarkAsPaidRoute(navController, entry) }
    composable<PaymentMatch> { entry -> PaymentMatchRoute(navController, entry) }
    composable<BalanceCleared> { entry -> BalanceClearedRoute(navController, entry) }
    composable<RemindOnWhatsApp> { entry -> RemindRoute(navController, entry) }
}

@Composable
private fun SettleUpRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        val route = createSavedStateHandle().toRoute<SettleUp>()
        SettleUpViewModel(route.groupId, route.memberId, app.groups, app.settlementMatcher)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Recorded, or nothing left to settle (the payment SMS closed the balance while the UPI app was open): leave.
    val finished = state == SettleUpUiState.Done || state == SettleUpUiState.Gone
    LaunchedEffect(finished) {
        if (finished) navController.leave(entry)
    }
    CollectSettleEffects(viewModel.effectFlow) { effect ->
        when (effect) {
            is SettleUpEffect.OpenUpi -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, effect.uri.toUri()))
            } catch (_: ActivityNotFoundException) {
                viewModel.onEvent(SettleUpEvent.UpiAppMissing)
            }
        }
    }
    SettleUpScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                SettleUpEvent.Back -> navController.leave(entry)
                else -> viewModel.onEvent(event)
            }
        },
    )
}

@Composable
private fun MarkAsPaidRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        val route = createSavedStateHandle().toRoute<MarkAsPaid>()
        MarkAsPaidViewModel(route.groupId, route.memberId, app.groups)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val finished = state == MarkAsPaidUiState.Done || state == MarkAsPaidUiState.Gone
    LaunchedEffect(finished) {
        if (finished) navController.leave(entry)
    }
    MarkAsPaidScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                MarkAsPaidEvent.Back -> navController.leave(entry)
                else -> viewModel.onEvent(event)
            }
        },
    )
}

@Composable
private fun PaymentMatchRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        PaymentMatchViewModel(createSavedStateHandle().toRoute<PaymentMatch>().creditExpenseId, app.groups, app.expenses, app.clock)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val creditId = entry.toRoute<PaymentMatch>().creditExpenseId
    val cleared = state as? PaymentMatchUiState.Cleared
    LaunchedEffect(cleared) {
        if (cleared == null || !navController.isCurrent(entry)) return@LaunchedEffect
        GroupNotifications(context).cancelAsk(creditId)
        navController.navigate(BalanceCleared(cleared.groupId, cleared.memberId, cleared.amountMinor)) {
            popUpTo<PaymentMatch> { inclusive = true }
        }
    }
    PaymentMatchScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                PaymentMatchEvent.Back -> navController.leave(entry)
                PaymentMatchEvent.No -> {
                    GroupNotifications(context).cancelAsk(creditId)
                    navController.leave(entry)
                }
                PaymentMatchEvent.Yes -> viewModel.onEvent(event)
            }
        },
    )
}

@Composable
private fun BalanceClearedRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        val route = createSavedStateHandle().toRoute<BalanceCleared>()
        BalanceClearedViewModel(route.groupId, route.memberId, route.amountMinor, app.groups)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state == BalanceClearedUiState.Gone) {
        if (state == BalanceClearedUiState.Gone && navController.isCurrent(entry)) navController.backToHome()
    }
    BalanceClearedScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                BalanceClearedEvent.Nice -> if (navController.isCurrent(entry)) navController.backToHome()
            }
        },
    )
}

@Composable
private fun RemindRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        val route = createSavedStateHandle().toRoute<RemindOnWhatsApp>()
        RemindViewModel(route.groupId, route.memberId, app.groups, app.network, myName = { app.auth.profile()?.name })
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.settle_remind_clip_label)
    val shown = state as? RemindUiState.Shown
    val message = shown?.let { remindMessageOf(it) }
    // "Link copied" where the system confirms nothing itself; Send clears it.
    var linkCopied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state == RemindUiState.Gone) {
        if (state == RemindUiState.Gone) navController.leave(entry)
    }
    CollectSettleEffects(viewModel.effectFlow) { effect ->
        when (effect) {
            is RemindEffect.Send -> message?.copy(link = effect.link)?.let { RemindShare.send(context, it.text) }
            is RemindEffect.Copy -> {
                RemindShare.copy(context, clipLabel, effect.link)
                if (RemindShare.needsCopyConfirmation()) linkCopied = true
            }
            // The session ended: Sign in, then back here to send it.
            RemindEffect.SignIn -> if (navController.isCurrent(entry)) navController.navigate(SignIn()) { launchSingleTop = true }
        }
    }
    RemindScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                RemindEvent.Back -> navController.leave(entry)
                RemindEvent.Send -> {
                    linkCopied = false
                    viewModel.onEvent(event)
                }
                else -> viewModel.onEvent(event)
            }
        },
        linkCopied = linkCopied,
    )
}
