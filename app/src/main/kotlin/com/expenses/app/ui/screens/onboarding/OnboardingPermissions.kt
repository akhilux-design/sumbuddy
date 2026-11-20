package com.expenses.app.ui.screens.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** The SMS permissions asked for together: reading the inbox, and hearing about new messages. One system dialog. */
private val SmsPermissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)

/** How the person answered the system's SMS dialog. */
internal enum class SmsAccessResult {
    /** SMS access is on. */
    Granted,

    /** They chose Don't allow; Android could ask again later. */
    Denied,

    /** Android will not show the dialog again ("don't ask again"): only Settings can turn SMS access on. */
    Blocked,
}

/** True when the app may read SMS. */
internal fun Context.hasSmsAccess(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

/**
 * True when Allow notifications should be shown: on Android 13 and later, while notifications are not allowed.
 * Older versions allow notifications without asking.
 */
internal fun Context.needsNotificationPrompt(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

/** Opens this app's page in the phone's Settings, where its permissions can be changed. */
internal fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

/**
 * Prepares the system dialog that asks for SMS access, and returns the function that shows it. [onResult] is
 * called with the answer. When access is already on, Android answers at once without a dialog.
 *
 * Android does not say directly whether the person tapped Don't allow, dismissed the dialog, or will no longer be
 * asked, so it is read from whether Android would explain the request before and after asking: after a first Don't
 * allow it would; after a second one it stops asking. A dialog closed without an answer is not reported; when that
 * happens twice in a row, Android is no longer showing the dialog, so the answer is [SmsAccessResult.Blocked].
 *
 * Whether the person said no before, in an earlier screen or session, is kept on the phone ([askedBefore],
 * [onAnswered]): when they did and Android would not explain the request, Android shows no dialog any more, so the
 * answer is [SmsAccessResult.Blocked] at once, without a silent first tap.
 *
 * @param askedBefore true when the person said no to the dialog before.
 * @param onAnswered called when the person said no to the dialog, to remember it.
 * @param onResult called with the answer.
 */
@Composable
internal fun rememberSmsAccessRequest(
    askedBefore: Boolean,
    onAnswered: () -> Unit,
    onResult: (SmsAccessResult) -> Unit,
): () -> Unit {
    val activity = LocalActivity.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnAnswered by rememberUpdatedState(onAnswered)
    val currentAskedBefore by rememberUpdatedState(askedBefore)
    var explainedBefore by rememberSaveable { mutableStateOf(false) }
    var unanswered by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        val explainedNow = activity?.shouldExplainSms() == true
        val result = when {
            answers[Manifest.permission.READ_SMS] == true -> SmsAccessResult.Granted
            explainedNow -> SmsAccessResult.Denied
            explainedBefore || unanswered > 0 || currentAskedBefore -> SmsAccessResult.Blocked
            else -> null
        }
        if (result == null) {
            unanswered++
        } else {
            unanswered = 0
            if (result != SmsAccessResult.Granted) currentOnAnswered()
            currentOnResult(result)
        }
    }
    return {
        val granted = activity?.hasSmsAccess() == true
        explainedBefore = activity?.shouldExplainSms() == true
        if (!granted && currentAskedBefore && !explainedBefore) {
            currentOnResult(SmsAccessResult.Blocked)
        } else {
            launcher.launch(SmsPermissions)
        }
    }
}

/** True when Android would show its explanation before asking for SMS access: the person said no once before. */
private fun Activity.shouldExplainSms(): Boolean =
    ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_SMS)

/**
 * Prepares the system dialog that asks to show notifications (Android 13 and later), and returns the function
 * that shows it. [onAnswered] is called once the person answered, whatever the answer: the app works either way.
 * On older versions the function calls [onAnswered] straight away.
 */
@Composable
internal fun rememberNotificationRequest(onAnswered: () -> Unit): () -> Unit {
    val currentOnAnswered by rememberUpdatedState(onAnswered)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        currentOnAnswered()
    }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            currentOnAnswered()
        }
    }
}
