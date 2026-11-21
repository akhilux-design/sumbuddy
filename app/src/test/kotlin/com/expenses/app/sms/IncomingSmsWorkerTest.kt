package com.expenses.app.sms

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.SmsKey
import com.expenses.app.data.repository.RoomTransactionRunner
import com.expenses.app.data.repository.TestDatabase
import com.expenses.app.data.repository.TestZone
import com.expenses.app.data.repository.nov
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.ingest.InboxSms
import com.expenses.app.ingest.IngestPipeline
import com.expenses.app.ingest.NewPaymentsHook
import com.expenses.parser.SmsMessage
import com.expenses.parser.SmsParser
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When a read for a new SMS is done and when it tries again (docs/architecture.md, Reading SMS: workers). The worker
 * runs as soon as the SMS broadcast comes, and the default SMS app may write the message to the inbox a moment later,
 * so the worker waits until it reads a message it had not read before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class IncomingSmsWorkerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val folder: File = Files.createTempDirectory("settings").toFile()
    private val test = TestDatabase()
    private val inbox = Inbox()
    private lateinit var settings: SettingsRepository

    /** "Now" for the next run. */
    private var now: Instant = SMS_53_AT

    private val parts = object : IncomingSmsParts {
        override val settings: SettingsRepository get() = this@IncomingSmsWorkerTest.settings
        override val inbox: PagedSmsInbox get() = this@IncomingSmsWorkerTest.inbox
        override val pipeline: IngestPipeline = SmsIngestPipeline(
            parser = SmsParser(),
            transactions = RoomTransactionRunner(test.db),
            expenses = test.expenses,
            accounts = test.accounts,
            cardBills = test.cardBills,
            reviews = test.reviews,
            readSms = test.readSms,
            clock = test.clock,
            parsing = Dispatchers.Unconfined,
        )
        override val clock: Clock get() = Clock.fixed(now, TestZone)
        override val hooks: List<NewPaymentsHook> = emptyList()

        override suspend fun account(id: Long): Account? = test.accounts.get(id)

        override suspend fun planCardBillReminders() = Unit
    }

    @After fun close() {
        test.close()
        folder.deleteRecursively()
    }

    /** Runs [block] with SMS tracking on since 1 Nov and the inbox last read at 22:00 on 16 Nov. */
    private fun workerTest(block: suspend TestScope.() -> Unit) = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder, "settings.preferences_pb") },
        )
        settings.setTrackingMode(TrackingMode.SMS)
        settings.setTrackingStart(nov(1, 0))
        settings.setLastScan(nov(16, 22))
        settings.completeOnboarding()
        block()
    }

    /** One run of the read for an SMS that arrived at [arrivedAt] (a catch-up when null), its [attempt]th try. */
    private suspend fun run(arrivedAt: Instant?, attempt: Int = 0): ListenableWorker.Result {
        val builder = TestListenableWorkerBuilder<IncomingSmsWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = IncomingSmsWorker(appContext, workerParameters, parts)
                },
            )
        if (arrivedAt != null) builder.setInputData(IncomingSmsWorker.arrivalInput(arrivedAt))
        return builder.build().doWork()
    }

    /** The "Payment detected" notifications showing, each named by when its SMS arrived. */
    private fun posted(): List<Instant> = shadowOf(manager).allNotifications
        .filter { it.channelId == PaymentNotifications.CHANNEL_ID }
        .map { Instant.ofEpochMilli(it.`when`) }
        .sorted()

    /** The person swipes every notification away, so a second post of one would show again. */
    private fun dismissAll() = manager.cancelAll()

    private suspend fun wasRead(sms: InboxSms): Boolean = test.readSms.alreadyRead(listOf(sms.key())).isNotEmpty()

    private suspend fun waiting(): Int = test.expenses.observeDetected().first().size

    @Test fun anSmsThatArrivesRightAfterAReadOneIsWaitedForAndRead() = workerTest {
        // SMS 53 arrives and is read at once.
        inbox += SMS_53
        now = SMS_53_AT.plusSeconds(1)
        assertEquals(ListenableWorker.Result.success(), run(SMS_53_AT))
        assertEquals(listOf(SMS_53_AT), posted())
        dismissAll()

        // SMS 54 arrives 43 s later. Its read runs 40 ms after the broadcast, before the SMS app wrote it to the inbox:
        // the inbox holds only SMS 53, read before and within two minutes of the arrival. That is not SMS 54.
        now = SMS_54_AT.plusMillis(40)
        assertEquals(ListenableWorker.Result.retry(), run(SMS_54_AT))
        assertTrue(posted().isEmpty())
        assertEquals(false, wasRead(SMS_54))

        // The next try finds it: read, waiting on Home, and one "Payment detected".
        inbox += SMS_54
        now = SMS_54_AT.plusSeconds(10)
        assertEquals(ListenableWorker.Result.success(), run(SMS_54_AT, attempt = 1))
        assertTrue(wasRead(SMS_54))
        assertEquals(2, waiting())
        assertEquals(listOf(SMS_54_AT), posted())
    }

    @Test fun aLoneSmsWrittenLateIsWaitedForThenRead() = workerTest {
        now = SMS_54_AT.plusMillis(40)
        assertEquals(ListenableWorker.Result.retry(), run(SMS_54_AT))

        inbox += SMS_54
        now = SMS_54_AT.plusSeconds(10)
        assertEquals(ListenableWorker.Result.success(), run(SMS_54_AT, attempt = 1))
        assertTrue(wasRead(SMS_54))
        assertEquals(listOf(SMS_54_AT), posted())
    }

    @Test fun whenNothingNewEverComesItStopsAfterTheLastTry() = workerTest {
        inbox += SMS_53
        now = SMS_53_AT.plusSeconds(1)
        run(SMS_53_AT)
        dismissAll()

        // SMS 54 never reaches the inbox (the SMS app dropped it): every try but the last asks to try again.
        for (attempt in 0 until IncomingSmsWorker.MAX_ATTEMPTS) {
            now = SMS_54_AT.plusSeconds(10L * attempt)
            assertEquals("try $attempt", ListenableWorker.Result.retry(), run(SMS_54_AT, attempt))
        }
        assertEquals(ListenableWorker.Result.success(), run(SMS_54_AT, IncomingSmsWorker.MAX_ATTEMPTS))
        assertTrue(posted().isEmpty())
        assertEquals(1, waiting())
    }

    @Test fun twoSmsReadByTheFirstReadAreNotifiedOnce() = workerTest {
        // Both are in the inbox when the read for SMS 53 runs (it was queued late): it reads both.
        inbox += SMS_53
        inbox += SMS_54
        now = SMS_54_AT.plusSeconds(1)
        assertEquals(ListenableWorker.Result.success(), run(SMS_53_AT))
        assertEquals(listOf(SMS_53_AT, SMS_54_AT), posted())
        dismissAll()

        // The read for SMS 54 finds nothing it had not read: it tries again until its last try and posts nothing.
        for (attempt in 0 until IncomingSmsWorker.MAX_ATTEMPTS) {
            now = SMS_54_AT.plusSeconds(2 + 10L * attempt)
            assertEquals("try $attempt", ListenableWorker.Result.retry(), run(SMS_54_AT, attempt))
        }
        assertEquals(ListenableWorker.Result.success(), run(SMS_54_AT, IncomingSmsWorker.MAX_ATTEMPTS))
        assertTrue(posted().isEmpty())
        assertEquals(2, waiting())
    }

    @Test fun aCatchUpReadsWhatIsNewAndNeverWaits() = workerTest {
        inbox += SMS_53
        now = SMS_53_AT.plusSeconds(1)
        run(SMS_53_AT)
        dismissAll()

        // Nothing new: done at once, as before.
        now = SMS_54_AT
        assertEquals(ListenableWorker.Result.success(), run(arrivedAt = null))
        assertTrue(posted().isEmpty())

        // A message that arrived while nothing read it: read and notified, done at once.
        inbox += SMS_54
        now = SMS_54_AT.plusSeconds(60)
        assertEquals(ListenableWorker.Result.success(), run(arrivedAt = null))
        assertTrue(wasRead(SMS_54))
        assertEquals(listOf(SMS_54_AT), posted())
    }

    @Test fun theWorkersInputIsOnlyTheArrivalTime() {
        assertEquals(mapOf("arrived_at" to SMS_54_AT.toEpochMilli()), IncomingSmsWorker.arrivalInput(SMS_54_AT).keyValueMap)
    }

    /** An inbox that grows as the SMS app writes messages to it. */
    private class Inbox : PagedSmsInbox {
        private val messages = ArrayList<InboxSms>()

        operator fun plusAssign(sms: InboxSms) {
            messages += sms
        }

        override fun canRead(): Boolean = true

        override suspend fun count(from: Instant, until: Instant?): Int = inRange(from, until).size

        override suspend fun forEachPage(
            from: Instant,
            until: Instant?,
            pageSize: Int,
            onPage: suspend (List<InboxSms>) -> Unit,
        ) {
            inRange(from, until).chunked(pageSize).forEach { onPage(it) }
        }

        private fun inRange(from: Instant, until: Instant?): List<InboxSms> = messages
            .filter { it.message.receivedAtMillis >= from.toEpochMilli() }
            .filter { until == null || it.message.receivedAtMillis < until.toEpochMilli() }
            .sortedBy { it.message.receivedAtMillis }
    }

    private companion object {
        const val HDFC = "VM-HDFCBK"

        /** SMS 53 arrived at 23:00:33 on 16 Nov; SMS 54, 43 s later. */
        val SMS_53_AT: Instant = nov(16, 23, 0).plusSeconds(33)
        val SMS_54_AT: Instant = SMS_53_AT.plusSeconds(43)

        // Message texts follow the parser's samples; names and numbers are made up.
        val SMS_53 = sms(
            53,
            "Rs.450.00 debited from A/c XX4521 on 16-11-26 to VPA swiggy@icici (UPI Ref No 632000000053). -HDFC Bank",
            SMS_53_AT,
        )
        val SMS_54 = sms(
            54,
            "Rs.120.00 debited from A/c XX4521 on 16-11-26 to VPA chai@ybl (UPI Ref No 632000000054). -HDFC Bank",
            SMS_54_AT,
        )

        fun sms(id: Long, body: String, at: Instant) =
            InboxSms(providerId = id, message = SmsMessage(sender = HDFC, body = body, receivedAtMillis = at.toEpochMilli()))

        fun InboxSms.key() = SmsKey(providerId, Instant.ofEpochMilli(message.receivedAtMillis), message.sender)
    }
}
