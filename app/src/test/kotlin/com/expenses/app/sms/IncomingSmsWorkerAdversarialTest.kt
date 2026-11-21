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

/** From the review of f8cf0b9: the "arrived" rule under crossed reads and old work. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class IncomingSmsWorkerAdversarialTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val folder: File = Files.createTempDirectory("settings").toFile()
    private val test = TestDatabase()
    private val inbox = Inbox()
    private lateinit var settings: SettingsRepository
    private var now: Instant = A_AT

    private val parts = object : IncomingSmsParts {
        override val settings: SettingsRepository get() = this@IncomingSmsWorkerAdversarialTest.settings
        override val inbox: PagedSmsInbox get() = this@IncomingSmsWorkerAdversarialTest.inbox
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

    private suspend fun run(arrivedAt: Instant, attempt: Int = 0, sent: List<Instant> = emptyList()): ListenableWorker.Result =
        TestListenableWorkerBuilder<IncomingSmsWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        IncomingSmsWorker(appContext, workerParameters, parts)
                },
            )
            .setInputData(IncomingSmsWorker.arrivalInput(arrivedAt, sent))
            .build()
            .doWork()

    private fun posted(): List<Instant> = shadowOf(manager).allNotifications
        .filter { it.channelId == PaymentNotifications.CHANNEL_ID }
        .map { Instant.ofEpochMilli(it.`when`) }
        .sorted()

    private suspend fun wasRead(sms: InboxSms): Boolean =
        test.readSms.alreadyRead(listOf(SmsKey(sms.providerId, Instant.ofEpochMilli(sms.message.receivedAtMillis), sms.message.sender))).isNotEmpty()

    /**
     * Bank SMS A and UPI SMS B, stamped in the same second. B's read runs first, while A is in the inbox unread (A's
     * read has not run) and B is not: it takes A for B and stops. A's read then finds A read, tries again, and reads B.
     * Both are read and notified once.
     */
    @Test fun crossedReadsOfSameSecondSmsReadBoth() = workerTest {
        inbox += A
        now = B_AT.plusMillis(30)
        assertEquals(ListenableWorker.Result.success(), run(B_AT, sent = listOf(SAME_SECOND)))
        assertTrue(wasRead(A))

        inbox += B
        now = B_AT.plusMillis(200)
        assertEquals(ListenableWorker.Result.success(), run(A_AT, sent = listOf(SAME_SECOND)))
        assertTrue(wasRead(B))
        assertEquals(listOf(A_AT, B_AT), posted())
    }

    /** Work queued by 0.3.0 (arrival time only) still ends on its SMS by the two-minute rule after the update. */
    @Test fun workQueuedBy030StillEnds() = workerTest {
        inbox += B
        now = B_AT.plusSeconds(5)
        assertEquals(ListenableWorker.Result.success(), run(B_AT))
        assertEquals(listOf(B_AT), posted())
    }

    /**
     * An SMS app that writes the arrival time as date_sent (never the service centre's). On f8cf0b9 the SMS was read
     * and notified on the first run, then the read tried 5 times more. Now the two-minute rule ends the first run, as
     * in a0d0e6b.
     */
    @Test fun dateSentThatIsTheArrivalTimeCostsNoTries() = workerTest {
        inbox += B.copy(sentAt = B_AT)
        now = B_AT.plusMillis(30)
        assertEquals(ListenableWorker.Result.success(), run(B_AT, sent = listOf(SAME_SECOND)))
        assertEquals(listOf(B_AT), posted())
    }

    /**
     * Failed on f8cf0b9 (12 runs). Two SMS ~1 s apart, stamped in different seconds (a bank SMS and a UPI SMS for one
     * payment), and the SMS app writes each a moment after its broadcast (Google Messages: ~0.1-0.2 s, longer than the
     * worker takes to start). The reads cross: P's first run finds nothing; Q's first run reads P; P's retry reads Q. Each
     * read only ever reads the other's SMS, so on f8cf0b9 neither date_sent matched and both used all 6 runs. Seen on
     * emulator-5554 with the f8cf0b9 debug build: SMS 74 and 75 (1.25 s apart), work 74177043 and 606a5f28, 6 runs
     * each. Under a0d0e6b, and now, the same timeline takes 3 runs: Q's first run stops on P (within 2 min), P's retry
     * stops on Q.
     */
    @Test fun crossedReadsOfAPairInDifferentSecondsTakeThreeRuns() = workerTest {
        val pAt = A_AT
        val qAt = A_AT.plusMillis(1_250)
        val pSent = pAt.minusMillis(124).plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val qSent = qAt.plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val p = A.copy(message = A.message.copy(receivedAtMillis = pAt.toEpochMilli()), sentAt = pSent)
        val q = B.copy(message = B.message.copy(receivedAtMillis = qAt.toEpochMilli()), sentAt = qSent)
        var runs = 0
        suspend fun runP(attempt: Int) = run(pAt, attempt, listOf(pSent)).also { runs++ }
        suspend fun runQ(attempt: Int) = run(qAt, attempt, listOf(qSent)).also { runs++ }

        now = pAt.plusMillis(35)
        var pResult = runP(0) // P not written yet
        inbox += p
        now = qAt.plusMillis(30)
        var qResult = runQ(0) // reads P for the first time
        inbox += q
        var attempt = 1
        while ((pResult == ListenableWorker.Result.retry() || qResult == ListenableWorker.Result.retry()) && attempt <= IncomingSmsWorker.MAX_ATTEMPTS) {
            val delay = 10L * attempt * (attempt + 1) / 2
            if (pResult == ListenableWorker.Result.retry()) { now = pAt.plusSeconds(delay); pResult = runP(attempt) }
            if (qResult == ListenableWorker.Result.retry()) { now = qAt.plusSeconds(delay); qResult = runQ(attempt) }
            attempt++
        }

        assertTrue(wasRead(p))
        assertTrue(wasRead(q))
        assertEquals(listOf(pAt, qAt), posted())
        assertEquals("worker runs for one pair of SMS (a0d0e6b: 3)", 3, runs)
    }

    private class Inbox : PagedSmsInbox {
        private val messages = ArrayList<InboxSms>()
        operator fun plusAssign(sms: InboxSms) { messages += sms }
        override fun canRead(): Boolean = true
        override suspend fun count(from: Instant, until: Instant?): Int = inRange(from, until).size
        override suspend fun forEachPage(from: Instant, until: Instant?, pageSize: Int, onPage: suspend (List<InboxSms>) -> Unit) {
            inRange(from, until).chunked(pageSize).forEach { onPage(it) }
        }
        private fun inRange(from: Instant, until: Instant?) = messages
            .filter { it.message.receivedAtMillis >= from.toEpochMilli() }
            .filter { until == null || it.message.receivedAtMillis < until.toEpochMilli() }
            .sortedBy { it.message.receivedAtMillis }
    }

    private companion object {
        val A_AT: Instant = nov(16, 23, 0).plusSeconds(3)
        val B_AT: Instant = A_AT.plusSeconds(1)
        val SAME_SECOND: Instant = A_AT.minusSeconds(1)
        val A = InboxSms(
            53,
            SmsMessage("VM-HDFCBK", "Rs.450.00 debited from A/c XX4521 on 16-11-26 to VPA swiggy@icici (UPI Ref No 632000000053). -HDFC Bank", A_AT.toEpochMilli()),
            SAME_SECOND,
        )
        val B = InboxSms(
            54,
            SmsMessage("VM-ICICIB", "Rs.120.00 debited from A/c XX7788 on 16-11-26 to VPA chai@ybl (UPI Ref No 632000000054). -ICICI Bank", B_AT.toEpochMilli()),
            SAME_SECOND,
        )
    }
}
