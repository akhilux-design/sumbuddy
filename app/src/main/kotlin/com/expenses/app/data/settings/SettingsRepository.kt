package com.expenses.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How the person tracks expenses. */
enum class TrackingMode {
    /** Payments are read from bank SMS. */
    SMS,

    /** SMS access was not given; expenses are typed in by hand. */
    MANUAL,
}

/**
 * The person's settings, all kept on this phone.
 *
 * @param userName the name they gave on Your name, or null before that step.
 * @param onboardingDone true once onboarding finished; the app then opens on Home.
 * @param trackingMode how they track expenses, or null before onboarding decided it.
 * @param trackingStartAt SMS received before this moment are never read, or null before it was chosen.
 * "Start fresh" stores the moment it was chosen; "This month" the start of the month's first day.
 * @param lastScanAt when the inbox was last read up to, or null before the first scan.
 * @param themeFollowsSystem true while the theme follows the phone's dark mode setting.
 * @param remindersOn true while the person wants card bill reminders. Only stored in this version.
 * @param smsAskedBefore true once the person said no to Android's SMS dialog at least once, so the app can tell
 * when Android will no longer show it.
 */
data class AppSettings(
    val userName: String? = null,
    val onboardingDone: Boolean = false,
    val trackingMode: TrackingMode? = null,
    val trackingStartAt: Instant? = null,
    val lastScanAt: Instant? = null,
    val themeFollowsSystem: Boolean = true,
    val remindersOn: Boolean = true,
    val smsAskedBefore: Boolean = false,
)

/** Reads and writes [AppSettings] in a Preferences DataStore. Writes are main-safe. */
class SettingsRepository(private val store: DataStore<Preferences>) {
    /** The current settings; emits again on every change. */
    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            userName = prefs[USER_NAME],
            onboardingDone = prefs[ONBOARDING_DONE] ?: false,
            trackingMode = prefs[TRACKING_MODE]?.let { name -> TrackingMode.entries.firstOrNull { it.name == name } },
            trackingStartAt = prefs[TRACKING_START_AT]?.let(Instant::ofEpochMilli),
            lastScanAt = prefs[LAST_SCAN_AT]?.let(Instant::ofEpochMilli),
            themeFollowsSystem = prefs[THEME_FOLLOWS_SYSTEM] ?: true,
            remindersOn = prefs[REMINDERS_ON] ?: true,
            smsAskedBefore = prefs[SMS_ASKED_BEFORE] ?: false,
        )
    }.distinctUntilChanged()

    /** Stores the person's name, trimmed; blank removes it. */
    suspend fun setUserName(name: String) {
        val trimmed = name.trim()
        store.edit { if (trimmed.isEmpty()) it.remove(USER_NAME) else it[USER_NAME] = trimmed }
    }

    /** Stores how the person tracks expenses. */
    suspend fun setTrackingMode(mode: TrackingMode) {
        store.edit { it[TRACKING_MODE] = mode.name }
    }

    /** Stores the moment from which SMS are read. */
    suspend fun setTrackingStart(at: Instant) {
        store.edit { it[TRACKING_START_AT] = at.toEpochMilli() }
    }

    /** Stores when the inbox was last read up to. */
    suspend fun setLastScan(at: Instant) {
        store.edit { it[LAST_SCAN_AT] = at.toEpochMilli() }
    }

    /** Stores whether the theme follows the phone's setting. */
    suspend fun setThemeFollowsSystem(follows: Boolean) {
        store.edit { it[THEME_FOLLOWS_SYSTEM] = follows }
    }

    /** Stores whether the person wants card bill reminders. */
    suspend fun setRemindersOn(on: Boolean) {
        store.edit { it[REMINDERS_ON] = on }
    }

    /** Remembers that the person said no to Android's SMS dialog. */
    suspend fun markSmsAsked() {
        store.edit { it[SMS_ASKED_BEFORE] = true }
    }

    /** Marks onboarding finished, so the app opens on Home from now on. */
    suspend fun completeOnboarding() {
        store.edit { it[ONBOARDING_DONE] = true }
    }

    /**
     * The parser rules version whose clean-up of stored SMS rows has run ([com.expenses.app.sms.ParserRulesCleanup]),
     * or 0 before the first one.
     */
    suspend fun cleanedParserRules(): Int = store.data.first()[PARSER_RULES_CLEANED] ?: 0

    /** Remembers that the clean-up for parser rules [version] has run. */
    suspend fun setCleanedParserRules(version: Int) {
        store.edit { it[PARSER_RULES_CLEANED] = version }
    }

    private companion object {
        val USER_NAME = stringPreferencesKey("user_name")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val TRACKING_MODE = stringPreferencesKey("tracking_mode")
        val TRACKING_START_AT = longPreferencesKey("tracking_start_at")
        val LAST_SCAN_AT = longPreferencesKey("last_scan_at")
        val THEME_FOLLOWS_SYSTEM = booleanPreferencesKey("theme_follows_system")
        val REMINDERS_ON = booleanPreferencesKey("reminders_on")
        val SMS_ASKED_BEFORE = booleanPreferencesKey("sms_asked_before")
        val PARSER_RULES_CLEANED = intPreferencesKey("parser_rules_cleaned")
    }
}
