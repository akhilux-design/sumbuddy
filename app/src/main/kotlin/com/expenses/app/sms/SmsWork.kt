package com.expenses.app.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.expenses.app.ExpensesApplication
import com.expenses.app.R
import com.expenses.app.di.AppContainer
import com.expenses.app.ui.theme.appLook
import java.time.Instant

/** The app's dependencies, for receivers and workers, which get no ViewModel factory. */
internal val Context.appContainer: AppContainer
    get() = (applicationContext as ExpensesApplication).container

/**
 * Drops, in one transaction, everything read from SMS that arrived before [before]: payments, card bills, Needs
 * review items, the record that those messages were read, and the accounts nothing refers to any more. Used by the
 * onboarding scan when it starts again from a later day.
 */
internal suspend fun AppContainer.forgetSmsBefore(before: Instant) {
    transactions.run {
        expenses.deleteSmsBefore(before)
        cardBills.deleteBefore(before)
        reviews.deleteBefore(before)
        readSms.forgetBefore(before)
        accounts.deleteUnused()
    }
}

/**
 * The quiet notification shown while SMS are read, needed only where WorkManager runs expedited work as a foreground
 * service (Android 11 and older). It shows no message content.
 */
internal fun readingForegroundInfo(context: Context, notificationId: Int): ForegroundInfo {
    val channel = NotificationChannel(
        READING_CHANNEL_ID,
        context.getString(R.string.sms_channel_reading),
        NotificationManager.IMPORTANCE_LOW,
    ).apply { description = context.getString(R.string.sms_channel_reading_description) }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    val notification = NotificationCompat.Builder(context, READING_CHANNEL_ID)
        .appLook(context)
        .setContentTitle(context.getString(R.string.sms_reading_title))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setSilent(true)
        .build()
    return ForegroundInfo(notificationId, notification)
}

private const val READING_CHANNEL_ID = "reading_sms"
