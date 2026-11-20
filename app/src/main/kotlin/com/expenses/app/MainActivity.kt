package com.expenses.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expenses.app.groups.sync.GroupNotifications
import com.expenses.app.ui.nav.AppLink
import com.expenses.app.ui.nav.PayLinks
import com.expenses.app.ui.shell.AppRoot
import com.expenses.app.ui.theme.AppTheme
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

/**
 * The app's only activity. It draws edge to edge with transparent system bars whose icons are light on the Dark
 * theme and dark on the Light theme. The theme follows the phone's dark mode setting; changing that setting
 * recreates the activity, so the bar icons follow it too.
 *
 * Screens are opened from outside in two ways, each an [AppLink]:
 * - The app's own notifications, built with [openLinkIntent] (or [openExpenseIntent]). The activity is exported as the
 *   launcher, so it reads a link from extras only on intents sent to the non-exported `NotificationTarget` alias,
 *   which no other app can start. A "Groups" notification is told that it was tapped ([GroupNotifications]).
 * - A friend's pay link `https://<LINK_HOST>/pay/<token>` (the App Link intent filter, docs/server.md 9.8): only that
 *   exact shape is accepted, and it opens Join group. The token is never logged. A friend who installed the app from
 *   the link goes through onboarding first: the link waits (also across recreation, in the saved state) and Join
 *   group opens when onboarding is done ([AppRoot]).
 *
 * The activity is single top, so a tap while the app is open arrives in [onNewIntent]. A start from Recents carries
 * the intent that first started the task; its link was handled then, so it is not followed again.
 */
class MainActivity : ComponentActivity() {
    private val openLink = MutableStateFlow<AppLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After recreation the intent is the one already handled, so it is read only on a fresh start. A link still
        // waiting (a friend's pay link kept until onboarding is done) comes back from the saved state.
        if (savedInstanceState == null) {
            readLink(intent)
        } else {
            savedInstanceState.getString(STATE_WAITING_LINK)?.let { text ->
                openLink.value = runCatching { LinkJson.decodeFromString(AppLink.serializer(), text) }.getOrNull()
            }
        }
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val dark = nightMode == Configuration.UI_MODE_NIGHT_YES
        val bars = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            val toOpen by openLink.collectAsStateWithLifecycle()
            AppTheme {
                AppRoot(openLink = toOpen, onOpenLinkHandled = { openLink.value = null })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Every start and return to the foreground: groups sync when signed in (docs/server.md 5.2).
        (application as ExpensesApplication).container.onForeground()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        openLink.value?.let { outState.putString(STATE_WAITING_LINK, LinkJson.encodeToString(AppLink.serializer(), it)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readLink(intent)
    }

    private fun readLink(intent: Intent?) {
        val link = linkOf(intent) ?: return
        openLink.value = link
        // A tap on one of the app's own notifications. A "Groups" notification says which one it was, so its bundle's
        // summary goes with the last one now, without waiting for a sync (GroupNotifications.removedByPerson).
        if (intent?.component?.className == NOTIFICATION_TARGET) GroupNotifications(this).removedByPerson(intent)
    }

    private fun linkOf(intent: Intent?): AppLink? {
        if (intent == null) return null
        // Opened again from Recents: the task's first intent comes back, and its screen was already shown.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.data ?: return null
            val token = PayLinks.tokenOf(data.scheme, data.host, data.path, BuildConfig.LINK_HOST) ?: return null
            return AppLink.Join(token)
        }
        if (intent.component?.className != NOTIFICATION_TARGET) return null
        intent.getStringExtra(EXTRA_LINK)?.let { text ->
            return runCatching { LinkJson.decodeFromString(AppLink.serializer(), text) }.getOrNull()
        }
        val id = intent.getLongExtra(EXTRA_OPEN_EXPENSE_ID, NO_ID)
        return if (id != NO_ID) AppLink.Payment(id, split = intent.getBooleanExtra(EXTRA_SPLIT, false)) else null
    }

    /** Intents that open the app at a given screen. */
    companion object {
        /** Intent extra: the [AppLink] to open, as JSON. */
        const val EXTRA_LINK: String = "com.expenses.app.extra.LINK"

        /** Intent extra: id of a detected payment to open in Add to expenses (notifications posted by older builds). */
        const val EXTRA_OPEN_EXPENSE_ID: String = "com.expenses.app.extra.OPEN_EXPENSE_ID"

        /** Intent extra: true when the payment was opened by Split (notifications posted by older builds). */
        const val EXTRA_SPLIT: String = "com.expenses.app.extra.SPLIT"

        private const val NO_ID = -1L

        /** Saved state: a link not followed yet (a friend's pay link waits while onboarding runs). */
        private const val STATE_WAITING_LINK = "waiting_link"

        /** The not exported activity alias that notification taps go through (see the manifest). */
        private const val NOTIFICATION_TARGET = "com.expenses.app.NotificationTarget"

        private val LinkJson = Json { ignoreUnknownKeys = true }

        /**
         * An intent that opens [link] once onboarding is done, for notification taps and actions.
         *
         * Every different link gets different intent data, so Android never treats two of them as the same intent:
         * two PendingIntents made from different links stay apart whatever request codes their callers pick, and
         * `FLAG_UPDATE_CURRENT` on one never rewrites another's extras. The same link gives the same data. The data is
         * a digest of the link, so a token or an id never shows in the system's own log of started activities.
         */
        fun openLinkIntent(context: Context, link: AppLink): Intent {
            val json = LinkJson.encodeToString(AppLink.serializer(), link)
            return Intent()
                .setComponent(ComponentName(context, NOTIFICATION_TARGET))
                .setData(linkIdentity(json))
                .putExtra(EXTRA_LINK, json)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        /** `app-link:open#<digest>`: the scheme part is what logs print; the digest is in the fragment, which they do not. */
        private fun linkIdentity(json: String): Uri {
            val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
            val hex = digest.take(LINK_DIGEST_BYTES).joinToString("") { "%02x".format(it) }
            return Uri.fromParts(LINK_SCHEME, LINK_PART, hex)
        }

        private const val LINK_SCHEME = "app-link"
        private const val LINK_PART = "open"
        private const val LINK_DIGEST_BYTES = 16

        /**
         * An intent that opens the detected payment [expenseId]: Add to expenses, or Split it when [split] (the
         * notification's Split button; signed out, Sign in opens first).
         */
        fun openExpenseIntent(context: Context, expenseId: Long, split: Boolean = false): Intent =
            openLinkIntent(context, AppLink.Payment(expenseId, split))
    }
}
