package com.expenses.app.ui.screens.you

import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.data.settings.TrackingMode
import java.time.Clock

/**
 * Switches reading bank SMS on, after the person allowed SMS access (from the Home tip or the switch on You).
 * Tracking starts now: the tracking start and the last scan both move to now, so the screens never claim that the
 * time tracking was off was read. Payments from that time can be read in with "Add older payments" on Tracking start
 * date, which reads only messages not read before.
 *
 * @param clock gives "now".
 */
internal suspend fun SettingsRepository.turnSmsTrackingOn(clock: Clock) {
    val now = clock.instant()
    setTrackingStart(now)
    setLastScan(now)
    setTrackingMode(TrackingMode.SMS)
}

/** Switches reading bank SMS off: expenses are then added by hand, and everything read so far stays. */
internal suspend fun SettingsRepository.turnSmsTrackingOff() {
    setTrackingMode(TrackingMode.MANUAL)
}
