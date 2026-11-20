package com.expenses.app.ui.screens.splitwise

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.R
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.ImportedGroup
import com.expenses.app.ui.format.shortName
import com.expenses.app.ui.screens.onboarding.Effects
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What Group imported (design 02-everyday/31, 141) shows. */
@Immutable
sealed interface GroupImportedUiState {
    /** The group is being read. */
    data object Loading : GroupImportedUiState

    /** The group is not on this phone (any more): the caller leaves for the Groups tab. */
    data object Gone : GroupImportedUiState

    /**
     * The group that came across.
     *
     * @param groupName its name ("Flat 302 is in").
     * @param expenseCount the past expenses that came ("Balances and 46 past expenses came across."); 0 says
     * "Balances came across.".
     * @param friendNames the friends who get a link, in their first names where those stay clear ("Priya and Kiran").
     * @param banner Offline (141) or Problem (141's alternative) while the links cannot be made, else none.
     * @param sharing true while the links are being made: Share is off.
     */
    data class Shown(
        val groupName: String,
        val expenseCount: Int,
        val friendNames: List<String>,
        val banner: ImportBanner = ImportBanner.None,
        val sharing: Boolean = false,
    ) : GroupImportedUiState
}

/** Everything the person can do on Group imported. */
sealed interface GroupImportedEvent {
    /** "Share invite links". */
    data object Share : GroupImportedEvent

    /** "Later", or Back: the group's page. */
    data object Later : GroupImportedEvent
}

/** One-shot messages from Group imported to its screen. */
sealed interface GroupImportedEffect {
    /** Send [text] from the person's WhatsApp (or another app), then open group [groupId]'s page. */
    data class Share(val text: String, val groupId: String) : GroupImportedEffect

    /** Open group [groupId]'s page on the Groups tab. */
    data class OpenGroup(val groupId: String) : GroupImportedEffect

    /** The session ended: open Sign in. */
    data object SignIn : GroupImportedEffect
}

/**
 * One line of the invite message: a friend's full name and their own pay link.
 *
 * @param name the friend's name as stored ("Priya Nair").
 * @param link their link (`https://<LINK_HOST>/pay/<token>`).
 */
data class InviteLine(val name: String, val link: String)

/**
 * The one invite message of Group imported (docs/splitwise-import.md 5.8): who it is from is the person's own
 * WhatsApp; the app's name comes only from `app_name`. Nothing of it is logged: it holds every friend's link.
 */
object ImportInviteMessage {
    /** "Flat 302 is on SumBuddy now, …", "Open the link with your name …", then "Priya Nair: https://…" per friend. */
    fun text(res: Resources, groupName: String, lines: List<InviteLine>): String = buildList {
        add(res.getString(R.string.import_message_intro, groupName, res.getString(R.string.app_name)))
        add(res.getString(R.string.import_message_how))
        lines.forEach { add(res.getString(R.string.import_message_line, it.name, it.link)) }
    }.joinToString("\n")
}

/**
 * Group imported for [groupId] (docs/splitwise-import.md 5.8): Share makes each friend's own pay link in turn
 * ([GroupsRepository.shareLink], reused within 60 days), waiting out the server's `Retry-After` once per link (at most
 * a minute), and hands the one message to the screen. Offline or on any other error it stops and says so with a
 * banner; nothing is lost, and Share tries again.
 *
 * @param message builds the message ([ImportInviteMessage.text]).
 * @param sleep waits a 429's `Retry-After`, in milliseconds; tests pass a fake.
 */
class GroupImportedViewModel(
    private val groupId: String,
    private val groups: GroupsRepository,
    private val online: StateFlow<Boolean>,
    private val message: (groupName: String, lines: List<InviteLine>) -> String,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private val problem = MutableStateFlow(ImportBanner.None)
    private val sharing = MutableStateFlow(false)
    private val effects = Effects<GroupImportedEffect>()

    /** One-shot messages for the screen. */
    val effect: Flow<GroupImportedEffect> = effects.flow

    /** The screen's state. */
    val state: StateFlow<GroupImportedUiState> =
        combine(groups.observeImported(groupId), online, problem, sharing) { imported, isOnline, trouble, busy ->
            shownOf(imported, isOnline, trouble, busy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), GroupImportedUiState.Loading)

    /** Handles what the person did. */
    fun onEvent(event: GroupImportedEvent) {
        when (event) {
            GroupImportedEvent.Share -> share()
            GroupImportedEvent.Later -> effects.send(GroupImportedEffect.OpenGroup(groupId))
        }
    }

    private fun shownOf(imported: ImportedGroup?, isOnline: Boolean, trouble: ImportBanner, busy: Boolean): GroupImportedUiState {
        imported ?: return GroupImportedUiState.Gone
        return GroupImportedUiState.Shown(
            groupName = imported.groupName,
            expenseCount = imported.expenseCount,
            friendNames = imported.friends.map { shortName(it.name, imported.memberNames) },
            banner = if (!isOnline) ImportBanner.Offline else trouble,
            sharing = busy,
        )
    }

    private fun share() {
        // Offline, the banner already says so (141); links need the server.
        if (sharing.value || !online.value) return
        sharing.value = true
        problem.value = ImportBanner.None
        viewModelScope.launch {
            try {
                val imported = groups.observeImported(groupId).first() ?: return@launch
                val lines = ArrayList<InviteLine>(imported.friends.size)
                for (friend in imported.friends) {
                    val link = linkFor(friend.id) ?: return@launch
                    lines += InviteLine(friend.name, link)
                }
                effects.send(GroupImportedEffect.Share(message(imported.groupName, lines), groupId))
            } finally {
                sharing.value = false
            }
        }
    }

    /** [memberId]'s link, after one wait for a 429; null when it could not be made (the banner then says why). */
    private suspend fun linkFor(memberId: String): String? {
        var result = groups.shareLink(groupId, memberId)
        val limited = (result as? ApiResult.Error)?.error?.takeIf { it.isRateLimited }
        if (limited != null) {
            sleep((limited.retryAfterSeconds ?: MAX_WAIT_SECONDS).coerceIn(0, MAX_WAIT_SECONDS) * MILLIS_PER_SECOND)
            result = groups.shareLink(groupId, memberId)
        }
        return when (result) {
            is ApiResult.Ok -> result.value
            is ApiResult.Error -> {
                val error = result.error
                when {
                    error.needsSignIn -> effects.send(GroupImportedEffect.SignIn)
                    error.isOffline -> problem.value = ImportBanner.Offline
                    else -> problem.value = ImportBanner.Problem
                }
                null
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MAX_WAIT_SECONDS = 60L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
