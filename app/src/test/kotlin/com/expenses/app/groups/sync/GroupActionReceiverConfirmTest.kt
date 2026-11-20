package com.expenses.app.groups.sync

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.groups.data.SettlementEntity
import com.expenses.app.groups.data.SyncState
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Confirm on "Rahul marked ₹1,200 as paid" (docs/server.md 9.5, 9.7; docs/groups-build-plan.md 4.4 task 7). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GroupActionReceiverConfirmTest {
    private val env = GroupsTestEnv()
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val settlementId = "f0000000-0000-4000-8000-000000000001"

    @Before fun allowNotifications() = shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @After fun close() = env.close()

    /** Rahul marked that he paid the person ₹1,200 in cash; the person has not confirmed it. */
    private suspend fun rahulMarkedItPaid() {
        env.seedGroup()
        env.db.groupDao().upsertSettlement(
            SettlementEntity(
                settlementId, G1, M_RAHUL, M_ME, 120_000, SettleMethod.CASH, matchedFromSms = false, occurredOn = LocalDate.of(2026, 11, 16),
                recordedByMemberId = M_RAHUL, confirmedAt = null, deleted = false, version = 7, syncState = SyncState.SYNCED,
            ),
        )
    }

    private fun posted(canConfirm: Boolean): Notification {
        GroupNotifications(context).post(listOf(GroupEvent.SettlementRecorded(G1, settlementId, "Rahul", 120_000, canConfirm)))
        // Without the bundle's summary, which is posted with it.
        return shadowOf(manager).allNotifications.single { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
    }

    @Test fun confirmConfirmsTheSettlementAndRemovesTheNotification() = runTest {
        rahulMarkedItPaid()
        val notification = posted(canConfirm = true)
        assertEquals("Rahul marked ₹1,200 as paid", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val action = notification.actions.single()
        assertEquals("Confirm", action.title.toString())
        val intent = shadowOf(action.actionIntent).savedIntent
        assertTrue(shadowOf(action.actionIntent).isBroadcast)
        assertEquals(GroupActionReceiver::class.java.name, intent.component!!.className)

        val id = GroupActionReceiver.settlementToConfirm(intent)
        assertEquals(settlementId, id)
        GroupActionReceiver.confirm(context, env.groups, id!!)

        assertNotNull(env.db.groupDao().getSettlement(settlementId)!!.confirmedAt)
        val sent = env.outbox.entries().single()
        assertEquals(OutboxOp.CONFIRM, sent.op)
        assertEquals("/v1/groups/$G1/settlements/$settlementId/confirm", sent.path)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun confirmingTwiceQueuesItOnce() = runTest {
        rahulMarkedItPaid()
        GroupActionReceiver.confirm(context, env.groups, settlementId)
        GroupActionReceiver.confirm(context, env.groups, settlementId)

        assertEquals(1, env.outbox.entries().size)
    }

    @Test fun withoutConfirmTheNotificationHasNoAction() = runTest {
        rahulMarkedItPaid()

        assertTrue(posted(canConfirm = false).actions.isNullOrEmpty())
    }

    @Test fun otherIntentsConfirmNothing() {
        assertNull(GroupActionReceiver.settlementToConfirm(Intent("something.else").putExtra(GroupActionReceiver.EXTRA_SETTLEMENT_ID, settlementId)))
        assertNull(GroupActionReceiver.settlementToConfirm(Intent(GroupActionReceiver.ACTION_CONFIRM)))
    }
}
