package com.expenses.parser

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** A date (and time, when printed) found in the text. */
internal data class FoundDate(val dateTime: LocalDateTime, val start: Int, val end: Int)

/**
 * Reads the dates Indian banks print: 16/11/26, 14-11-2026, 16.11.2026, 13-NOV-26, 16Nov26, 9 November 2026,
 * Nov 15, 2026 and 2026-11-15:19:42:07. A date needs a year: "16 Nov, 10:45" gives no date.
 */
internal object Dates {
    private const val MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
    private val MONTH_NAMES = MONTHS.split('|')

    private val YEAR_FIRST = Regex("(?<![\\d])(\\d{4})[-/:](\\d{2})[-/:](\\d{2})(?![\\d])")
    /** Day, month, year with one separator used twice ("16-11-26", "16/11/2026", "16.11.2026"). */
    private val NUMERIC = Regex("(?<![\\d.:/-])(\\d{1,2})([-/.])(\\d{1,2})\\2(\\d{4}|\\d{2})(?![\\d])(?!\\.\\d)")
    /** A two-digit year must not be the hour of a following time ("16 Nov, 10:45"). */
    private val NAMED = Regex(
        "(?<![\\dA-Za-z])(\\d{1,2})[-/ ]?($MONTHS)[a-z]*\\.?[-/ ,]{0,3}(\\d{4}(?![\\d])|\\d{2}(?![\\d:]))",
        RegexOption.IGNORE_CASE,
    )
    /** Month first, with a four-digit year: "Nov 15, 2026", "November 5 2026". */
    private val MONTH_FIRST = Regex(
        "(?<![A-Za-z])($MONTHS)[a-z]*\\.?\\s(\\d{1,2})(?:st|nd|rd|th)?,?\\s(\\d{4})(?![\\d])",
        RegexOption.IGNORE_CASE,
    )
    private val TIME = Regex(
        "(?:\\s{0,2},?\\s{0,2}(?:at\\s{1,2})?|:)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:\\s?([AaPp])\\.?[Mm]\\b\\.?)?",
    )

    /** The first valid date in the text, with the time that follows it when there is one. */
    fun first(text: String): FoundDate? = all(text).minByOrNull { it.start }

    /** The first of [dates] (sorted by start, as [all] returns them) that starts at or after [from]. */
    fun firstAfter(dates: List<FoundDate>, from: Int): FoundDate? {
        var low = 0
        var high = dates.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (dates[mid].start < from) low = mid + 1 else high = mid
        }
        return dates.getOrNull(low)
    }

    fun toMillis(dateTime: LocalDateTime, zone: ZoneId): Long = dateTime.atZone(zone).toInstant().toEpochMilli()

    fun midnightMillis(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Every valid date in the text, sorted by where it starts. */
    fun all(text: String): List<FoundDate> {
        val found = ArrayList<FoundDate>()
        for (m in YEAR_FIRST.findAll(text)) {
            val (y, mo, d) = m.destructured
            add(found, text, m, date(y.toInt(), mo.toInt(), d.toInt()))
        }
        for (m in NUMERIC.findAll(text)) {
            val (d, _, mo, y) = m.destructured
            add(found, text, m, date(year(y), mo.toInt(), d.toInt()))
        }
        val lower = text.lowercase()
        val namesMonth = MONTH_NAMES.any { lower.contains(it) }
        if (namesMonth) for (m in NAMED.findAll(text)) {
            val (d, mon, y) = m.destructured
            val month = MONTH_NAMES.indexOf(mon.lowercase()) + 1
            add(found, text, m, date(year(y), month, d.toInt()))
        }
        if (namesMonth) for (m in MONTH_FIRST.findAll(text)) {
            val (mon, d, y) = m.destructured
            val month = MONTH_NAMES.indexOf(mon.lowercase()) + 1
            add(found, text, m, date(y.toInt(), month, d.toInt()))
        }
        found.sortBy { it.start }
        return found
    }

    private fun add(found: MutableList<FoundDate>, text: String, match: MatchResult, date: LocalDate?) {
        if (date == null) return
        val end = match.range.last + 1
        val time = TIME.matchAt(text, end)?.let { t -> time(t)?.let { it to (t.range.last + 1) } }
        found += FoundDate(LocalDateTime.of(date, time?.first ?: LocalTime.MIDNIGHT), match.range.first, time?.second ?: end)
    }

    private fun year(text: String): Int = text.toInt().let { if (text.length == 2) 2000 + it else it }

    private fun date(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun time(m: MatchResult): LocalTime? {
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val second = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val half = m.groupValues[4].lowercase()
        if (half == "p" && hour in 1..11) hour += 12
        if (half == "a" && hour == 12) hour = 0
        return runCatching { LocalTime.of(hour, minute, second) }.getOrNull()
    }
}
