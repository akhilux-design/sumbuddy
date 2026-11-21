package com.expenses.app.cardbill

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.expenses.app.cardbill.CardBillTestKit.Companion.at
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The card bill reminder when its time comes (docs/card-bills.md 2.7): it checks again, posts and marks the bill. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CardBillReminderWorkerTest {
    private val kit = CardBillTestKit()

    @After fun close() = kit.close()

    private suspend fun run(billId: Long): ListenableWorker.Result {
        val reminders = kit.reminders()
        val worker = TestListenableWorkerBuilder<CardBillReminderWorker>(kit.context)
            .setInputData(workDataOf(CardBillReminderWorker.KEY_BILL_ID to billId))
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: android.content.Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
                    ): ListenableWorker = CardBillReminderWorker(appContext, workerParameters, reminders)
                },
            )
            .build()
        return worker.doWork()
    }

    private val Notification.title: String? get() = extras.getString(Notification.EXTRA_TITLE)
    private val Notification.text: String? get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test fun itPostsTheDesignsTextWithRemindFriends() = runTest {
        val id = kit.bill(kit.card())
        kit.drawnFriends()
        kit.now = at(11, 18, 9)
        assertEquals(ListenableWorker.Result.success(), run(id))
        val posted = kit.posted().single()
        assertEquals("HDFC Millennia bill due in 3 days", posted.title)
        assertEquals("Friends owe you ₹8,200 of the ₹24,300.", posted.text)
        assertEquals(listOf("Remind friends"), posted.actions.map { it.title.toString() })
        assertEquals(kit.now, kit.database.cardBills.get(id)!!.remindedAt)
    }

    @Test fun oneFriendIsNamed() = runTest {
        val id = kit.bill(kit.card())
        kit.drawnFriends()
        kit.parts.value = kit.parts.value.take(1)
        kit.now = at(11, 20, 10)
        run(id)
        val posted = kit.posted().single()
        assertEquals("HDFC Millennia bill due tomorrow", posted.title)
        assertEquals("Rahul owes you ₹4,100 of the ₹24,300.", posted.text)
    }

    @Test fun nobodyOwingOrSignedOutSaysPayByWithNoAction() = runTest {
        val id = kit.bill(kit.card())
        kit.drawnFriends()
        kit.signedIn.value = false
        kit.now = at(11, 21, 9)
        run(id)
        val posted = kit.posted().single()
        assertEquals("HDFC Millennia bill due today", posted.title)
        assertEquals("Pay ₹24,300 by 21 Nov.", posted.text)
        assertNull(posted.actions)
    }

    @Test fun nothingWhenAlreadyReminded() = runTest {
        val id = kit.bill(kit.card())
        kit.now = at(11, 18, 9)
        kit.database.cardBills.setRemindedAt(id, at(11, 18, 9))
        run(id)
        assertTrue(kit.posted().isEmpty())
    }

    @Test fun nothingWhenPaid() = runTest {
        val card = kit.card()
        val id = kit.bill(card)
        kit.payCard(card, day = 10)
        kit.now = at(11, 18, 9)
        run(id)
        assertTrue(kit.posted().isEmpty())
        assertNull(kit.database.cardBills.get(id)!!.remindedAt)
    }

    @Test fun nothingWhileNotificationsAreOff() = runTest {
        val id = kit.bill(kit.card())
        kit.now = at(11, 18, 9)
        shadowOf(kit.context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(kit.context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertEquals(false, NotificationManagerCompat.from(kit.context).areNotificationsEnabled())
        run(id)
        assertTrue(kit.posted().isEmpty())
        assertNull("not reminded while notifications are off", kit.database.cardBills.get(id)!!.remindedAt)
    }

    @Test fun lateIntoQuietHoursItWaitsForNine() = runTest {
        val id = kit.bill(kit.card())
        kit.now = at(11, 19, 22)
        run(id)
        assertTrue(kit.posted().isEmpty())
        assertNull(kit.database.cardBills.get(id)!!.remindedAt)
        val again = kit.workManager.getWorkInfosForUniqueWork(CardBillReminders.uniqueName(id)).get()
            .single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(at(11, 20, 9).toEpochMilli() - kit.now.toEpochMilli(), again.initialDelayMillis)
    }
}
