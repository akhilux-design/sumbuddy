package com.expenses.app.sms

import android.app.Application
import android.content.Intent
import android.provider.Telephony
import androidx.work.Data
import java.time.Instant
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** From the review of f8cf0b9: how the receiver reads the service-centre times. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SmsReceivedReceiverAdversarialTest {
    private val receiver = SmsReceivedReceiver()

    @Suppress("UNCHECKED_CAST")
    private fun sentTimes(intent: Intent): List<Instant> {
        val method = SmsReceivedReceiver::class.java.getDeclaredMethod("sentTimes", Intent::class.java)
        method.isAccessible = true
        return method.invoke(receiver, intent) as List<Instant>
    }

    private fun smsIntent() = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)

    @Test fun noExtrasGivesNoTimes() {
        assertEquals(emptyList<Instant>(), sentTimes(smsIntent()))
    }

    @Test fun pdusOfTheWrongTypeGiveNoTimes() {
        val intent = smsIntent().putExtra("pdus", arrayOf<Any>("not a pdu")).putExtra("format", "3gpp")
        assertEquals(emptyList<Instant>(), sentTimes(intent))
    }

    @Test fun aGarbagePduGivesNoTimes() {
        val intent = smsIntent().putExtra("pdus", arrayOf<Any>(byteArrayOf(1, 2, 3))).putExtra("format", "3gpp")
        assertEquals(emptyList<Instant>(), sentTimes(intent))
    }

    @Test fun oneGsmPartGivesItsServiceCentreTime() {
        val intent = smsIntent().putExtra("pdus", arrayOf<Any>(pdu(second = 14))).putExtra("format", "3gpp")
        assertEquals(listOf(at(14)), sentTimes(intent))
    }

    @Test fun withoutTheFormatExtraThereAreNoTimes() {
        // The 2-minute rule then decides, as before the commit.
        val intent = smsIntent().putExtra("pdus", arrayOf<Any>(pdu(second = 14)))
        assertEquals(emptyList<Instant>(), sentTimes(intent))
    }

    @Test fun twoPartsGiveBothTimesWithoutRepeats() {
        val intent = smsIntent()
            .putExtra("pdus", arrayOf<Any>(pdu(second = 14), pdu(second = 15), pdu(second = 15)))
            .putExtra("format", "3gpp")
        assertEquals(listOf(at(14), at(15)), sentTimes(intent))
    }

    @Test fun theLargestInputFitsInWorkManagerData() {
        // A concatenated SMS has at most 255 parts.
        val times = (0 until 255).map { at(0).plusSeconds(it.toLong()) }
        val input: Data = IncomingSmsWorker.arrivalInput(at(0), times)
        val bytes = input.toByteArray()
        assertTrue("size ${bytes.size}", bytes.size < Data.MAX_DATA_BYTES)
        println("255 service-centre times take ${bytes.size} of ${Data.MAX_DATA_BYTES} bytes")
        // As WorkManager stores it on disk and gives it back to the worker.
        val back = Data.fromByteArray(bytes)
        assertEquals(times.map(Instant::toEpochMilli), back.getLongArray("sent_at")!!.toList())
        assertEquals(at(0).toEpochMilli(), back.getLong("arrived_at", 0))
    }

    private companion object {
        /** 2026-11-16 23:01:<second> +05:30, as the SMS-DELIVER service-centre time stamp gives it. */
        fun at(second: Int): Instant = OffsetDateTime.parse("2026-11-16T23:01:%02d+05:30".format(second)).toInstant()

        /** A GSM SMS-DELIVER PDU from 9876543210 with text "A", stamped 2026-11-16 23:01:<second> +05:30. */
        fun pdu(second: Int): ByteArray {
            fun swapped(n: Int): Int = ((n % 10) shl 4) or (n / 10)
            val bytes = intArrayOf(
                0x00, // no SMSC
                0x04, // SMS-DELIVER, no more messages
                0x0A, 0x81, 0x89, 0x67, 0x45, 0x23, 0x01, // originating address, 10 digits
                0x00, // PID
                0x00, // DCS: GSM 7-bit
                swapped(26), swapped(11), swapped(16), swapped(23), swapped(1), swapped(second), swapped(22), // +22 quarter hours
                0x01, 0x41, // one character, "A"
            )
            return ByteArray(bytes.size) { bytes[it].toByte() }
        }
    }
}
