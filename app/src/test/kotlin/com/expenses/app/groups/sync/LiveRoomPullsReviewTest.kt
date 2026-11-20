package com.expenses.app.groups.sync

import com.expenses.app.data.category.Category
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.api.ChangesPage
import com.expenses.app.groups.api.ExpenseDto
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.expenseDto
import com.expenses.app.groups.json
import com.expenses.app.groups.page
import com.expenses.splits.SplitMethod
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Review (2026-11-19). A Splitwise import notifies the group's room once per part (docs/server.md 4.17), up to 100
 * parts, while this phone's push run holds the sync lock for all of them (up to about 17 minutes with 429 waits). The
 * import screen's "Open {group}" opens the Group page, whose live socket then gets every one of those `changed`
 * messages. [LiveRoom] compares each message's version with the local one when it arrives, before the lock, and queues
 * a [SyncEngine.pullGroup] for each; once the run ends they all run, one `GET /changes` each, although the first one
 * already brought the group up to date. 100 such requests in a burst, with the run's own pages, pass `RL_USER`'s 120 a
 * minute, and a `429` on a pull sets the group's load as failed ([SyncEngine] `setLoadFailed`).
 */
@RunWith(RobolectricTestRunner::class)
class LiveRoomPullsReviewTest {
    private val env = GroupsTestEnv()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val room = LiveRoom(
        env.api, env.db.groupDao(),
        pullGroup = { env.engine.pullGroup(it) },
        pullChanged = { groupId, version -> env.engine.pullChanged(groupId, version) },
        scheduler = env.scheduler, scope = scope,
    )
    private val serverSocket = AtomicReference<WebSocket?>(null)

    @After fun close() {
        scope.cancel()
        env.close()
    }

    private fun changesPath() = "/v1/groups/$G1/changes"

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!condition()) delay(POLL_MS)
    }

    @Test fun changedMessagesThatArriveDuringOneLongPushPullOnceAfterIt() = runBlocking {
        env.seedGroup(G1, version = 5)
        env.fake.on(
            "GET", "/v1/groups/$G1/live",
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) = serverSocket.set(webSocket)
                },
            ),
        )
        // The socket's own pull when it opens, then everything up to version 10, then nothing new.
        env.fake.on(
            "GET", changesPath(),
            json(200, ChangesPage.serializer(), page(G1, from = 5, to = 5)),
            json(200, ChangesPage.serializer(), page(G1, from = 5, to = 10)),
            json(200, ChangesPage.serializer(), page(G1, from = 10, to = 10)),
        )
        room.open(G1)
        waitUntil { serverSocket.get() != null && env.fake.requestsTo("GET", changesPath()).size == 1 }

        // A long push holds the sync lock (an import's parts; here one slow write is enough).
        val milk = env.groups.addGroupExpense(
            G1,
            GroupExpenseDraft("Milk", Category.GROCERIES, 6_000, LocalDate.of(2026, 11, 15), M_RAHUL, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null)),
        )
        env.fake.on(
            "PUT", "/v1/groups/$G1/expenses/$milk",
            json(201, ExpenseDto.serializer(), expenseDto(milk, M_RAHUL, 6_000, mapOf(M_ME to 3_000, M_RAHUL to 3_000), 6))
                .setHeadersDelay(1_500, TimeUnit.MILLISECONDS),
        )
        val push = async(Dispatchers.IO) { env.engine.push() }
        delay(300)
        // Meanwhile the room says the group moved, once per part.
        for (version in 6L..10L) serverSocket.get()!!.send("""{"type":"changed","version":$version}""")
        push.await()
        delay(SETTLE_MS)

        val pulls = env.fake.requestsTo("GET", changesPath()).size - 1
        assertTrue("$pulls pulls of an up-to-date group after one push", pulls <= 2)
        room.close(G1)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 1_500L
    }
}
