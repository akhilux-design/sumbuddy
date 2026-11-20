package com.expenses.app.ui.screens.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.nav.ConfirmAccounts
import com.expenses.app.ui.nav.HowFarBack
import com.expenses.app.ui.nav.NotificationPermission
import com.expenses.app.ui.nav.NothingFound
import com.expenses.app.ui.nav.OnboardingGraph
import com.expenses.app.ui.nav.PrivacyPromise
import com.expenses.app.ui.nav.Scanning
import com.expenses.app.ui.nav.SignIn
import com.expenses.app.ui.nav.SmsPermission
import com.expenses.app.ui.nav.Welcome
import com.expenses.app.ui.nav.YourName
import com.expenses.app.ui.nav.finishOnboarding
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.nav.rememberNavigateOnce
import com.expenses.app.ui.screens.payment.SystemDatePicker

/**
 * Registers the onboarding graph: Welcome, Your name, Privacy promise (with the system SMS dialog, and SMS access
 * blocked when Android no longer asks), Allow notifications (Android 13 and later), How far back, Scanning SMS,
 * then Nothing found or Confirm accounts, and Home. Not allowing SMS access, or choosing to type expenses in,
 * finishes onboarding in manual mode. Onboarding has no sign-in; Welcome's "I already have an account" opens Sign in
 * (a destination of the sign-in feature), which comes back to Privacy promise. Owned by the onboarding feature.
 */
fun NavGraphBuilder.onboardingGraph(navController: NavController) {
    navigation<OnboardingGraph>(startDestination = Welcome) {
        composable<Welcome> {
            val once = rememberNavigateOnce()
            WelcomeScreen(
                onGetStarted = { once { navController.navigate(YourName) { launchSingleTop = true } } },
                onHaveAccount = { once { navController.navigate(SignIn(fromWelcome = true)) { launchSingleTop = true } } },
            )
        }
        composable<YourName> { entry ->
            YourNameRoute(navController, entry)
        }
        composable<PrivacyPromise> {
            PrivacyPromiseRoute(navController)
        }
        composable<SmsPermission> {
            SmsBlockedRoute(navController)
        }
        composable<NotificationPermission> {
            AllowNotificationsRoute(navController)
        }
        composable<HowFarBack> { entry ->
            HowFarBackRoute(navController, entry)
        }
        composable<Scanning> {
            ScanningRoute(navController)
        }
        composable<NothingFound> {
            NothingFoundRoute(navController)
        }
        composable<ConfirmAccounts> { entry ->
            ConfirmAccountsRoute(navController, entry)
        }
    }
}

/** Your name with its ViewModel; Privacy promise opens once the name is stored. */
@Composable
private fun YourNameRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app -> YourNameViewModel(app.settings, createSavedStateHandle()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffects(viewModel.done) {
        if (navController.isCurrent(entry)) navController.navigate(PrivacyPromise) { launchSingleTop = true }
    }
    YourNameScreen(state = state, onEvent = viewModel::onEvent, onBack = { navController.leave(entry) })
}

/**
 * Privacy promise. Allow SMS access shows the real system dialog over it: allowed goes on, Don't allow finishes
 * onboarding in manual mode, and "don't ask again" opens SMS access blocked.
 */
@Composable
private fun PrivacyPromiseRoute(navController: NavController) {
    val context = LocalContext.current
    val viewModel = appViewModel { app -> SmsAccessViewModel(app.settings) }
    val askedBefore by viewModel.askedBefore.collectAsStateWithLifecycle()
    CollectEffects(viewModel.manualModeStored) { navController.finishOnboarding() }
    val requestSms = rememberSmsAccessRequest(askedBefore = askedBefore, onAnswered = viewModel::onAnswered) { result ->
        when (result) {
            SmsAccessResult.Granted -> navController.navigate(context.stepAfterSmsAccess())
            SmsAccessResult.Denied -> viewModel.useManualMode()
            SmsAccessResult.Blocked -> navController.navigate(SmsPermission)
        }
    }
    PrivacyPromiseScreen(onAllow = requestSms, onTypeByHand = viewModel::useManualMode)
}

/** SMS access blocked. It moves on by itself when the person comes back from Settings with access on. */
@Composable
private fun SmsBlockedRoute(navController: NavController) {
    val context = LocalContext.current
    val viewModel = appViewModel { app -> SmsAccessViewModel(app.settings) }
    CollectEffects(viewModel.manualModeStored) { navController.finishOnboarding() }
    LifecycleResumeEffect(Unit) {
        if (context.hasSmsAccess()) {
            navController.navigate(context.stepAfterSmsAccess()) { popUpTo<SmsPermission> { inclusive = true } }
        }
        onPauseOrDispose {}
    }
    SmsBlockedScreen(onOpenSettings = { context.openAppSettings() }, onTypeByHand = viewModel::useManualMode)
}

/**
 * Allow notifications. Either answer to the system dialog, or Not now, goes on to How far back; this step is left
 * off the back stack so Back from How far back returns to Privacy promise.
 */
@Composable
private fun AllowNotificationsRoute(navController: NavController) {
    val toHowFarBack = {
        navController.navigate(HowFarBack) { popUpTo<NotificationPermission> { inclusive = true } }
    }
    val requestNotifications = rememberNotificationRequest(onAnswered = toHowFarBack)
    AllowNotificationsScreen(onAllow = requestNotifications, onNotNow = toHowFarBack)
}

/**
 * How far back with its ViewModel. Pick a date opens the system date picker, which stays open across rotation and
 * a theme change because the state says it is open.
 */
@Composable
private fun HowFarBackRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app -> HowFarBackViewModel(app.settings, app.clock, createSavedStateHandle()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            HowFarBackEffect.FinishOnboarding -> navController.finishOnboarding()
            HowFarBackEffect.Scan -> navController.navigate(Scanning) { launchSingleTop = true }
        }
    }
    HowFarBackScreen(state = state, onEvent = viewModel::onEvent, onBack = { navController.leave(entry) })
    if (state.pickingDate) {
        SystemDatePicker(
            initial = state.pickedDate ?: state.thisMonthStart,
            latest = state.today,
            onPicked = { viewModel.onEvent(HowFarBackEvent.DatePicked(it)) },
            onDismiss = { viewModel.onEvent(HowFarBackEvent.DatePickerClosed) },
        )
    }
}

/** Scanning SMS with its ViewModel. It is replaced by Nothing found or Confirm accounts when the scan ends. */
@Composable
private fun ScanningRoute(navController: NavController) {
    val appContext = LocalContext.current.applicationContext
    val viewModel = appViewModel { app ->
        ScanningViewModel(app.settings, app.accounts, app.expenses, WorkOnboardingScanner(appContext), app.clock)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffects(viewModel.effect) { effect ->
        val next: Any = when (effect) {
            ScanningEffect.NothingFound -> NothingFound
            ScanningEffect.ConfirmAccounts -> ConfirmAccounts
        }
        navController.navigate(next) { popUpTo<Scanning> { inclusive = true } }
    }
    ScanningScreen(state = state, onEvent = viewModel::onEvent)
}

/** Nothing found with its ViewModel. Look further back returns to How far back. */
@Composable
private fun NothingFoundRoute(navController: NavController) {
    val viewModel = appViewModel { app -> NothingFoundViewModel(app.settings, app.clock) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            NothingFoundEffect.FinishOnboarding -> navController.finishOnboarding()
            NothingFoundEffect.LookFurtherBack -> navController.popBackStack<HowFarBack>(inclusive = false)
        }
    }
    NothingFoundScreen(state = state, onEvent = viewModel::onEvent)
}

/** Confirm accounts with its ViewModel. Looks good opens Home. */
@Composable
private fun ConfirmAccountsRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app -> ConfirmAccountsViewModel(app.accounts, app.settings) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffects(viewModel.done) { navController.finishOnboarding() }
    ConfirmAccountsScreen(state = state, onEvent = viewModel::onEvent, onBack = { navController.leave(entry) })
}

/** The step after SMS access is on: Allow notifications when Android asks for it, else How far back. */
private fun Context.stepAfterSmsAccess(): Any = if (needsNotificationPrompt()) NotificationPermission else HowFarBack
