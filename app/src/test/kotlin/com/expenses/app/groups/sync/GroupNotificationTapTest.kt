package com.expenses.app.groups.sync

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import com.expenses.app.MainActivity
import com.expenses.app.groups.G1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A tap on a "Groups" notification opens [MainActivity], which tells the bundle which notification it was: the
 * summary goes with the last one without a sync (docs/server.md 9.7 "The summary goes with the last notification").
 * The activity is only created here, never shown, so no screen is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GroupNotificationTapTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before fun allowNotifications() = shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun children(): List<Notification> =
        shadowOf(manager).allNotifications.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun Notification.tap(): Intent = Intent(shadowOf(contentIntent).savedIntent)

    @Test fun aTapThatStartsTheAppTakesTheSummaryWithTheLastNotification() {
        GroupNotifications(context).postAskRepaid(7L, "Priya", 50_000)
        assertEquals(2, shadowOf(manager).allNotifications.size)

        Robolectric.buildActivity(MainActivity::class.java, children().single().tap()).create()

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun aTapWhileTheAppIsOpenRemovesThatNotificationOnly() {
        GroupNotifications(context).post(
            listOf(GroupEvent.ExpenseAdded(G1, "e1", "Rahul", "Swiggy", 15_000), GroupEvent.ExpenseAdded(G1, "e2", "Rahul", "Uber", 5_000)),
        )
        val (first, second) = children()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()

        activity.newIntent(first.tap())
        assertEquals(listOf(second), children())
        assertEquals(2, shadowOf(manager).allNotifications.size)

        activity.newIntent(second.tap())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun theAppOpenedAgainFromRecentsRemovesNothing() {
        GroupNotifications(context).postAskRepaid(7L, "Priya", 50_000)
        // The task's first intent comes back: the tap it once was has been handled, and this is a new question.
        val again = children().single().tap().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

        Robolectric.buildActivity(MainActivity::class.java, again).create()

        assertEquals(2, shadowOf(manager).allNotifications.size)
    }

    @Test fun anIntentFromOutsideTheAppThatNamesANotificationRemovesNothing() {
        GroupNotifications(context).postAskRepaid(7L, "Priya", 50_000)
        // Not through the app's own notification target: the launcher intent with the extra added by someone else.
        val forged = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).putExtras(children().single().tap())

        Robolectric.buildActivity(MainActivity::class.java, forged).create()

        assertEquals(2, shadowOf(manager).allNotifications.size)
    }
}
