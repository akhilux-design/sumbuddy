package com.expenses.splitwise

import java.time.DateTimeException
import java.time.LocalDate

/**
 * Dates of an export (docs/splitwise-import.md 1.2). Splitwise writes `YYYY-MM-DD`; a time after `T` or a space is
 * ignored. A file re-saved in a spreadsheet may hold `D/M/YYYY` or `M/D/YYYY` instead (also with `-` or `.`, as an
 * Indian or European spreadsheet writes them, and with a 2-digit year meaning 20yy). Their order is decided once per
 * file by [orderOf].
 */
internal object Dates {
    /** Day and month order of the dates that do not start with the year. */
    enum class Order { DAY_FIRST, MONTH_FIRST }

    private val YEAR_FIRST = Regex("""^(\d{4})([-/.])(\d{1,2})\2(\d{1,2})$""")
    private val YEAR_LAST = Regex("""^(\d{1,2})([-/.])(\d{1,2})\2(\d{4}|\d{2})$""")

    /** The date part of a cell: what comes before a time (`2025-05-17T10:00:00Z`, `5/17/2025 0:00`). */
    fun token(cell: String): String = cell.substringBefore(' ').substringBefore('T')

    /**
     * The file's day and month order, from every date seen ([add]): day-first when any first number is above 12;
     * otherwise month-first when any second number is above 12; otherwise day-first (India). Dates that start with
     * the year give no evidence.
     */
    class OrderEvidence {
        private var firstAbove12 = false
        private var secondAbove12 = false

        fun add(token: String) {
            val match = YEAR_LAST.matchEntire(token) ?: return
            if (match.groupValues[1].toInt() > 12) firstAbove12 = true
            if (match.groupValues[3].toInt() > 12) secondAbove12 = true
        }

        val order: Order
            get() = when {
                firstAbove12 -> Order.DAY_FIRST
                secondAbove12 -> Order.MONTH_FIRST
                else -> Order.DAY_FIRST
            }
    }

    /** The date of [token] read with [order], or null when it is not a date (`2025-02-30`, `17 May`, blank). */
    fun parse(token: String, order: Order): LocalDate? {
        YEAR_FIRST.matchEntire(token)?.let { match ->
            val (year, _, month, day) = match.destructured
            return dateOf(year.toInt(), month.toInt(), day.toInt())
        }
        val match = YEAR_LAST.matchEntire(token) ?: return null
        val (first, _, second, yearText) = match.destructured
        val year = if (yearText.length == 2) 2000 + yearText.toInt() else yearText.toInt()
        return when (order) {
            Order.DAY_FIRST -> dateOf(year, second.toInt(), first.toInt())
            Order.MONTH_FIRST -> dateOf(year, first.toInt(), second.toInt())
        }
    }

    private fun dateOf(year: Int, month: Int, day: Int): LocalDate? =
        try {
            LocalDate.of(year, month, day)
        } catch (e: DateTimeException) {
            null
        }
}
