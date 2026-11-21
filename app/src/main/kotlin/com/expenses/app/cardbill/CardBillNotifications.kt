package com.expenses.app.cardbill

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.expenses.app.MainActivity
import com.expenses.app.R
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.format.shortName
import com.expenses.app.ui.nav.AppLink
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The "Card bills" notifications (docs/card-bills.md 2.7; design 02-everyday/04 and 146): "HDFC Millennia bill due in
 * 3 days" / "Friends owe you ₹8,200 of the ₹24,300.", with "Remind friends" when friends owe part of it. Only the
 * card's name, the amounts, the due date and friends' first names show; never an SMS. A tap opens the Card bill view;
 * Remind friends opens it and runs Remind everyone once.
 */
object CardBillNotifications {
    /** The id of the "Card bills" channel. */
    const val CHANNEL_ID: String = "card_bills"

    /** The tag of every card bill notification; its id is the bill's ([notificationId]). */
    const val TAG: String = "card_bill"

    private const val REQUEST_OPEN = 0
    private const val REQUEST_REMIND = 1
    private const val REQUESTS_PER_BILL = 2
    private const val NO_ICON = 0

    /** True when the app may post notifications now: allowed on Android 13 and later, and not turned off. */
    fun enabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** The notification's title on [today] for a bill due on [dueDate]: "HDFC Millennia bill due in 3 days". */
    fun title(context: Context, detail: CardBillDetail, dueDate: LocalDate, today: LocalDate): String {
        val name = detail.cardName ?: context.getString(R.string.onboarding_account_credit_card)
        val days = ChronoUnit.DAYS.between(today, dueDate).toInt()
        return when {
            days <= 0 -> context.getString(R.string.cardbill_notify_due_today, name)
            days == 1 -> context.getString(R.string.cardbill_notify_due_tomorrow, name)
            else -> context.resources.getQuantityString(R.plurals.cardbill_notify_due_in, days, name, days)
        }
    }

    /**
     * The notification's text: "Friends owe you ₹8,200 of the ₹24,300.", "Rahul owes you ₹4,100 of the ₹24,300." for
     * one friend, or "Pay ₹24,300 by 21 Nov." when nobody owes part of it or nobody is signed in.
     */
    fun body(context: Context, detail: CardBillDetail, dueDate: LocalDate): String {
        val total = formatRupees(detail.bill.totalDueMinor)
        val friends = detail.friends
        return when (friends.size) {
            0 -> context.getString(R.string.cardbill_notify_pay_by, total, dueDate.format(dayMonth()))
            1 -> context.getString(R.string.cardbill_notify_friend, shortName(friends.single().name), formatRupees(detail.friendsMinor), total)
            else -> context.getString(R.string.cardbill_notify_friends, formatRupees(detail.friendsMinor), total)
        }
    }

    /**
     * Posts the reminder of [detail] on [today]. Does nothing without a due date, or when notifications are not
     * allowed ([enabled]).
     */
    fun post(context: Context, detail: CardBillDetail, today: LocalDate) {
        val dueDate = detail.bill.dueDate ?: return
        if (!enabled(context)) return
        createChannel(context)
        val billId = detail.bill.id
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_card_line)
            .setContentTitle(title(context, detail, dueDate, today))
            .setContentText(body(context, detail, dueDate))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open(context, billId, remind = false))
        if (detail.friendsMinor > 0) {
            builder.addAction(NO_ICON, context.getString(R.string.cardbill_notify_remind), open(context, billId, remind = true))
        }
        try {
            NotificationManagerCompat.from(context).notify(TAG, notificationId(billId), builder.build())
        } catch (_: SecurityException) {
            // Notifications were turned off between the check and the post: nothing to show.
        }
    }

    /** Removes the notification of bill [billId], if it shows. */
    fun cancel(context: Context, billId: Long) {
        NotificationManagerCompat.from(context).cancel(TAG, notificationId(billId))
    }

    /** Removes every card bill notification whose bill is not in [keep]. */
    fun cancelAllBut(context: Context, keep: Set<Long>) {
        val kept = keep.mapTo(HashSet(), ::notificationId)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { it.tag == TAG && it.id !in kept }
            .forEach { manager.cancel(TAG, it.id) }
    }

    /** Creates the "Card bills" channel; safe to call again. */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.cardbill_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.cardbill_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** The notification id of bill [billId]. */
    fun notificationId(billId: Long): Int = billId.hashCode()

    private fun open(context: Context, billId: Long, remind: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        billId.hashCode() * REQUESTS_PER_BILL + if (remind) REQUEST_REMIND else REQUEST_OPEN,
        MainActivity.openLinkIntent(context, AppLink.CardBill(billId, remind)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun dayMonth(): DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
}
