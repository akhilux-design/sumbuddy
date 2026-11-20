package com.expenses.app.ui.time

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * "Now" from [clock]: once at once, then at the start of every minute for as long as it is collected. Screens that
 * show the current month, today's date or "5 min ago" build on it, so they stay right while they stay open, across
 * midnight and the end of a month.
 */
fun minuteTicks(clock: Clock): Flow<Instant> = flow {
    while (true) {
        val now = clock.instant()
        emit(now)
        val nextMinute = now.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
        delay(Duration.between(now, nextMinute).toMillis().coerceAtLeast(1))
    }
}

/** The month in [clock]'s time zone of each tick, emitted again only when it changes. */
fun Flow<Instant>.months(clock: Clock): Flow<YearMonth> =
    map { YearMonth.from(it.atZone(clock.zone)) }.distinctUntilChanged()
