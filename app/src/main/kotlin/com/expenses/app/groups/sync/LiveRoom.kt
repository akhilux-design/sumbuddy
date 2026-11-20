package com.expenses.app.groups.sync

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleStartEffect
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.data.GroupDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The live connection of one group (docs/server.md 5.3): while a screen of one group is visible and the app is in the
 * foreground, one WebSocket to `/v1/groups/{gid}/live`. Each time the socket opens (first connect and every
 * reconnect) the group is pulled once: while this phone counted as connected, the server sent it no push, and a
 * change made while the old socket was silently dead reached neither. A `changed` message with a version above the
 * phone's pulls that group; the comparison is [pullChanged]'s, made once the sync in progress has ended, so the many
 * messages of one long push (a Splitwise import notifies the room once per part, docs/server.md 4.17) cost one pull
 * and not one each. `revoked` (or close code 4403) syncs the group list; 4401 (the session ended or the token
 * expired) reconnects with a fresh token. It reconnects with back-off (2 s, 4 s, … 60 s) while the screen stays
 * visible. Other screens rely on push and foreground syncs. A group made on this phone connects only once its create
 * has reached the server (the outbox sent it): the page of a new group opens at once, offline too.
 *
 * Screens use [LiveGroup] rather than calling [open] and [close] themselves.
 *
 * @param pullGroup pulls one group whatever its version ([SyncEngine.pullGroup]): each time the socket opens.
 * @param pullChanged pulls one group only when the phone is behind the version given ([SyncEngine.pullChanged]): for
 * each `changed` message.
 */
class LiveRoom(
    private val api: GroupsApi,
    private val groups: GroupDao,
    private val pullGroup: suspend (String) -> Unit,
    private val pullChanged: suspend (String, Long) -> Unit,
    private val scheduler: SyncScheduler,
    private val scope: CoroutineScope,
    private val json: Json = ApiJson,
) {
    private val visible = MutableStateFlow<String?>(null)
    private var job: Job? = null

    /** The group whose screen is open now, or null; its changes post no notifications. */
    val visibleGroupId: StateFlow<String?> = visible.asStateFlow()

    /** A screen of [groupId] became visible. */
    @Synchronized
    fun open(groupId: String) {
        if (visible.value == groupId && job?.isActive == true) return
        job?.cancel()
        visible.value = groupId
        job = scope.launch { connectLoop(groupId) }
    }

    /** The screen of [groupId] is no longer visible. */
    @Synchronized
    fun close(groupId: String) {
        if (visible.value != groupId) return
        visible.value = null
        job?.cancel()
        job = null
    }

    private suspend fun CoroutineScope.connectLoop(groupId: String) {
        // A group made on this phone a moment ago reaches the server with its create, sent by the outbox (5.1). Before
        // that the server answers 403 not_member, which reads as "no longer in the group": wait for the create first.
        groups.observeCreateWaiting(groupId).first { waiting -> !waiting }
        // The server refused the create: the group is gone from this phone, and there is nothing to connect to.
        if (groups.getGroup(groupId) == null) return
        var backoff = FIRST_BACKOFF_MS
        while (isActive && visible.value == groupId) {
            val ended = CompletableDeferred<Boolean>()
            val socket = api.openLive(
                groupId,
                Listener(groupId, ended) {
                    backoff = FIRST_BACKOFF_MS
                    scope.launch { pullGroup(groupId) }
                },
            )
            val revoked = try {
                ended.await()
            } finally {
                socket.close(NORMAL_CLOSE, null)
            }
            if (revoked) {
                scheduler.requestSync()
                return
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private inner class Listener(
        private val groupId: String,
        private val ended: CompletableDeferred<Boolean>,
        private val onOpen: () -> Unit,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (message["type"]?.jsonPrimitive?.content) {
                "changed" -> {
                    val version = message["version"]?.jsonPrimitive?.longOrNull ?: return
                    // Not compared with the stored version here: a push in progress may hold the sync for minutes, and
                    // every message that arrived meanwhile would find the phone behind and pull once it ends.
                    scope.launch { pullChanged(groupId, version) }
                }
                "revoked" -> ended.complete(true)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSE, null)
            ended.complete(code == KICKED)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ended.complete(code == KICKED)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // 403 or 410 on the upgrade: the person is no longer in the group; the group list sync sorts it out.
            ended.complete(response?.code == FORBIDDEN || response?.code == GONE)
        }
    }

    private companion object {
        const val FIRST_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val NORMAL_CLOSE = 1000
        const val KICKED = 4403
        const val FORBIDDEN = 403
        const val GONE = 410
    }
}

/**
 * Keeps the live connection of [groupId] open while the calling screen is started (visible, app in the foreground).
 * Put it in the Group page, Settle up, and Split it once a group is chosen.
 */
@Composable
fun LiveGroup(room: LiveRoom, groupId: String) {
    LifecycleStartEffect(room, groupId) {
        room.open(groupId)
        onStopOrDispose { room.close(groupId) }
    }
}
