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

/** Review of 0.3.1 (untracked): edge cases of the "arrived" rule of IncomingSmsWorker. */
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

    private fun worker(arrivedAt: Instant?, attempt: Int = 0, sent: Instant? = null): IncomingSmsWorker {
        val builder = TestListenableWorkerBuilder<IncomingSmsWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        IncomingSmsWorker(appContext, workerParameters, parts)
                },
            )
        if (arrivedAt != null) builder.setInputData(IncomingSmsWorker.arrivalInput(arrivedAt, listOfNotNull(sent)))
        return builder.build()
    }

    private suspend fun run(arrivedAt: Instant?, attempt: Int = 0, sent: Instant? = null) = worker(arrivedAt, attempt, sent).doWork()

    private fun posted(): List<Instant> = shadowOf(manager).allNotifications
        .filter { it.channelId == PaymentNotifications.CHANNEL_ID }
        .map { Instant.ofEpochMilli(it.`when`) }
        .sorted()

    private suspend fun wasRead(sms: InboxSms): Boolean =
        test.readSms.alreadyRead(listOf(SmsKey(sms.providerId, Instant.ofEpochMilli(sms.message.receivedAtMillis), sms.message.sender))).isNotEmpty()

    /**
     * The hole the builder named. Y is in the inbox, unread, and had no read of its own (it arrived while the app
     * could not run: during an app update, or while onboarding was not done so its read returned at once). X arrives
     * 30 s after Y. Its read runs before the SMS app wrote X, reads Y for the first time (within 2 min) and stops.
     * X is written a moment later and nothing reads it until the next SMS or app start.
     * Fix: the broadcast gives X's service-centre time and the SMS app records each message's (`date_sent`), so Y is
     * not taken for X.
     */
    @Test fun anUnreadMessageWithNoReadOfItsOwnEndsTheWaitForTheNewSms() = workerTest {
        inbox += Y
        now = X_AT.plusMillis(40)
        // X is not in the inbox yet, so its read must try again. Before the fix it stopped (success), and X was left unread.
        assertEquals(ListenableWorker.Result.retry(), run(X_AT, sent = X_SENT))
        assertTrue(wasRead(Y))

        // The next try finds X: read, and both are notified once.
        inbox += X
        now = X_AT.plusSeconds(10)
        assertEquals(ListenableWorker.Result.success(), run(X_AT, attempt = 1, sent = X_SENT))
        assertTrue(wasRead(X))
        assertEquals(listOf(Y_AT, X_AT), posted())
    }

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
        assertEquals(1, results.count { it == ListenableWorker.Result.success() })
        assertEquals(1, results.count { it == ListenableWorker.Result.retry() })
    }

    /** A message missed 20 minutes ago is read and notified, but it does not end the wait for the new SMS. */
    @Test fun anOldMissedMessageDoesNotEndTheWait() = workerTest {
        val old = sms(50, "Rs.75.00 debited from A/c XX4521 on 16-11-26 to VPA tea@ybl (UPI Ref No 632000000050). -HDFC Bank", X_AT.minusSeconds(20 * 60))
        inbox += old
        now = X_AT.plusMillis(40)
        assertEquals(ListenableWorker.Result.retry(), run(X_AT))
        assertTrue(wasRead(old))

        inbox += X
        now = X_AT.plusSeconds(10)
        assertEquals(ListenableWorker.Result.success(), run(X_AT, attempt = 1))
        assertEquals(listOf(old.at(), X_AT), posted())
    }

    /**
     * The SMS app writes an inbox time more than 2 min before the broadcast (late broadcast, or a network time): the
     * SMS is read and notified on the first run, then the read tries again 5 times for nothing, and never posts twice.
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
        assertEquals(IncomingSmsWorker.MAX_ATTEMPTS, results.count { it == ListenableWorker.Result.retry() })
        assertEquals(ListenableWorker.Result.success(), results.last())
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
        /** When the service centre stamped Y and X (the inbox's `date_sent`), a little before each arrived. */
        val Y_SENT: Instant = Y_AT.minusSeconds(2)
        val X_SENT: Instant = X_AT.minusSeconds(2)
        val Y = sms(53, "Rs.450.00 debited from A/c XX4521 on 16-11-26 to VPA swiggy@icici (UPI Ref No 632000000053). -HDFC Bank", Y_AT, Y_SENT)
        val X = sms(54, "Rs.120.00 debited from A/c XX4521 on 16-11-26 to VPA chai@ybl (UPI Ref No 632000000054). -HDFC Bank", X_AT, X_SENT)

        fun sms(id: Long, body: String, at: Instant, sent: Instant? = null) =
            InboxSms(providerId = id, message = SmsMessage(sender = HDFC, body = body, receivedAtMillis = at.toEpochMilli()), sentAt = sent)

        fun InboxSms.at(): Instant = Instant.ofEpochMilli(message.receivedAtMillis)
    }
}
