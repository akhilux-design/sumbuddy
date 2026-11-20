package com.expenses.splitwise

/** A money cell read by [Money.parse]. */
internal sealed interface MoneyCell {
    /** A whole number of paise. */
    data class Paise(val value: Long) : MoneyCell

    /** Not an amount: a symbol, three decimals, a decimal comma, letters, a blank cell. */
    data object Unreadable : MoneyCell

    /** An amount with more than [Money.MAX_WHOLE_DIGITS] digits before the point: far over ₹1 crore. */
    data object TooLarge : MoneyCell
}

/**
 * Money cells to paise (docs/splitwise-import.md 1.2), with integer string arithmetic only, never floating point:
 * an optional sign, digits and at most 2 decimals (`98.5` is 9,850 paise, `-0.00` is 0). Grouping commas are allowed
 * as a spreadsheet writes them, Indian (`1,23,456.50`) or Western (`123,456.50`) only, so `12,34` (a decimal comma)
 * is not read as 1,234.
 */
internal object Money {
    /** Digits before the point that still fit a [Long] of paise with room for summing 51 of them. */
    const val MAX_WHOLE_DIGITS: Int = 15

    private val PLAIN = Regex("""^([+-]?)(\d+)(?:\.(\d{1,2}))?$""")
    private val GROUPED = Regex("""^([+-]?)(\d{1,3}(?:,\d{3})+|\d{1,2}(?:,\d{2})*,\d{3})(?:\.(\d{1,2}))?$""")

    fun parse(cell: String): MoneyCell {
        if (cell.length > 64) return MoneyCell.Unreadable
        val match = PLAIN.matchEntire(cell) ?: GROUPED.matchEntire(cell) ?: return MoneyCell.Unreadable
        val (sign, wholeText, fraction) = match.destructured
        val whole = wholeText.replace(",", "").trimStart('0')
        if (whole.length > MAX_WHOLE_DIGITS) return MoneyCell.TooLarge
        val rupees = if (whole.isEmpty()) 0L else whole.toLong()
        val paise = rupees * 100 + fraction.padEnd(2, '0').toLong()
        return MoneyCell.Paise(if (sign == "-") -paise else paise)
    }
}
