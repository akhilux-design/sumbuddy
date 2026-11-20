package com.expenses.app.groups.sync

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.expenses.app.MainActivity
import com.expenses.app.groups.FAMILY
import com.expenses.app.ui.nav.AppLink
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Code review (Family build), docs/family.md 1.1, 8.2 and 9 (SyncEngineTest item): "Meera joined Home" opens Home
 * with the Family side chosen, `AppLink.Family`. `GroupNotifications.linkOf` still answers `AppLink.Group`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FamilyNotificationReviewTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test fun meeraJoinedHomeOpensTheFamilySideOfHome() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        GroupNotifications(context).post(listOf(GroupEvent.MemberJoined(FAMILY, "m1", "Meera", "Home", family = true)))

        val joined = shadowOf(manager).allNotifications.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }.single()
        val opens = shadowOf(joined.contentIntent).savedIntent
        assertTrue(
            "the notification should open AppLink.Family",
            opens.filterEquals(MainActivity.openLinkIntent(context, AppLink.Family)),
        )
    }
}
