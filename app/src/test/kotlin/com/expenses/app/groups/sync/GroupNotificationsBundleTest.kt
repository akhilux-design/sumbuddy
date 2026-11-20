package com.expenses.app.groups.sync

import android.Manifest
import android.app.Application
import android.app.Notification
import com.expenses.app.MainActivity
import com.expenses.app.groups.G1
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.ui.nav.AppLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The bundle of "Groups" notifications as it behaves on a phone (docs/server.md 9.7 "One bundle"): the system applies
 * `notify()` and `cancel()` after the call has returned, so its list is behind right after the app's own call.
 * Robolectric's notification manager applies them at once and cannot show that; [LateShade] applies them late.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GroupNotificationsBundleTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val shade = LateShade()
    private var now = 10_000L
    private val notifications = GroupNotifications(context, shade, RecentCalls { now })

    private val ask = "Was this Priya paying you back?"
    private val repaid = "Rahul paid you ₹500"
    private val marked = "Rahul marked ₹1,200 as paid"

    @Before fun allowNotifications() = shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun askPriya() = notifications.postAskRepaid(7L, "Priya", 50_000)

    private fun rahulRepaid() =
        notifications.postRepaid(8L, "Rahul", 50_000, listOf(GroupKind.GROUP to "Flat 302"), AppLink.AllSquare(G1, "m2", 50_000))

    private fun rahulMarkedPaid() = notifications.post(listOf(GroupEvent.SettlementRecorded(G1, "s1", "Rahul", 120_000, canConfirm = true)))

    /** Much later: the system has applied every call, and the app's own calls are no longer "a moment ago". */
    private fun later() {
        shade.settle()
        now += 60_000
    }

    @Test fun aQuestionThenARepaymentAreUnderTheAppsOwnSummary() {
        askPriya()
        later()
        // One alone: its summary is there already (Android shows the question without it).
        assertEquals(listOf(ask), shade.titles())
        assertEquals(1, shade.summary()!!.number)

        // The summary is posted with the repayment, while the system does not list the repayment yet.
        rahulRepaid()
        assertEquals(listOf(ask), shade.titles())
        shade.settle()

        assertEquals(listOf(ask, repaid), shade.titles())
        val summary = shade.summary()!!
        assertEquals(2, summary.number)
        assertTrue(shade.children().all { it.group == summary.group && it.group != null })
        // A tap on the bundle opens the app and removes nothing: the summary is not cancelled by it.
        assertEquals(0, summary.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals(MainActivity::class.java.name, shadowOf(summary.contentIntent).savedIntent.component?.className)
    }

    @Test fun twoPostedBeforeTheSystemListsEitherAreBothCounted() {
        askPriya()
        rahulRepaid()
        shade.settle()

        assertEquals(listOf(ask, repaid), shade.titles())
        assertEquals(2, shade.summary()!!.number)
    }

    @Test fun aSyncThatPostsAndTidiesAtOnceKeepsItsSummary() {
        // SyncEngine.sync() posts what the pull found and tidies right after.
        notifications.post(
            listOf(GroupEvent.ExpenseAdded(G1, "e1", "Rahul", "Swiggy", 15_000), GroupEvent.ExpenseAdded(G1, "e2", "Rahul", "Uber", 5_000)),
        )
        notifications.tidy()
        shade.settle()

        assertEquals(2, shade.children().size)
        assertEquals(2, shade.summary()!!.number)
    }

    @Test fun answeringTheLastQuestionRemovesTheSummaryWhileTheSystemStillListsTheQuestion() {
        askPriya()
        later()

        notifications.cancelAsk(7L)
        // Not applied yet: the question is still listed, and the summary must go all the same.
        assertEquals(listOf(ask), shade.titles())
        shade.settle()

        assertTrue(shade.listed().isEmpty())
    }

    @Test fun answeringOneOfTwoKeepsTheOtherUnderTheSummary() {
        askPriya()
        rahulMarkedPaid()
        later()

        notifications.cancelAsk(7L)
        shade.settle()
        assertEquals(listOf(marked), shade.titles())
        assertNotNull(shade.summary())

        notifications.cancelSettlement("s1")
        shade.settle()
        assertTrue(shade.listed().isEmpty())
    }

    @Test fun aQuestionAnsweredBeforeTheSystemListedItLeavesNothing() {
        askPriya()
        notifications.cancelAsk(7L)
        shade.settle()

        assertTrue(shade.listed().isEmpty())
    }

    @Test fun aTapOnOneLeavesTheQuestionUnderTheSummary() {
        askPriya()
        rahulRepaid()
        later()

        // The person tapped "Rahul paid you" and the app was not told (a notification an older build posted): it is
        // cancelled by its tap, the app opens and syncs, which tidies.
        shade.removedByPerson(repaid)
        notifications.tidy()
        shade.settle()

        assertEquals(listOf(ask), shade.titles())
        assertNotNull(shade.summary())
    }

    @Test fun aTapOnTheLastOneLeavesNoEmptySummary() {
        askPriya()
        later()

        shade.removedByPerson(ask)
        notifications.tidy()
        shade.settle()

        assertTrue(shade.listed().isEmpty())
    }

    @Test fun aTapOnTheLastOneTakesTheSummaryAtOnce() {
        askPriya()
        later()
        val tap = shadowOf(shade.children().single().contentIntent).savedIntent

        // The tap opened the app, which is told which notification it was. The system has removed nothing yet, and no
        // sync follows (offline, or the app was on screen already).
        notifications.removedByPerson(tap)
        assertEquals(listOf(ask), shade.titles())
        shade.settle()

        assertTrue(shade.listed().isEmpty())
    }

    @Test fun aSwipeRightAfterItCameInTakesTheSummary() {
        askPriya()
        shade.settle()
        val swipe = shadowOf(shade.children().single().deleteIntent).savedIntent
        // Swiped away while the app still counts it as just posted: Android removed the question and left the summary.
        shade.removedByPerson(ask)
        now += RecentCalls.SETTLE_MS / 2

        notifications.removedByPerson(swipe)
        shade.settle()

        assertTrue(shade.listed().isEmpty())
    }

    @Test fun twoThatOpenTheSameGroupEachSayWhichOneWasTapped() {
        notifications.post(
            listOf(GroupEvent.ExpenseAdded(G1, "e1", "Rahul", "Swiggy", 15_000), GroupEvent.ExpenseAdded(G1, "e2", "Rahul", "Uber", 5_000)),
        )
        later()
        val (swiggy, uber) = shade.children()
        // The same screen, yet an intent each: one's id never overwrites the other's.
        assertNotEquals(swiggy.contentIntent, uber.contentIntent)
        assertNotEquals(swiggy.deleteIntent, uber.deleteIntent)

        notifications.removedByPerson(shadowOf(swiggy.contentIntent).savedIntent)
        shade.settle()
        assertEquals(listOf(uber.title()), shade.titles())
        assertNotNull(shade.summary())

        notifications.removedByPerson(shadowOf(uber.deleteIntent).savedIntent)
        shade.settle()
        assertTrue(shade.listed().isEmpty())
    }

    @Test fun anIntentThatNamesNoNotificationOnlyTidies() {
        askPriya()
        later()

        // Another notification of the app was tapped ("Payment detected"), or one posted by an older build.
        notifications.removedByPerson(MainActivity.openLinkIntent(context, AppLink.Payment(7)))
        shade.settle()
        assertEquals(listOf(ask), shade.titles())
        assertNotNull(shade.summary())

        shade.removedByPerson(ask)
        notifications.removedByPerson(MainActivity.openLinkIntent(context, AppLink.Payment(7)))
        shade.settle()
        assertTrue(shade.listed().isEmpty())
    }

    @Test fun tidyingWithNothingPostedLeavesNothing() {
        notifications.tidy()
        shade.settle()

        assertTrue(shade.listed().isEmpty())
    }

    @Test fun signingOutAlsoRemovesWhatTheSystemDoesNotListYet() {
        askPriya()
        later()
        rahulRepaid()

        notifications.cancelAll()
        shade.settle()

        assertTrue(shade.listed().isEmpty())
    }

    @Test fun aTidyWhileTheSystemHasNotListedTheQuestionYetKeepsItsSummary() {
        // The matcher posts the question; a sync that was already running ends a moment later and tidies.
        askPriya()
        now += RecentCalls.SETTLE_MS / 2
        notifications.tidy()
        shade.settle()

        assertEquals(listOf(ask), shade.titles())
        assertNotNull(shade.summary())
    }

    @Test fun aSummaryTheSystemTookWithTheLastOneIsNotBroughtBack() {
        askPriya()
        shade.settle()
        // The person tapped the question as it came in. Android removed it, and its summary with it, before the app
        // ever saw the question listed; the app opens and tidies while it still counts the question as just posted.
        shade.clearedByPerson()
        now += RecentCalls.SETTLE_MS / 2
        notifications.tidy()
        shade.settle()
        assertTrue(shade.listed().isEmpty())

        // And once the system's list is believed again, nothing changes.
        later()
        notifications.tidy()
        shade.settle()
        assertTrue(shade.listed().isEmpty())
    }

    @Test fun aTidyOnlyRemoves() {
        askPriya()
        rahulRepaid()
        later()
        // Two under no summary, as a build before this one left them. They stay as they are: a summary comes with a
        // new notification only.
        shade.removedByPerson(shade.summary()!!.title())

        notifications.tidy()
        shade.settle()
        assertEquals(listOf(ask, repaid), shade.titles())
        assertNull(shade.summary())

        rahulMarkedPaid()
        shade.settle()
        assertEquals(listOf(ask, repaid, marked), shade.titles())
        assertEquals(3, shade.summary()!!.number)
    }

    @Test fun nothingIsPostedWithoutThePermission() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        askPriya()
        rahulMarkedPaid()
        notifications.tidy()
        shade.settle()

        assertTrue(shade.listed().isEmpty())
    }

    @Test fun oneAppHasOneRecordOfItsOwnCalls() {
        assertSame(RecentCalls.of(context), RecentCalls.of(context.applicationContext))
    }
}

private fun Notification.title(): String = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

private fun Notification.isSummary(): Boolean = flags and Notification.FLAG_GROUP_SUMMARY != 0

/**
 * A shade that applies `notify()` and `cancel()` only when [settle] runs, in the order they were called: on a phone
 * the system applies them on its own thread, after the call has returned.
 */
private class LateShade : GroupShade {
    private val shown = LinkedHashMap<Int, Notification>()
    private val waiting = ArrayList<() -> Unit>()

    override fun notify(id: Int, notification: Notification): Boolean {
        waiting += { shown[id] = notification }
        return true
    }

    override fun cancel(id: Int) {
        waiting += { shown.remove(id) }
    }

    override fun listed(): Map<Int, Notification> = shown.toMap()

    /** The system has caught up with every call made so far. */
    fun settle() {
        waiting.forEach { it() }
        waiting.clear()
    }

    /** The person tapped the notification titled [title] (cancelled by its tap) or swiped it away. */
    fun removedByPerson(title: String) {
        shown.values.removeAll { it.title() == title }
    }

    /** Nothing is left on show: the person removed the last notification, and Android its summary with it. */
    fun clearedByPerson() = shown.clear()

    /** The notifications on show, the summary not counted. */
    fun children(): List<Notification> = shown.values.filterNot { it.isSummary() }

    /** Their titles, in the order they were first posted. */
    fun titles(): List<String> = children().map { it.title() }

    /** The summary on show, or null. */
    fun summary(): Notification? = shown.values.singleOrNull { it.isSummary() }
}
