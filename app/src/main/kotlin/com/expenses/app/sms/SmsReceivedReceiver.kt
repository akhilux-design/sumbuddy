package com.expenses.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Hears every incoming SMS (SMS_RECEIVED, sent only by the system, which holds BROADCAST_SMS) and queues an
 * [IncomingSmsWorker], so parsing and storing never run on the main thread.
 *
 * Only the arrival time is handed on. The worker reads the message from the inbox, where the default SMS app stores
 * it with all its parts joined, so a long bank SMS sent in several parts is read as one message and its text never
 * goes into WorkManager's queue on disk. Several parts of one message arrive in one broadcast and queue one read.
 */
class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        IncomingSmsWorker.enqueue(context, arrivedAt = context.appContainer.clock.instant())
    }
}
