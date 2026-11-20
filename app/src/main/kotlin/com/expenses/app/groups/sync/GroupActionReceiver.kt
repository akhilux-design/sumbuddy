package com.expenses.app.groups.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.sms.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Actions on "Groups" notifications that work without opening the app: Confirm on "Priya marked ₹1,200 as paid"
 * (docs/server.md 9.5, 9.7). Confirming is queued like every group change, so it also works offline; the
 * notification goes once it is confirmed. A notification swiped away says so ([ACTION_TIDY]), and the bundle's
 * summary goes with the last one. Not exported: only the app's own PendingIntents ([GroupNotifications]) reach it.
 * Nothing is logged.
 *
 * Owned by the settle-up area (docs/groups-build-plan.md).
 */
class GroupActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TIDY) {
            GroupNotifications(context).removedByPerson(intent)
            return
        }
        val settlementId = settlementToConfirm(intent) ?: return
        val app = context.appContainer
        val appContext = context.applicationContext
        val pending = goAsync()
        app.appScope.launch {
            try {
                confirm(appContext, app.groups, settlementId)
            } finally {
                pending.finish()
            }
        }
    }

    /** The intent action and extra of Confirm. */
    companion object {
        /** Confirms a settlement another member recorded. */
        const val ACTION_CONFIRM: String = "com.expenses.app.groups.action.CONFIRM_SETTLEMENT"

        /**
         * A notification of the bundle was swiped away (the intent names it): the summary goes once nothing is left
         * under it.
         */
        const val ACTION_TIDY: String = "com.expenses.app.groups.action.TIDY"

        /** The settlement to confirm, a String id. */
        const val EXTRA_SETTLEMENT_ID: String = "com.expenses.app.groups.extra.SETTLEMENT_ID"

        /** The settlement [intent] asks to confirm, or null when it is not a Confirm. */
        internal fun settlementToConfirm(intent: Intent): String? =
            if (intent.action == ACTION_CONFIRM) intent.getStringExtra(EXTRA_SETTLEMENT_ID)?.takeIf { it.isNotBlank() } else null

        /**
         * What Confirm does: confirms [settlementId] (a settlement already confirmed, deleted or gone is left alone) and
         * removes its notification. A failure leaves the notification, so Confirm can be tapped again.
         */
        internal suspend fun confirm(context: Context, groups: GroupsRepository, settlementId: String) {
            try {
                groups.confirmSettlement(settlementId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            GroupNotifications(context).cancelSettlement(settlementId)
        }
    }
}
