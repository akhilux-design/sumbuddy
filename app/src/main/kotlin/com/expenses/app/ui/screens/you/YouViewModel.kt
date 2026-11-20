package com.expenses.app.ui.screens.you

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.repository.AccountRepository
import com.expenses.app.data.repository.ReadSmsRepository
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.groups.auth.AuthState
import com.expenses.app.groups.data.FamilyInfo
import com.expenses.app.groups.data.FamilyState
import com.expenses.app.ui.components.SelfAvatarColor
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Holds the You tab: the person's name and whether they are signed in, whether SMS tracking and reminders are on, how
 * many accounts are tracked, the tracking start date and how many bank messages were used. Nothing is ever sent off
 * the phone, so that count is always 0.
 *
 * @param settings the person's settings.
 * @param accounts the accounts and cards found in SMS.
 * @param readSms the counts of messages read.
 * @param clock gives the time zone that turns the tracking start into a day.
 * @param auth who is signed in (`AuthRepository.state`).
 * @param family the family on this phone (`FamilyRepository.observeFamily()`), for the FAMILY row.
 */
class YouViewModel(
    private val settings: SettingsRepository,
    accounts: AccountRepository,
    readSms: ReadSmsRepository,
    private val clock: Clock,
    auth: Flow<AuthState>,
    family: Flow<FamilyInfo?> = flowOf(null),
) : ViewModel() {
    /** What the tab shows; null until the first read of the settings, the session and the database. */
    val state: StateFlow<YouUiState?> = combine(
        settings.settings,
        accounts.observeAll(),
        readSms.observeCounts(),
        auth.filter { it != AuthState.Loading },
        family,
    ) { appSettings, allAccounts, counts, session, familyInfo ->
        val signedIn = session as? AuthState.SignedIn
        YouUiState(
            // The name used on Home; saving the profile sets it too. Signed in without one, the account's name.
            profile = YouProfile(
                name = appSettings.userName ?: signedIn?.name?.takeIf { it.isNotBlank() },
                avatarColor = SelfAvatarColor,
            ),
            signedIn = signedIn != null,
            smsTrackingOn = appSettings.trackingMode == TrackingMode.SMS,
            trackedAccountCount = allAccounts.count { it.tracked },
            remindersOn = appSettings.remindersOn,
            reminderDaysBefore = REMINDER_DAYS_BEFORE,
            trackingStartDate = appSettings.trackingStartAt?.atZone(clock.zone)?.toLocalDate(),
            messagesRead = counts.used,
            messagesSent = 0,
            family = familyInfo.toYouFamily(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /**
     * Handles the switches. Turning SMS tracking on is done with [onSmsAccessGranted] when SMS access is already
     * allowed, otherwise through the Privacy promise; the rows that open other screens are handled by the caller.
     */
    fun onEvent(event: YouEvent) {
        when (event) {
            is YouEvent.SetSmsTracking -> if (!event.on) viewModelScope.launch { settings.turnSmsTrackingOff() }
            is YouEvent.SetReminders -> viewModelScope.launch { settings.setRemindersOn(event.on) }
            YouEvent.OpenAccountsAndCards, YouEvent.OpenTrackingStartDate, YouEvent.OpenWhatWeRead,
            YouEvent.OpenAccount, YouEvent.SignIn, YouEvent.OpenImport, YouEvent.OpenFamily,
            -> Unit
        }
    }

    /** Turns SMS tracking on when SMS access is already allowed. */
    fun onSmsAccessGranted() {
        viewModelScope.launch { settings.turnSmsTrackingOn(clock) }
    }

    /** What the FAMILY row says about [this] family. */
    private fun FamilyInfo?.toYouFamily(): YouFamily {
        val info = this ?: return YouFamily.None
        val partner = info.partner
        return when (info.state) {
            FamilyState.TOGETHER -> YouFamily.Together(info.name, info.me.shortName, partner?.shortName.orEmpty())
            FamilyState.WAITING -> YouFamily.Waiting(info.name)
            FamilyState.ALONE -> YouFamily.Alone(info.name)
        }
    }

    private companion object {
        /** How many days before a card bill is due the reminder goes out. */
        const val REMINDER_DAYS_BEFORE = 3

        /** How long the reads stay active after the screen stops watching, so a rotation does not restart them. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
