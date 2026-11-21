package com.expenses.app.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.expenses.app.MainActivity
import com.expenses.app.R
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.payee
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.format.payeeWording
import com.expenses.app.ui.nav.AppLink
import com.expenses.app.ui.theme.appLook
import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Currency
import java.util.Locale

/**
 * The "Payment detected" notifications: one per new detected debit, with Add, Split and Ignore. And, in their place,
 * the notification of a payment that split itself by an automatic rule ([showAutoSplit]).
 *
 * The notification shows only the amount, the merchant and the account, never the SMS text. Add, Split and a tap on
 * the notification open the app at Add to expenses for the payment, the same as Add and Split on Home, so the person
 * sees the category and who it counts for before it is added. Ignore is handled by [PaymentActionReceiver] without
 * opening the app.
 */
object PaymentNotifications {
    /** The id of the "Payment detected" channel. */
    const val CHANNEL_ID: String = "payment_detected"

    /** The id of the "Automatic splits" channel. */
    const val AUTO_SPLIT_CHANNEL_ID: String = "payment_auto_split"

    private const val TAG = "payment_detected"
    private const val AUTO_SPLIT_TAG = "payment_auto_split"
    private const val REQUEST_OPEN = 0
    private const val REQUEST_SPLIT = 1
    private const val REQUEST_ADD = 2
    private const val REQUEST_IGNORE = 3
    private const val REQUESTS_PER_PAYMENT = 4
    private const val DEFAULT_FRACTION_DIGITS = 2
    private const val NO_ICON = 0

    /**
     * Posts one notification for the detected payment [expense] paid from [account] (null when unknown). Does
     * nothing without POST_NOTIFICATIONS on Android 13 and later, or when the person turned notifications off.
     */
    fun show(context: Context, expense: Expense, account: Account?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val amount = formatAmount(context, expense.amountMinor, expense.currency)
        val payment = expense.payee?.let { context.getString(expense.payeeWording().amountAndPayee, amount, it) } ?: amount
        val title = context.getString(R.string.sms_notification_title, payment)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .appLook(context)
            .setContentTitle(title)
            .setContentText(accountLabel(context, account))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen((expense.smsReceivedAt ?: expense.occurredAt).toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, expense.id, REQUEST_OPEN, split = false))
            .addAction(
                NO_ICON,
                context.getString(R.string.sms_notification_action_add),
                openApp(context, expense.id, REQUEST_ADD, split = false),
            )
            .addAction(
                NO_ICON,
                context.getString(R.string.sms_notification_action_split),
                openApp(context, expense.id, REQUEST_SPLIT, split = true),
            )
            .addAction(
                NO_ICON,
                context.getString(R.string.sms_notification_action_ignore),
                action(context, expense.id, PaymentActionReceiver.ACTION_IGNORE, REQUEST_IGNORE),
            )
            .build()
        NotificationManagerCompat.from(context).notify(TAG, notificationId(expense.id), notification)
    }

    /**
     * Posts the notification of the detected payment [expense] that split itself in group [groupId] by its merchant's
     * automatic rule (not designed): "₹24,000 to Rent · split with Flat 302" / "Your ₹8,000 share counts in November.
     * Nothing to tick." A tap opens the group's page. It takes the place of "Payment detected", so it has no Add,
     * Split or Ignore. Does nothing without POST_NOTIFICATIONS on Android 13 and later.
     *
     * @param groupName the group's name; for a direct split, the friend's name.
     * @param myShareMinor the person's share in paise, the part that counts in [month].
     * @param month the month the share counts in.
     */
    fun showAutoSplit(
        context: Context,
        expense: Expense,
        groupId: String,
        groupName: String,
        myShareMinor: Long,
        month: YearMonth,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val amount = formatAmount(context, expense.amountMinor, expense.currency)
        val payment = expense.payee?.let { context.getString(expense.payeeWording().amountAndPayee, amount, it) } ?: amount
        val monthName = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val notification = NotificationCompat.Builder(context, AUTO_SPLIT_CHANNEL_ID)
            .appLook(context)
            .setContentTitle(context.getString(R.string.sms_auto_split_title, payment, groupName))
            .setContentText(context.getString(R.string.sms_auto_split_text, formatRupees(myShareMinor), monthName))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen((expense.smsReceivedAt ?: expense.occurredAt).toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    requestCode(expense.id, REQUEST_OPEN),
                    MainActivity.openLinkIntent(context, AppLink.Group(groupId)),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        NotificationManagerCompat.from(context).notify(AUTO_SPLIT_TAG, notificationId(expense.id), notification)
    }

    /** Removes the notification of the payment [expenseId], for example once it was added or ignored in the app. */
    fun cancel(context: Context, expenseId: Long) {
        NotificationManagerCompat.from(context).cancel(TAG, notificationId(expenseId))
    }

    /**
     * Removes the notifications of payments that are no longer waiting, for example because they were added or
     * ignored in the app.
     *
     * @param waitingIds ids of the payments still waiting for Add, Split or Ignore.
     */
    fun cancelHandled(context: Context, waitingIds: Set<Long>) {
        val waiting = waitingIds.mapTo(HashSet(), ::notificationId)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { it.tag == TAG && it.id !in waiting }
            .forEach { manager.cancel(TAG, it.id) }
    }

    /** Creates the "Payment detected" and "Automatic splits" channels; safe to call again. */
    fun createChannel(context: Context) {
        val detected = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sms_channel_payment_detected),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.sms_channel_payment_detected_description) }
        val autoSplit = NotificationChannel(
            AUTO_SPLIT_CHANNEL_ID,
            context.getString(R.string.sms_channel_auto_split),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.sms_channel_auto_split_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(detected, autoSplit))
    }

    private fun notificationId(expenseId: Long): Int = expenseId.hashCode()

    private fun requestCode(expenseId: Long, request: Int): Int = expenseId.hashCode() * REQUESTS_PER_PAYMENT + request

    private fun openApp(context: Context, expenseId: Long, request: Int, split: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode(expenseId, request),
            MainActivity.openExpenseIntent(context, expenseId, split),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun action(context: Context, expenseId: Long, action: String, request: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(expenseId, request),
            Intent(context, PaymentActionReceiver::class.java)
                .setAction(action)
                .putExtra(PaymentActionReceiver.EXTRA_EXPENSE_ID, expenseId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** "HDFC Bank card ••4521", the account the payment came from as shown in the design. */
    private fun accountLabel(context: Context, account: Account?): String {
        if (account == null) return context.getString(R.string.sms_notification_account_unknown)
        val name = account.displayName ?: account.bank ?: account.upiId
            ?: return context.getString(R.string.sms_notification_account_unknown)
        val last4 = account.last4 ?: return name
        val isCard = account.type == AccountType.CREDIT_CARD || account.type == AccountType.DEBIT_CARD
        return if (isCard && account.displayName == null) {
            context.getString(R.string.sms_notification_account_card, name, last4)
        } else {
            context.getString(R.string.sms_notification_account_digits, name, last4)
        }
    }

    /** Rupees as "₹450"; any other currency as its code and amount ("USD 12.50"). */
    private fun formatAmount(context: Context, amountMinor: Long, currency: String): String {
        if (currency == Expense.INR) return formatRupees(amountMinor)
        val digits = runCatching { Currency.getInstance(currency).defaultFractionDigits }
            .getOrDefault(DEFAULT_FRACTION_DIGITS)
            .coerceAtLeast(0)
        val amount = BigDecimal.valueOf(amountMinor).movePointLeft(digits).toPlainString()
        return context.getString(R.string.sms_notification_amount_foreign, currency, amount)
    }
}
