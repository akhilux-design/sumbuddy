package com.expenses.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.expenses.app.data.model.ExpenseStatus
import kotlinx.coroutines.launch

/**
 * Handles Ignore on a "Payment detected" notification without opening the app: it marks the payment not an expense.
 * A payment the person already decided about in the app is left alone. The notification is removed either way.
 *
 * Not exported: only the app's own notification reaches it.
 */
class PaymentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val expenseId = intent.getLongExtra(EXTRA_EXPENSE_ID, NO_ID)
        if (expenseId == NO_ID || intent.action != ACTION_IGNORE) return
        val app = context.appContainer
        val appContext = context.applicationContext
        val pending = goAsync()
        app.appScope.launch {
            try {
                val expense = app.expenses.get(expenseId)
                if (expense != null && expense.status == ExpenseStatus.DETECTED) app.expenses.ignore(expenseId)
                PaymentNotifications.cancel(appContext, expenseId)
            } finally {
                pending.finish()
            }
        }
    }

    /** Intent action and extra of the Ignore button. */
    companion object {
        /** Marks the payment not an expense. */
        const val ACTION_IGNORE: String = "com.expenses.app.sms.action.IGNORE_PAYMENT"

        /** The id of the detected payment, a Long. */
        const val EXTRA_EXPENSE_ID: String = "com.expenses.app.sms.extra.EXPENSE_ID"

        private const val NO_ID = -1L
    }
}
