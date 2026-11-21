package com.expenses.app.ui.theme

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.expenses.app.R

/**
 * The look every SumBuddy notification shares, as the design draws it (02-everyday/04, 53, 54 and 146): the "₹" of the
 * design's App badge as the small icon, and the brand colour (`color/brand/primary`, [R.color.brand_primary]) as the
 * notification's colour. One small icon for the whole app, so the status bar shows the app, not the kind of message.
 *
 * What Android does with the colour on Android 12 and later: it fills the circle behind the small icon with it (made
 * darker or lighter first when it would not stand out from the notification's background), and draws the "₹" in the
 * notification's background colour inside it. The app name keeps the system's secondary text colour, and the action
 * labels the system's accent colour: the colour set here does not reach them. The notification is not colourised.
 */
fun NotificationCompat.Builder.appLook(context: Context): NotificationCompat.Builder =
    setSmallIcon(R.drawable.ic_notification).setColor(ContextCompat.getColor(context, R.color.brand_primary))
