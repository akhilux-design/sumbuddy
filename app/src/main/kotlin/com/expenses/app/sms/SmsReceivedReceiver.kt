package com.expenses.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.time.Instant

/**
 * Hears every incoming SMS (SMS_RECEIVED, sent only by the system, which holds BROADCAST_SMS) and queues an
 * [IncomingSmsWorker], so parsing and storing never run on the main thread.
 *
 * Only times are handed on: when the SMS arrived, and when the network's service centre stamped its parts (the time
 * the default SMS app records as the message's `date_sent`), so the worker can tell this SMS from another one in the
 * inbox. The text the broadcast carries is not kept. The worker reads the message from the inbox, where the default
 * SMS app stores it with all its parts joined, so a long bank SMS sent in several parts is read as one message and its
 * text never goes into WorkManager's queue on disk. Several parts of one message arrive in one broadcast and queue one
 * read.
 */
class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        IncomingSmsWorker.enqueue(context, arrivedAt = context.appContainer.clock.instant(), sentAt = sentTimes(intent))
    }

    /** The service-centre times of the message's parts, without repeats; empty when the broadcast cannot be read. */
    private fun sentTimes(intent: Intent): List<Instant> =
        try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty()
                .mapNotNull { part -> part?.timestampMillis?.takeIf { it > 0 } }
                .distinct()
                .map(Instant::ofEpochMilli)
        } catch (_: RuntimeException) {
            emptyList()
        }
}
