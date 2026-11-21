package com.expenses.app.cardbill

import android.app.Application
import androidx.work.WorkInfo
import com.expenses.app.cardbill.CardBillTestKit.Companion.at
import com.expenses.app.data.settings.TrackingMode
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Planning the card bill reminders (docs/card-bills.md 2.7): when they wait, and every reason they go. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CardBillRemindersTest {
    private val kit = CardBillTestKit()

    @After fun close() = kit.close()

    private fun works(billId: Long): List<WorkInfo> =
        kit.workManager.getWorkInfosForUniqueWork(CardBillReminders.uniqueName(billId)).get()

    private fun waiting(billId: Long): WorkInfo? = works(billId).singleOrNull { it.state == WorkInfo.State.ENQUEUED }

    /** Waits (up to 5 s) until the reminder work of [billId] that runs at once has finished on its own thread. */
    private fun ranAtOnce(billId: Long) {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            val all = works(billId)
            if (all.isNotEmpty() && all.all { it.state.isFinished }) return
            Thread.sleep(20)
        }
        throw AssertionError("the reminder did not run")
    }

    @Test fun aBillWaitsUntilNineThreeDaysBeforeItIsDue() = runTest {
        val id = kit.bill(kit.card())
        kit.now = at(11, 10, 14)
        kit.reminders().sync()
        val work = waiting(id)!!
        assertEquals(Duration.between(at(11, 10, 14), at(11, 18, 9)).toMillis(), work.initialDelayMillis)
        assertTrue(CardBillReminders.TAG in work.tags)
    }

    @Test fun aSecondSyncChangesNothing() = runTest {
        val id = kit.bill(kit.card())
        kit.reminders().sync()
        val first = waiting(id)!!.id
        kit.reminders().sync()
        assertEquals(first, waiting(id)!!.id)
        assertEquals(1, works(id).size)
    }

    @Test fun aPaidBillIsNotReminded() = runTest {
        val card = kit.card()
        val id = kit.bill(card)
        kit.reminders().sync()
        kit.payCard(card)
        kit.reminders().sync()
        assertEquals(null, waiting(id))
        assertTrue(works(id).all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test fun aNewerBillReplacesTheOlder() = runTest {
        val card = kit.card()
        val old = kit.bill(card)
        kit.reminders().sync()
        val newer = kit.bill(card, due = LocalDate.of(2026, 11, 28), smsId = 901, received = at(11, 8))
        kit.reminders().sync()
        assertEquals(null, waiting(old))
        assertEquals(Duration.between(kit.now, at(11, 25, 9)).toMillis(), waiting(newer)!!.initialDelayMillis)
    }

    @Test fun aSwitchedOffCardIsNotReminded() = runTest {
        val card = kit.card()
        val id = kit.bill(card)
        kit.reminders().sync()
        kit.database.accounts.setTracked(card, false)
        kit.reminders().sync()
        assertEquals(null, waiting(id))
    }

    @Test fun remindersOffOrManualModeCancelIt() = runTest {
        val id = kit.bill(kit.card())
        kit.reminders().sync()
        kit.settings.value = kit.settings.value.copy(remindersOn = false)
        kit.reminders().sync()
        assertEquals(null, waiting(id))
        kit.settings.value = kit.settings.value.copy(remindersOn = true)
        kit.reminders().sync()
        assertTrue(waiting(id) != null)
        kit.settings.value = kit.settings.value.copy(trackingMode = TrackingMode.MANUAL)
        kit.reminders().sync()
        assertEquals(null, waiting(id))
    }

    @Test fun aBillArrivingInsideTheWindowIsRemindedAtOnce() = runTest {
        val id = kit.bill(kit.card())
        kit.now = at(11, 19, 14)
        kit.reminders().sync()
        ranAtOnce(id)
        // Planned for now: the test WorkManager runs it at once, and the reminder is posted.
        assertEquals(listOf("HDFC Millennia bill due in 2 days"), kit.posted().map { it.extras.getString("android.title") })
        assertTrue(kit.database.cardBills.get(id)!!.remindedAt != null)
    }

    /**
     * Inside the window in the day the planned time is "now", so it changes with every sync. A sync while the reminder
     * runs must leave it alone: REPLACE would cancel it, and a cancel between its post and its "reminded" mark would
     * let the new work post it again.
     */
    @Test fun aRunningReminderIsNeitherCancelledNorReplaced() = runTest {
        val id = kit.bill(kit.card())
        val gate = CompletableDeferred<Unit>()
        kit.hold = gate
        try {
            kit.now = at(11, 19, 14)
            kit.reminders().sync()
            val running = runningWork(id)
            kit.now = at(11, 19, 14, 5)
            kit.reminders().sync()
            assertEquals(listOf(running.id to WorkInfo.State.RUNNING), works(id).map { it.id to it.state })
            // Switched off meanwhile: the running reminder still finishes on its own checks.
            kit.settings.value = kit.settings.value.copy(remindersOn = false)
            kit.reminders().sync()
            assertEquals(listOf(running.id to WorkInfo.State.RUNNING), works(id).map { it.id to it.state })
        } finally {
            gate.complete(Unit)
        }
    }

    /** Waits (up to 5 s) until the reminder work of [billId] is running, and returns it. */
    private fun runningWork(billId: Long): WorkInfo {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            works(billId).singleOrNull { it.state == WorkInfo.State.RUNNING }?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("the reminder did not start: ${works(billId).map { it.state }}")
    }

    @Test fun aBillPastItsDueDateIsNeverReminded() = runTest {
        val id = kit.bill(kit.card())
        kit.now = at(11, 22, 10)
        kit.reminders().sync()
        assertTrue(works(id).isEmpty())
    }

    @Test fun aPostedReminderGoesWhenTheBillIsPaidButStaysWhenRemindersAreSwitchedOff() = runTest {
        val card = kit.card()
        val id = kit.bill(card)
        kit.now = at(11, 19, 14)
        kit.reminders().sync()
        ranAtOnce(id)
        assertEquals(1, kit.posted().size)
        kit.settings.value = kit.settings.value.copy(remindersOn = false)
        kit.reminders().sync()
        assertEquals(1, kit.posted().size)
        kit.payCard(card, day = 19)
        kit.reminders().sync()
        assertEquals(0, kit.posted().size)
    }
}
