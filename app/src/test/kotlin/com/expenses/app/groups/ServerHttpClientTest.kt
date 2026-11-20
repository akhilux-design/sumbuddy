package com.expenses.app.groups

import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.api.SetTo
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.api.serverHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The splitting server's shared HTTP client: a whole call is cut off after the call timeout, so a screen that waits
 * for a save (Back on Account and Your name) never waits longer, while a group's live socket stays open past it.
 */
class ServerHttpClientTest {
    private val fake = FakeServer()
    private val server = MockWebServer().apply {
        dispatcher = fake
        start()
    }
    private val session = FakeSession()

    @After fun close() {
        runCatching { server.shutdown() }
    }

    private fun api(callTimeoutMs: Long) =
        GroupsApi(server.url("/").toString().trimEnd('/'), serverHttpClient(callTimeoutMs), { session })

    @Test fun theSharedClientHasItsLimits() {
        val client = serverHttpClient()

        assertEquals(20_000, client.callTimeoutMillis)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
        assertEquals(15_000, client.writeTimeoutMillis)
    }

    @Test fun aSaveWhoseAnswerIsLateFailsAsOfflineAtTheCallTimeout() = runBlocking {
        // Each part of the answer comes within the read timeout, but the whole of it does not come in time.
        fake.on(
            "PATCH", "/v1/me",
            json(200, UserDto.serializer(), UserDto(ME_USER, "Arjun", null, null)).setHeadersDelay(3, TimeUnit.SECONDS),
        )
        val started = System.nanoTime()

        val result = api(callTimeoutMs = 500).updateMe(name = SetTo("Arjun"), idempotencyKey = "k-1")

        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 2_000)
        assertTrue((result as ApiResult.Error).error.isOffline)
        assertEquals(1, fake.requestsTo("PATCH", "/v1/me").size)
    }

    @Test fun anAnswerWithinTheCallTimeoutIsRead() = runBlocking {
        fake.on("GET", "/v1/me", json(200, UserDto.serializer(), UserDto(ME_USER, "Arjun", null, null)).setHeadersDelay(100, TimeUnit.MILLISECONDS))

        val result = api(callTimeoutMs = 2_000).me()

        assertEquals("Arjun", (result as ApiResult.Ok).value.name)
    }

    @Test fun theLiveSocketStaysOpenPastTheCallTimeout() {
        val received = LinkedBlockingQueue<String>()
        fake.on(
            "GET", "/v1/groups/$G1/live",
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received += text
                    }
                },
            ),
        )
        val opened = CountDownLatch(1)
        val failure = LinkedBlockingQueue<Throwable>()

        val socket = api(callTimeoutMs = 300).openLive(
            G1,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure += t
                }
            },
        )
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        // Well past the call timeout, the socket still works.
        Thread.sleep(1_000)
        assertTrue(socket.send("still here"))

        assertEquals("still here", received.poll(5, TimeUnit.SECONDS))
        assertNull(failure.poll())
        socket.close(1000, null)
    }
}
