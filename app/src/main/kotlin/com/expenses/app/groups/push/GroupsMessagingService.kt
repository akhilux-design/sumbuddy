package com.expenses.app.groups.push

import com.expenses.app.sms.appContainer
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/**
 * Receives the server's data-only pushes (docs/server.md 4.14): `changed` (`g`, `v`), `groups`, and `remind` (`g`,
 * `m`). A push only asks for a sync; it never carries or shows text. `changed` pulls only its group, and only when
 * the phone is behind (one request, not the group list and every group); `groups` and `remind` sync everything.
 * Firebase runs this service only when [PushSetup] started it.
 */
class GroupsMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val app = applicationContext.appContainer
        val data = message.data
        when (data["t"]) {
            "changed" -> {
                val group = data["g"]
                val version = data["v"]?.toLongOrNull()
                if (group != null && version != null) app.sync.requestGroupPull(group, version) else app.sync.requestSync(expedited = true)
            }
            "groups" -> app.sync.requestSync(expedited = true)
            "remind" -> {
                val group = data["g"]
                val member = data["m"]
                if (group != null && member != null) app.sync.requestReminder(group, member) else app.sync.requestSync()
            }
        }
    }

    @Deprecated("Firebase 26 reports tokens through onRegistered; older paths still call this.")
    override fun onNewToken(token: String) = sendToken(token)

    override fun onRegistered(token: String) = sendToken(token)

    /** Sent only while someone is signed in, and only when the server does not have this token yet. */
    private fun sendToken(token: String) {
        val app = applicationContext.appContainer
        app.appScope.launch { app.push.sendToken(token) }
    }
}
