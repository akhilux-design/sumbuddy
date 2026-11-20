package com.expenses.app.ui.screens.you

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.screens.onboarding.PrivacyPromiseScreen
import com.expenses.app.ui.screens.onboarding.SmsAccessResult
import com.expenses.app.ui.screens.onboarding.hasSmsAccess
import com.expenses.app.ui.screens.onboarding.needsNotificationPrompt
import com.expenses.app.ui.screens.onboarding.openAppSettings
import com.expenses.app.ui.screens.onboarding.rememberNotificationRequest
import com.expenses.app.ui.screens.onboarding.rememberSmsAccessRequest
import java.time.Clock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Holds turning SMS tracking on after onboarding: whether the person said no to Android's SMS dialog before, and
 * switching tracking on once SMS access is allowed.
 *
 * @param settings the person's settings.
 * @param clock gives "now", the new tracking start.
 */
class TurnOnSmsTrackingViewModel(private val settings: SettingsRepository, private val clock: Clock) : ViewModel() {
    /** True once the person said no to Android's SMS dialog, in this session or an earlier one. */
    val askedBefore: StateFlow<Boolean> = settings.settings.map { it.smsAskedBefore }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The person said no to Android's SMS dialog. */
    fun onAnswered() {
        viewModelScope.launch { settings.markSmsAsked() }
    }

    /** SMS access is allowed: tracking starts now. */
    fun turnOn() {
        viewModelScope.launch { settings.turnSmsTrackingOn(clock) }
    }
}

/**
 * The Privacy promise before Android's SMS dialog, for turning SMS tracking on from the Home tip or the switch on
 * You, so the person always reads what is read and kept right before the system asks. Allowed turns tracking on
 * (and asks for notifications on Android 13 and later); Don't allow and "Not now" leave tracking off. When Android
 * no longer shows the dialog, the app's page in Settings opens, and tracking turns on once the person comes back
 * with access on.
 *
 * @param onDone leaves the screen.
 */
@Composable
internal fun TurnOnSmsTrackingRoute(onDone: () -> Unit) {
    val context = LocalContext.current
    val viewModel = appViewModel { app -> TurnOnSmsTrackingViewModel(app.settings, app.clock) }
    val askedBefore by viewModel.askedBefore.collectAsStateWithLifecycle()
    var waitingForSettings by rememberSaveable { mutableStateOf(false) }
    val requestNotifications = rememberNotificationRequest(onAnswered = onDone)
    val granted = {
        viewModel.turnOn()
        // Payment detected notifications need their own permission on Android 13 and later.
        if (context.needsNotificationPrompt()) requestNotifications() else onDone()
    }
    LifecycleResumeEffect(waitingForSettings) {
        if (waitingForSettings && context.hasSmsAccess()) {
            waitingForSettings = false
            granted()
        }
        onPauseOrDispose {}
    }
    val requestSms = rememberSmsAccessRequest(askedBefore = askedBefore, onAnswered = viewModel::onAnswered) { result ->
        when (result) {
            SmsAccessResult.Granted -> granted()
            SmsAccessResult.Denied -> onDone()
            SmsAccessResult.Blocked -> {
                waitingForSettings = true
                context.openAppSettings()
            }
        }
    }
    PrivacyPromiseScreen(onAllow = requestSms, onTypeByHand = onDone)
}
