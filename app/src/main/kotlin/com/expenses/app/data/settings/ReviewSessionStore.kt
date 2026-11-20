package com.expenses.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One run of Review payments, kept on disk until All sorted's Done (design 02-everyday/34 to 38): the decisions in
 * the order they were made, so Undo still works after Android stopped the app, and All sorted counts every payment of
 * the run, not only those sorted since the screen last opened. Only payment ids are kept, never SMS text.
 *
 * @param decisions the Add and Ignore decisions of this run, oldest first; Undo takes back the last one.
 * @param splitIds the payments of this run whose "Split it instead" was tapped, in that order. Once such a payment is
 * split it counts as added, with the person's share; while it still waits it is still a card of the run. Undo never
 * takes a split back (Undo on "Split. Sorted." does).
 * @param sorted whether the screen showed All sorted when the run was last written. Such a run was finished but not
 * ended: All sorted was left another way than Done (a notification or a link opened, the app swiped away). It still
 * offers Undo when the screen comes back to it, and ends as soon as a payment that is not of the run waits, so a later
 * review never carries it.
 */
@Serializable
data class ReviewSession(
    val decisions: List<ReviewSessionDecision> = emptyList(),
    val splitIds: List<Long> = emptyList(),
    val sorted: Boolean = false,
)

/**
 * One decision of a [ReviewSession]: one swipe or button, or Add all.
 *
 * @param added true for Add (or Add all), false for Ignore.
 * @param ids the payments it decided.
 */
@Serializable
data class ReviewSessionDecision(val added: Boolean, val ids: List<Long>)

/**
 * Where the current [ReviewSession] is kept (a DataStore file of its own, on this phone only; backups are off).
 *
 * Owned by the small-fixes area (docs/groups-build-plan.md). Review payments writes the run after each decision has
 * reached the database, inside [exclusive], so a screen opened again reads the run only after the writes of the one
 * before it have ended.
 */
class ReviewSessionStore(private val store: DataStore<Preferences>) {
    private val lock = Mutex()

    /** The current run; empty when none is open. */
    val session: Flow<ReviewSession> = store.data.map { prefs -> decode(prefs[SESSION]) }

    /**
     * Runs [block] while no other change to the run (made through this function) runs: a decision written to the
     * database and then to the run, an Undo, reading the run back, or ending it. Not reentrant.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    /** Replaces the run with what [change] makes of it, in one write. */
    suspend fun update(change: (ReviewSession) -> ReviewSession) {
        store.edit { prefs -> prefs[SESSION] = json.encodeToString(ReviewSession.serializer(), change(decode(prefs[SESSION]))) }
    }

    /** Ends the run (All sorted's Done). */
    suspend fun clear() {
        store.edit { it.remove(SESSION) }
    }

    private fun decode(text: String?): ReviewSession =
        text?.let { runCatching { json.decodeFromString(ReviewSession.serializer(), it) }.getOrNull() } ?: ReviewSession()

    private companion object {
        val SESSION = stringPreferencesKey("review_session")
        val json = Json { ignoreUnknownKeys = true }
    }
}
