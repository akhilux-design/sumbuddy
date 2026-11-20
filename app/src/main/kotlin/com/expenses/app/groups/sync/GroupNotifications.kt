package com.expenses.app.groups.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.expenses.app.MainActivity
import com.expenses.app.R
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.nav.AppLink

/**
 * The "Groups" notifications (docs/server.md 9.7): what friends did that involves the person, and what the phone
 * settled by itself from an SMS (9.5). Only names and amounts, never anything else of the expense or of the SMS.
 *
 * - A tap on a friend's change opens that group's page ([AppLink.Group]); "Meera joined Home" opens Home on the
 *   Family side ([AppLink.Family]).
 * - "Priya marked ₹1,200 as paid", when the person may confirm, has a Confirm button handled by
 *   [GroupActionReceiver] without opening the app.
 * - "Rahul paid you ₹1,200 / Flat 302 balance cleared. Nothing to tick." (design 02-everyday/04) opens All square
 *   ([AppLink.AllSquare]); "Was this Rahul paying you back?" opens Money received ([AppLink.MoneyReceived]).
 * - They are one bundle (`setGroup`) under one summary, which is posted together with every notification, the first
 *   one too: Android shows one alone without its summary. A tap on the bundle opens the app and leaves every
 *   notification in it; they go one by one as they are tapped, answered or swiped away. Without the app's own
 *   summary, Android 16 bundles two or more by itself, and a tap on that bundle removes them all: a question not
 *   answered yet was lost that way. The summary goes when nothing is left under it ([tidy]), and only then.
 * - `notify()` and `cancel()` return before the system has applied them, so its list of active notifications is not
 *   taken as the truth right after one of the app's own calls: [RecentCalls] remembers what was just posted and
 *   removed. One change of the bundle runs at a time.
 * - Each notification carries its own id in its tap and swipe intents, so the app is told which one the person
 *   removed ([removedByPerson]) and takes the summary away with the last one at once. It relies neither on Android's
 *   shade doing that nor on the next sync.
 * - Sign out and account deletion remove them all ([cancelAll]).
 *
 * Owned by the settle-up area (docs/groups-build-plan.md).
 */
class GroupNotifications internal constructor(
    private val context: Context,
    private val shade: GroupShade,
    private val recent: RecentCalls,
) : GroupNotifier {
    /** The "Groups" notifications in this phone's shade. */
    constructor(context: Context) : this(context, SystemShade(context, TAG), RecentCalls.of(context))

    override fun post(events: List<GroupEvent>) {
        if (events.isEmpty() || !allowed()) return
        createChannel()
        postAll(
            events.map { event ->
                val key = key(event)
                val builder = builder(key, text(event), body = null, link = linkOf(event))
                if (event is GroupEvent.SettlementRecorded && event.canConfirm) {
                    builder.addAction(NO_ICON, context.getString(R.string.notify_action_confirm), confirm(event.settlementId, key))
                }
                key to builder.build()
            },
        )
    }

    /**
     * What a tap on [event] opens: the group's page; "Meera joined Home" ([GroupEvent.MemberJoined.family]) opens Home
     * on the Family side instead ([AppLink.Family], docs/family.md 1.1). A family never shows as a Group page (8.2).
     */
    private fun linkOf(event: GroupEvent): AppLink =
        if (event is GroupEvent.MemberJoined && event.family) AppLink.Family else AppLink.Group(event.groupId)

    /** Removes every "Groups" notification, the summary included (sign out, account deletion, another account). */
    override fun cancelAll(): Unit = synchronized(recent) {
        // Also what was posted a moment ago and the system does not list yet, and the summary in any case.
        (recent.correct(shade.listed().keys) + notificationId(SUMMARY)).forEach { cancel(it) }
    }

    /**
     * Removes the summary once no notification is left under it: Android leaves the summary of an empty bundle on
     * show. Runs after every removal the app makes or is told of ([removedByPerson]), and after each sync as a net
     * for the rest. It only ever removes: the summary is posted with a notification ([postAll]), never by itself, so
     * a tidy cannot bring back a summary over nothing.
     */
    override fun tidy(): Unit = synchronized(recent) {
        if (children().isEmpty()) cancel(notificationId(SUMMARY))
    }

    /**
     * A friend's money arrived and cleared their balance by itself (design 02-everyday/04, second notification):
     * "Rahul paid you ₹1,200" / "Flat 302 balance cleared. Nothing to tick.". A tap opens [open].
     *
     * @param creditId the money-in payment, so the same SMS read twice gives one notification.
     * @param friendName who paid.
     * @param amountMinor what arrived, in paise.
     * @param cleared the groups whose balance it cleared: kind and name.
     * @param open All square for the friend in the group with the largest transfer.
     */
    fun postRepaid(creditId: Long, friendName: String, amountMinor: Long, cleared: List<Pair<GroupKind, String>>, open: AppLink.AllSquare) {
        if (!allowed()) return
        createChannel()
        val title = context.getString(R.string.notify_repaid_title, friendName, formatRupees(amountMinor))
        val key = REPAID + creditId
        postAll(listOf(key to builder(key, title, clearedText(cleared), open).build()))
    }

    /**
     * The person's Pay by UPI went through and the balance closed by itself (the payer's side of 03-getting-paid-back
     * flow lane 05, "Both people are notified"; not designed): "You paid Rahul ₹1,200" / "Flat 302 balance cleared.
     * Nothing to tick.". A tap opens the group.
     *
     * @param debitId the payment, so the same SMS read twice gives one notification.
     * @param friendName who was paid.
     * @param amountMinor what was paid, in paise.
     * @param groupId the group.
     * @param kind a named group or a direct split.
     * @param groupName the group's name.
     */
    fun postPaidByUpi(debitId: Long, friendName: String, amountMinor: Long, groupId: String, kind: GroupKind, groupName: String) {
        if (!allowed()) return
        createChannel()
        val title = context.getString(R.string.notify_paid_title, friendName, formatRupees(amountMinor))
        val key = PAID + debitId
        postAll(listOf(key to builder(key, title, clearedText(listOf(kind to groupName)), AppLink.Group(groupId)).build()))
    }

    /**
     * Money arrived that is exactly what [friendName] owes, but the SMS did not clearly name them (not designed; it
     * opens 03-getting-paid-back/02): "Was this Rahul paying you back?" / "₹1,200 arrived. It matches what Rahul owes.".
     *
     * @param creditId the money-in payment; a tap opens Money received for it.
     * @param friendName the friend it may be.
     * @param amountMinor what arrived, in paise.
     */
    fun postAskRepaid(creditId: Long, friendName: String, amountMinor: Long) {
        if (!allowed()) return
        createChannel()
        val title = context.getString(R.string.notify_ask_title, friendName)
        val body = context.getString(R.string.notify_ask_body, formatRupees(amountMinor), friendName)
        val key = ASK + creditId
        postAll(listOf(key to builder(key, title, body, AppLink.MoneyReceived(creditId)).build()))
    }

    /** Removes "… marked ₹X as paid" of [settlementId], once it was confirmed. */
    fun cancelSettlement(settlementId: String): Unit = remove(notificationId(SETTLEMENT + settlementId))

    /** Removes "Was this … paying you back?" of [creditId], once the person answered it. */
    fun cancelAsk(creditId: Long): Unit = remove(notificationId(ASK + creditId))

    /**
     * The person removed one of these notifications themselves: its tap opened the app with [intent]
     * ([MainActivity]), or its swipe sent [intent] to [GroupActionReceiver]. The intent says which notification it
     * was, so that one counts as gone at once, whatever the system lists at this moment, and the summary goes with
     * the last one. A tap does not wait for the sync that may follow it: none follows offline, within 30 seconds of
     * the last one, or while the app is on screen already.
     *
     * An intent that names no notification (another notification's, or one posted by an older build) only tidies.
     */
    fun removedByPerson(intent: Intent) {
        if (intent.hasExtra(EXTRA_NOTIFICATION_ID)) remove(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)) else tidy()
    }

    /**
     * Posts [notifications] (each with its key), then the summary that heads the bundle: with every notification, so
     * none is on show without it, whatever the system lists at that moment.
     */
    private fun postAll(notifications: List<Pair<String, Notification>>): Unit = synchronized(recent) {
        val posted = notifications.count { (key, notification) -> notify(notificationId(key), notification) }
        if (posted > 0) summarize()
    }

    /** Removes notification [id], and the summary when that was the last one under it. */
    private fun remove(id: Int): Unit = synchronized(recent) {
        cancel(id)
        tidy()
    }

    /**
     * Posts the summary, or brings its count up to date. A tap on it opens the app; it is not cancelled by the tap,
     * so the notifications under it stay.
     */
    private fun summarize() {
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_users_line)
            .setContentTitle(context.getString(R.string.notify_channel))
            .setNumber(children().size)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            // Only the notifications under it make a sound; the summary never does.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(openApp())
            .build()
        notify(notificationId(SUMMARY), summary)
    }

    /**
     * The notifications in the bundle now, the summary not counted: the system's list, put right by this app's own
     * calls of a moment ago ([RecentCalls]).
     */
    private fun children(): Set<Int> =
        recent.correct(shade.listed().filterValues { it.group == GROUP_KEY }.keys) - notificationId(SUMMARY)

    private fun text(event: GroupEvent): String = when (event) {
        is GroupEvent.ExpenseAdded ->
            context.getString(R.string.notify_expense_added, event.actorName, event.description, formatRupees(event.myShareMinor))
        is GroupEvent.SettlementRecorded ->
            context.getString(R.string.notify_marked_paid, event.actorName, formatRupees(event.amountMinor))
        is GroupEvent.SettlementConfirmed ->
            context.getString(R.string.notify_confirmed, event.actorName, formatRupees(event.amountMinor))
        is GroupEvent.MemberJoined -> context.getString(R.string.notify_joined, event.memberName, event.groupName)
        is GroupEvent.Reminded ->
            context.getString(R.string.notify_reminded, event.actorName, formatRupees(event.amountMinor), event.groupName)
    }

    /** "Flat 302 balance cleared. Nothing to tick.", or the words for a direct split or several groups. */
    private fun clearedText(cleared: List<Pair<GroupKind, String>>): String {
        val single = cleared.singleOrNull()
        return when {
            single == null -> context.resources.getQuantityString(R.plurals.notify_cleared_groups, cleared.size, cleared.size)
            single.first == GroupKind.GROUP -> context.getString(R.string.notify_cleared_group, single.second)
            else -> context.getString(R.string.notify_cleared_direct)
        }
    }

    /** One notification per thing: a second sync of the same change replaces it instead of adding another. */
    private fun key(event: GroupEvent): String = when (event) {
        is GroupEvent.ExpenseAdded -> "e:" + event.expenseId
        is GroupEvent.SettlementRecorded -> SETTLEMENT + event.settlementId
        is GroupEvent.SettlementConfirmed -> "c:" + event.settlementId
        is GroupEvent.MemberJoined -> "j:" + event.memberId
        is GroupEvent.Reminded -> "r:" + event.groupId
    }

    private fun builder(key: String, title: String, body: String?, link: AppLink): NotificationCompat.Builder {
        val id = notificationId(key)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_users_line)
            .setContentTitle(title)
            .apply { if (body != null) setContentText(body) }
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(open(link, id))
            .setGroup(GROUP_KEY)
            .setDeleteIntent(swiped(id))
    }

    /** Posts notification [id]. False when the system refused it. */
    private fun notify(id: Int, notification: Notification): Boolean {
        if (!shade.notify(id, notification)) return false
        recent.posted(id)
        return true
    }

    private fun cancel(id: Int) {
        shade.cancel(id)
        recent.cancelled(id)
    }

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = context.getString(R.string.notify_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Opens [link] in the app and says which notification was tapped ([removedByPerson]). Different links get
     * different intents ([MainActivity.openLinkIntent]); the notification's id is the request code, so two
     * notifications that open the same screen still have an intent each, with their own id.
     */
    private fun open(link: AppLink, id: Int): PendingIntent = PendingIntent.getActivity(
        context,
        id,
        MainActivity.openLinkIntent(context, link).putExtra(EXTRA_NOTIFICATION_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The summary's tap: the app as it was left, as its launcher icon opens it. */
    private fun openApp(): PendingIntent {
        val launch = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, REQUEST_OPEN_APP, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Sent when notification [id] is swiped away, or cleared with the rest: it says which one went
     * ([removedByPerson]), and the summary goes with the last one.
     */
    private fun swiped(id: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        id,
        Intent(context, GroupActionReceiver::class.java)
            .setAction(GroupActionReceiver.ACTION_TIDY)
            .putExtra(EXTRA_NOTIFICATION_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Confirm on "Priya marked ₹1,200 as paid". The settlement id travels in an extra, which logs do not print. */
    private fun confirm(settlementId: String, key: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        notificationId(key),
        Intent(context, GroupActionReceiver::class.java)
            .setAction(GroupActionReceiver.ACTION_CONFIRM)
            .putExtra(GroupActionReceiver.EXTRA_SETTLEMENT_ID, settlementId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationId(key: String): Int = key.hashCode()

    private companion object {
        const val CHANNEL_ID = "groups"
        const val TAG = "groups"
        const val GROUP_KEY = "com.expenses.app.groups"
        const val SUMMARY = "summary"
        const val REQUEST_OPEN_APP = 1
        const val NO_ICON = 0
        const val SETTLEMENT = "s:"
        const val REPAID = "repaid:"
        const val PAID = "paid:"
        const val ASK = "ask:"

        /** Intent extra of a notification's tap and swipe intents: that notification's id, an Int. */
        const val EXTRA_NOTIFICATION_ID = "com.expenses.app.groups.extra.NOTIFICATION_ID"
    }
}
