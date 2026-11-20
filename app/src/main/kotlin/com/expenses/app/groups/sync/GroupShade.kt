package com.expenses.app.groups.sync

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat

/**
 * The phone's notification shade as [GroupNotifications] uses it: the "Groups" notifications only. [SystemShade] is
 * the real one; tests pass one that applies calls late, as Android does.
 */
internal interface GroupShade {
    /** Posts or replaces notification [id]. False when the system refused it (the permission was taken away). */
    fun notify(id: Int, notification: Notification): Boolean

    /** Removes notification [id]. Nothing happens when it is not there. */
    fun cancel(id: Int)

    /**
     * The notifications the system lists now, by id. On a phone the list lags behind [notify] and [cancel]: the system
     * applies both on its own thread, after the call has returned.
     */
    fun listed(): Map<Int, Notification>
}

/** The system's shade: every notification this app posted with [tag]. */
internal class SystemShade(private val context: Context, private val tag: String) : GroupShade {
    override fun notify(id: Int, notification: Notification): Boolean = try {
        NotificationManagerCompat.from(context).notify(tag, id, notification)
        true
    } catch (_: SecurityException) {
        // The permission was taken away meanwhile.
        false
    }

    override fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(tag, id)

    override fun listed(): Map<Int, Notification> = runCatching {
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.tag == tag }
            .associate { it.id to it.notification }
    }.getOrDefault(emptyMap())
}

/**
 * What this app posted and removed a moment ago. For that moment the system's list ([GroupShade.listed]) can miss a
 * notification just posted and still show one just removed, so the app's own call is believed over the list:
 *
 * - a notification posted counts as there until the list shows it, for [SETTLE_MS] at most;
 * - a notification removed counts as gone for [SETTLE_MS], whatever the list says. A notification the person tapped
 *   or swiped away is removed here too: its own intents report it ([GroupNotifications.removedByPerson]).
 *
 * Both mistakes it guards against matter: a summary removed over a notification the system has not listed yet takes
 * that notification away with it (Android cancels what is under a cancelled summary), and a summary kept over one
 * that is already gone stays on show empty.
 *
 * One per app process ([of]), because [GroupNotifications] is made anew wherever it is used.
 *
 * @param now a clock that only moves forward, in milliseconds.
 */
internal class RecentCalls(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val posted = HashMap<Int, Long>()
    private val cancelled = HashMap<Int, Long>()

    /** This app posted (or replaced) notification [id] just now. */
    @Synchronized
    fun posted(id: Int) {
        cancelled.remove(id)
        posted[id] = now()
    }

    /** This app removed notification [id] just now. */
    @Synchronized
    fun cancelled(id: Int) {
        posted.remove(id)
        cancelled[id] = now()
    }

    /** The notifications there now: [listed] by the system, put right by what this app did a moment ago. */
    @Synchronized
    fun correct(listed: Set<Int>): Set<Int> {
        val time = now()
        posted.entries.removeAll { (id, at) -> id in listed || time - at > SETTLE_MS }
        cancelled.values.removeAll { at -> time - at > SETTLE_MS }
        return listed - cancelled.keys + posted.keys
    }

    /** How long a call is believed, and the one record per app. */
    companion object {
        /**
         * How long the app's own call is believed over the system's list. The system usually needs a fraction of a
         * second, more on a slow or busy phone, so this errs on the long side. That is safe: what the person removes
         * in that time is reported to the app and counts as removed, so it is not believed to be there still.
         */
        const val SETTLE_MS: Long = 5_000

        private val perApp = java.util.WeakHashMap<Context, RecentCalls>()

        /** The calls of the app [context] belongs to (one per application object, so every test starts clean). */
        fun of(context: Context): RecentCalls = synchronized(perApp) {
            perApp.getOrPut(context.applicationContext ?: context) { RecentCalls() }
        }
    }
}
