package com.expenses.app.groups.sync

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.expenses.app.MainActivity
import com.expenses.app.groups.G1
import com.expenses.app.groups.G2
import com.expenses.app.ui.nav.AppLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Groups" notifications open the group they are about (docs/server.md 9.7; docs/groups-build-plan.md 4.4 task 7). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GroupNotificationsLinksTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** The notifications posted, the bundle's summary not counted. */
    private fun posted(): List<Notification> = shadowOf(manager).allNotifications.filterNot { it.isSummary() }

    private fun summaries(): List<Notification> = shadowOf(manager).allNotifications.filter { it.isSummary() }

    private fun Notification.isSummary(): Boolean = flags and Notification.FLAG_GROUP_SUMMARY != 0

    private fun Notification.title(): String = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun Notification.opens(link: AppLink): Boolean =
        shadowOf(contentIntent).savedIntent.filterEquals(MainActivity.openLinkIntent(context, link))

    @Test fun eachEventOpensItsGroup() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        GroupNotifications(context).post(
            listOf(
                GroupEvent.ExpenseAdded(G1, "e1", "Rahul", "Swiggy", 15_000),
                GroupEvent.SettlementConfirmed(G1, "s1", "Priya", 120_000),
                GroupEvent.MemberJoined(G2, "m1", "Priya", "Goa trip"),
                GroupEvent.Reminded(G2, "Arjun", 115_000, "Goa trip"),
            ),
        )

        val byTitle = posted().associateBy { it.title() }
        assertEquals(4, byTitle.size)
        assertTrue(byTitle.getValue("Rahul added Swiggy · you owe ₹150").opens(AppLink.Group(G1)))
        assertTrue(byTitle.getValue("Priya confirmed ₹1,200 as paid").opens(AppLink.Group(G1)))
        assertTrue(byTitle.getValue("Priya joined Goa trip").opens(AppLink.Group(G2)))
        assertTrue(byTitle.getValue("Arjun reminded you: ₹1,150 for Goa trip").opens(AppLink.Group(G2)))
    }

    @Test fun theSameChangeTwiceIsOneNotification() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val event = GroupEvent.ExpenseAdded(G1, "e1", "Rahul", "Swiggy", 15_000)

        GroupNotifications(context).post(listOf(event))
        GroupNotifications(context).post(listOf(event))

        assertEquals(1, posted().size)
    }

    @Test fun theyAreOneBundleWhoseTapOpensTheAppAndKeepsThem() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = GroupNotifications(context)

        notifications.post(listOf(GroupEvent.ExpenseAdded(G1, "e1", "Rahul", "Swiggy", 15_000)))
        // The summary comes with the first one already: Android shows one alone without it.
        assertEquals(1, summaries().single().number)
        notifications.postAskRepaid(7L, "Priya", 120_000)

        val children = posted()
        assertEquals(2, children.size)
        assertTrue(children.all { it.group == children.first().group && it.group != null && it.deleteIntent != null })
        val summary = summaries().single()
        assertEquals(2, summary.number)
        assertEquals(children.first().group, summary.group)
        // A tap opens the app and does not clear what is under it.
        assertEquals(0, summary.flags and Notification.FLAG_AUTO_CANCEL)
        val opened = shadowOf(summary.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, opened.component?.className)
        assertEquals(android.content.Intent.ACTION_MAIN, opened.action)
        assertTrue(opened.extras?.containsKey(MainActivity.EXTRA_LINK) != true)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
    }

    @Test fun theSummaryGoesWithTheLastNotificationUnderIt() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = GroupNotifications(context)
        notifications.postAskRepaid(7L, "Priya", 120_000)
        notifications.post(listOf(GroupEvent.SettlementRecorded(G1, "s1", "Rahul", 120_000, canConfirm = true)))
        assertEquals(1, summaries().size)

        notifications.cancelAsk(7L)
        // One left under it: the summary stays (Android shows that one alone).
        assertEquals(1, summaries().size)
        notifications.cancelSettlement("s1")

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun swipingAwayTheLastOneRemovesTheSummary() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = GroupNotifications(context)
        notifications.post(
            listOf(GroupEvent.ExpenseAdded(G1, "e1", "Rahul", "Swiggy", 15_000), GroupEvent.ExpenseAdded(G1, "e2", "Rahul", "Uber", 5_000)),
        )
        assertEquals(1, summaries().size)
        val delete = posted().first().deleteIntent

        // The person swiped both away (Android removes them; their delete intent reaches the receiver).
        shadowOf(manager).activeNotifications.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .forEach { manager.cancel(it.tag, it.id) }
        GroupActionReceiver().onReceive(context, shadowOf(delete).savedIntent)

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun aTapOnTheLastOneFollowedByASyncRemovesTheSummary() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = GroupNotifications(context)
        notifications.postAskRepaid(7L, "Priya", 120_000)
        assertEquals(1, summaries().size)

        // The tap cancelled the question (auto-cancel) and opened the app, which syncs and then tidies.
        shadowOf(manager).activeNotifications.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .forEach { manager.cancel(it.tag, it.id) }
        notifications.tidy()

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun aTapOnTheLastOneRemovesTheSummaryWithoutASync() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = GroupNotifications(context)
        notifications.post(
            listOf(GroupEvent.ExpenseAdded(G1, "e1", "Rahul", "Swiggy", 15_000), GroupEvent.ExpenseAdded(G1, "e2", "Rahul", "Uber", 5_000)),
        )
        val taps = posted().map { shadowOf(it.contentIntent).savedIntent }
        assertTrue(taps.all { it.filterEquals(MainActivity.openLinkIntent(context, AppLink.Group(G1))) })

        // MainActivity hands the intent of the tap over: that notification goes, the other stays under the summary.
        GroupNotifications(context).removedByPerson(taps[0])
        assertEquals(1, posted().size)
        assertEquals(1, summaries().size)

        GroupNotifications(context).removedByPerson(taps[1])
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun signingOutRemovesEveryGroupsNotification() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = GroupNotifications(context)
        notifications.post(
            listOf(GroupEvent.ExpenseAdded(G1, "e1", "Rahul", "Swiggy", 15_000), GroupEvent.MemberJoined(G2, "m1", "Priya", "Goa trip")),
        )
        notifications.postRepaid(3L, "Rahul", 120_000, emptyList(), AppLink.AllSquare(G1, "m2", 120_000))
        // Not a "Groups" notification: it stays.
        manager.notify("payments", 1, Notification.Builder(context, "payments").setSmallIcon(android.R.drawable.ic_dialog_info).build())

        notifications.cancelAll()

        assertEquals(listOf("payments"), shadowOf(manager).activeNotifications.map { it.tag })
    }

    @Test fun nothingIsPostedWithoutThePermission() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        GroupNotifications(context).post(listOf(GroupEvent.ExpenseAdded(G1, "e1", "Rahul", "Swiggy", 15_000)))

        assertTrue(posted().isEmpty())
    }
}
