package com.expenses.app.sms

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.SmsKey
import com.expenses.app.data.repository.RoomTransactionRunner
import com.expenses.app.data.repository.TestDatabase
import com.expenses.app.data.repository.TestZone
import com.expenses.app.data.repository.TransactionRunner
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

/** Edge cases of the "arrived" rule of IncomingSmsWorker, from the review of 0.3.1. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class IncomingSmsWorkerReviewTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val folder: File = Files.createTempDirectory("settings").toFile()
    private val test = TestDatabase()
    private val inbox = Inbox()
    private lateinit var settings: SettingsRepository
    private var now: Instant = X_AT
    private var transactions: TransactionRunner = RoomTransactionRunner(test.db)

    /** The tries the runs queued, in order. */
    private val queued = mutableListOf<OneTimeWorkRequest>()

    private val parts = object : IncomingSmsParts {
        override val settings: SettingsRepository get() = this@IncomingSmsWorkerReviewTest.settings
        override val inbox: PagedSmsInbox get() = this@IncomingSmsWorkerReviewTest.inbox
        override val pipeline: IngestPipeline by lazy {
            SmsIngestPipeline(
                parser = SmsParser(),
                transactions = object : TransactionRunner {
                    override suspend fun <T> run(block: suspend () -> T): T = transactions.run(block)
                },
                expenses = test.expenses,
                accounts = test.accounts,
                cardBills = test.cardBills,
                reviews = test.reviews,
                readSms = test.readSms,
                clock = test.clock,
                parsing = Dispatchers.Unconfined,
            )
        }
        override val clock: Clock get() = Clock.fixed(now, TestZone)
        override val hooks: List<NewPaymentsHook> = emptyList()
        override suspend fun account(id: Long): Account? = test.accounts.get(id)
        override suspend fun planCardBillReminders() = Unit
        override fun queue(request: OneTimeWorkRequest) {
            queued += request
        }
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

    private fun worker(arrivedAt: Instant?, attempt: Int = 0): IncomingSmsWorker {
        val builder = TestListenableWorkerBuilder<IncomingSmsWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        IncomingSmsWorker(appContext, workerParameters, parts)
                },
            )
        if (arrivedAt != null) builder.setInputData(IncomingSmsWorker.arrivalInput(arrivedAt, tryNumber = attempt))
        return builder.build()
    }

    /**
     * One run, its [attempt]th try. It always ends with success; it tries again when it queued its next try. Two runs
     * may run at once, so a run's tries are told apart by their SMS's arrival time.
     */
    private suspend fun run(arrivedAt: Instant?, attempt: Int = 0): ReadEnd {
        fun tries() = queued.count { it.input.getLong("arrived_at", 0) == arrivedAt?.toEpochMilli() }
        val before = tries()
        assertEquals(ListenableWorker.Result.success(), worker(arrivedAt, attempt).doWork())
        return if (tries() > before) ReadEnd.TRIES_AGAIN else ReadEnd.DONE
    }

    private fun posted(): List<Instant> = shadowOf(manager).allNotifications
        .filter { it.channelId == PaymentNotifications.CHANNEL_ID }
        .map { Instant.ofEpochMilli(it.`when`) }
        .sorted()

    private suspend fun wasRead(sms: InboxSms): Boolean =
        test.readSms.alreadyRead(listOf(SmsKey(sms.providerId, Instant.ofEpochMilli(sms.message.receivedAtMillis), sms.message.sender))).isNotEmpty()

    /** Two reads run at once for two SMS that are both in the inbox: each SMS is notified exactly once. */
    @Test fun twoReadsAtOnceNotifyEachSmsOnce() = workerTest {
        inbox += Y
        inbox += X
        val real = RoomTransactionRunner(test.db)
        val waiting = AtomicInteger(0)
        val bothChecked = CompletableDeferred<Unit>()
        transactions = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                if (waiting.incrementAndGet() == 2) bothChecked.complete(Unit)
                bothChecked.await()
                return real.run(block)
            }
        }
        now = X_AT.plusSeconds(1)
        val first = async { run(Y_AT) }
        val second = async { run(X_AT) }
        val results = listOf(first.await(), second.await())

        assertEquals(listOf(Y_AT, X_AT), posted())
        assertEquals(1, results.count { it == ReadEnd.DONE })
        assertEquals(1, results.count { it == ReadEnd.TRIES_AGAIN })
    }

    /** A message missed 20 minutes ago is read and notified, but it does not end the wait for the new SMS. */
    @Test fun anOldMissedMessageDoesNotEndTheWait() = workerTest {
        val old = sms(50, "Rs.75.00 debited from A/c XX4521 on 16-11-26 to VPA tea@ybl (UPI Ref No 632000000050). -HDFC Bank", X_AT.minusSeconds(20 * 60))
        inbox += old
        now = X_AT.plusMillis(40)
        assertEquals(ReadEnd.TRIES_AGAIN, run(X_AT))
        assertTrue(wasRead(old))

        inbox += X
        now = X_AT.plusSeconds(10)
        assertEquals(ReadEnd.DONE, run(X_AT, attempt = 1))
        assertEquals(listOf(old.at(), X_AT), posted())
    }

    /**
     * The SMS app writes an inbox time more than 2 min before the broadcast (late broadcast, or a network time) and no
     * service-centre time can match (a 0.3.0 work, or no `date_sent`): the SMS is read and notified on the first run,
     * then the read tries again 5 times for nothing, and never posts twice.
     */
    @Test fun anInboxTimeBeforeTheMarginIsReadOnceThenWastesTheTries() = workerTest {
        val late = sms(55, "Rs.300.00 debited from A/c XX4521 on 16-11-26 to VPA shop@ybl (UPI Ref No 632000000055). -HDFC Bank", X_AT.minusSeconds(180))
        inbox += late
        val results = (0..IncomingSmsWorker.MAX_ATTEMPTS).map { attempt ->
            now = X_AT.plusSeconds(10L * attempt)
            run(X_AT, attempt)
        }
        assertTrue(wasRead(late))
        assertEquals(listOf(late.at()), posted())
        assertEquals(IncomingSmsWorker.MAX_ATTEMPTS, results.count { it == ReadEnd.TRIES_AGAIN })
        assertEquals(ReadEnd.DONE, results.last())
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
        const val HDFC = "VM-HDFCBK"
        val Y_AT: Instant = nov(16, 23, 0).plusSeconds(3)
        val X_AT: Instant = Y_AT.plusSeconds(30)
        val Y = sms(53, "Rs.450.00 debited from A/c XX4521 on 16-11-26 to VPA swiggy@icici (UPI Ref No 632000000053). -HDFC Bank", Y_AT)
        val X = sms(54, "Rs.120.00 debited from A/c XX4521 on 16-11-26 to VPA chai@ybl (UPI Ref No 632000000054). -HDFC Bank", X_AT)

        fun sms(id: Long, body: String, at: Instant) =
            InboxSms(providerId = id, message = SmsMessage(sender = HDFC, body = body, receivedAtMillis = at.toEpochMilli()))

        fun InboxSms.at(): Instant = Instant.ofEpochMilli(message.receivedAtMillis)
    }
}
