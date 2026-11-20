package com.expenses.app.groups.sync

import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.api.CreateGroupResponse
import com.expenses.app.groups.api.GroupDto
import com.expenses.app.groups.error
import com.expenses.app.groups.json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The live room of a group made on this phone (docs/server.md 5.3): it connects only once the group's create has
 * reached the server. Connecting first got `403 not_member`, which reads as "no longer in the group".
 */
@RunWith(RobolectricTestRunner::class)
class LiveRoomTest {
    private val env = GroupsTestEnv()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pulls = CopyOnWriteArrayList<String>()
    private val changed = CopyOnWriteArrayList<Pair<String, Long>>()
    private val room = LiveRoom(
        env.api, env.db.groupDao(),
        pullGroup = { pulls += it },
        pullChanged = { groupId, version -> changed += groupId to version },
        scheduler = env.scheduler, scope = scope,
    )

    @After fun close() {
        scope.cancel()
        env.close()
    }

    private fun livePath(groupId: String) = "/v1/groups/$groupId/live"

    private fun acceptSockets(groupId: String) =
        env.fake.on("GET", livePath(groupId), MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))

    private fun connects(groupId: String): Int = env.fake.requestsTo("GET", livePath(groupId)).size

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!condition()) delay(POLL_MS)
    }

    @Test fun aNewGroupConnectsOnceItsCreateReachedTheServer() = runBlocking {
        val goa = env.groups.createGroup("Goa trip", emptyList())
        acceptSockets(goa)

        room.open(goa)
        delay(SETTLE_MS)
        // The create still waits in the outbox: no socket, so no 403 and no list sync from it.
        assertEquals(0, connects(goa))

        env.fake.on(
            "POST", "/v1/groups",
            json(201, CreateGroupResponse.serializer(), CreateGroupResponse(GroupDto(goa, "group", "Goa trip", ME_USER, 1, 0), emptyList())),
        )
        env.engine.push()

        waitUntil { connects(goa) == 1 && pulls.isNotEmpty() }
        val order = env.fake.requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" }
        assertTrue(order.indexOf("POST /v1/groups") < order.indexOf("GET ${livePath(goa)}"))
        assertEquals(listOf(goa), pulls.distinct())
        room.close(goa)
    }

    @Test fun aRefusedCreateNeverConnects() = runBlocking {
        val goa = env.groups.createGroup("Goa trip", emptyList())
        acceptSockets(goa)
        room.open(goa)
        env.fake.on("POST", "/v1/groups", error(400, "bad_request"))

        env.engine.push()

        assertNull(env.db.groupDao().getGroup(goa))
        delay(SETTLE_MS)
        assertEquals(0, connects(goa))
    }

    @Test fun aGroupTheServerHasConnectsAtOnce() = runBlocking {
        env.seedGroup(G1)
        acceptSockets(G1)

        room.open(G1)

        waitUntil { connects(G1) == 1 && pulls.isNotEmpty() }
        room.close(G1)
    }

    /**
     * The socket's open pulls the group whatever its version; a `changed` message only hands its version on, and the
     * sync engine decides under its lock whether the phone is behind (a message at or below the stored version too:
     * the room does not compare, since a push in progress may be about to move the stored version).
     */
    @Test fun anOpenSocketPullsOnceAndEachChangedMessageAsksWithItsVersion() = runBlocking {
        env.seedGroup(G1, version = 5)
        val serverSocket = AtomicReference<WebSocket?>(null)
        env.fake.on(
            "GET", livePath(G1),
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) = serverSocket.set(webSocket)
                },
            ),
        )

        room.open(G1)
        waitUntil { serverSocket.get() != null && pulls.isNotEmpty() }
        assertTrue(changed.isEmpty())

        serverSocket.get()!!.send("""{"type":"changed","version":7}""")
        serverSocket.get()!!.send("""{"type":"changed","version":4}""")
        serverSocket.get()!!.send("""{"type":"changed"}""")
        waitUntil { changed.size == 2 }
        delay(SETTLE_MS)

        assertEquals(setOf(G1 to 7L, G1 to 4L), changed.toSet())
        assertEquals(2, changed.size)
        // Only the open pulled without asking.
        assertEquals(listOf(G1), pulls.toList())
        room.close(G1)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 300L
    }
}
