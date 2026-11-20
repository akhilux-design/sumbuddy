package com.expenses.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run of Review payments on disk: ids only (and whether All sorted showed), kept across a new store on the same
 * file, gone after Done.
 */
class ReviewSessionStoreTest {
    private val folder: File = Files.createTempDirectory("review-session").toFile()
    private val file = File(folder, "review.preferences_pb")

    @After fun deleteFolder() {
        folder.deleteRecursively()
    }

    private fun TestScope.dataStore(scope: CoroutineScope = backgroundScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { file }

    private val run = ReviewSession(
        decisions = listOf(
            ReviewSessionDecision(added = true, ids = listOf(4)),
            ReviewSessionDecision(added = false, ids = listOf(7)),
            ReviewSessionDecision(added = true, ids = listOf(9, 12, 15)),
        ),
        splitIds = listOf(5),
    )

    @Test fun noRunAtFirst() = runTest {
        assertEquals(ReviewSession(), ReviewSessionStore(dataStore()).session.first())
    }

    @Test fun keepsTheDecisionsInOrderAndTheSplits() = runTest {
        val store = ReviewSessionStore(dataStore())
        store.update { run.copy(decisions = run.decisions.take(2)) }
        store.update { it.copy(decisions = it.decisions + run.decisions[2]) }

        assertEquals(run, store.session.first())
    }

    @Test fun theRunOutlivesTheStore() = runTest {
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        ReviewSessionStore(dataStore(firstScope)).update { run }
        // The app is stopped; a new process opens the same file.
        firstScope.coroutineContext[Job]!!.cancelAndJoin()

        assertEquals(run, ReviewSessionStore(dataStore()).session.first())
    }

    @Test fun clearEndsTheRun() = runTest {
        val store = ReviewSessionStore(dataStore())
        store.update { run }
        store.clear()

        assertEquals(ReviewSession(), store.session.first())
    }

    @Test fun onlyIdsAreWritten() = runTest {
        val data = dataStore()
        ReviewSessionStore(data).update { run }

        val written = data.data.first().asMap().values.single() as String
        assertEquals(
            """{"decisions":[{"added":true,"ids":[4]},{"added":false,"ids":[7]},{"added":true,"ids":[9,12,15]}],"splitIds":[5]}""",
            written,
        )
    }

    @Test fun keepsWhetherTheRunWasSorted() = runTest {
        val data = dataStore()
        ReviewSessionStore(data).update { run.copy(decisions = run.decisions.take(1), splitIds = emptyList(), sorted = true) }

        val written = data.data.first().asMap().values.single() as String
        assertEquals("""{"decisions":[{"added":true,"ids":[4]}],"sorted":true}""", written)
        assertTrue(ReviewSessionStore(data).session.first().sorted)
    }

    @Test fun anUnreadableRunReadsAsNone() = runTest {
        val data = dataStore()
        data.edit { it[stringPreferencesKey("review_session")] = "{not a run" }
        val store = ReviewSessionStore(data)

        assertEquals(ReviewSession(), store.session.first())
        store.update { it.copy(splitIds = listOf(3)) }
        assertEquals(ReviewSession(splitIds = listOf(3)), store.session.first())
    }

    @Test fun exclusiveRunsOneChangeAtATime() = runTest {
        val store = ReviewSessionStore(dataStore())
        val order = mutableListOf<String>()
        val slow = async { store.exclusive { order += "slow start"; delay(100); order += "slow end" } }
        val quick = async { store.exclusive { order += "quick" } }
        slow.await()
        quick.await()

        assertEquals(listOf("slow start", "slow end", "quick"), order)
    }
}
