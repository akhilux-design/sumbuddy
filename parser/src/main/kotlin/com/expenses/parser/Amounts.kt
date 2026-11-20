package com.expenses.parser

import java.math.BigDecimal
import java.util.Currency

/** What an amount in the text stands for, judged from the words that govern it. */
internal enum class AmountKind { PLAIN, BALANCE, LIMIT }

internal data class Amount(
    val minor: Long,
    val currency: String,
    val kind: AmountKind,
    val start: Int,
    val end: Int,
)

/** Reads money amounts ("Rs.1,23,456.78", "INR450", "USD 12.99", "Rs:900.00", "Rs.15,000/-", "OMR 4.125"). */
internal object Amounts {
    /** Currencies Indian cards are commonly used in abroad. Matched in upper case only, so words such as "try" never count. */
    private val FOREIGN = setOf(
        "USD", "EUR", "GBP", "AED", "SGD", "JPY", "AUD", "CAD", "CHF", "THB", "MYR", "HKD", "SAR", "QAR",
        "NZD", "CNY", "OMR", "KWD", "BHD", "LKR", "NPR", "IDR", "ZAR", "SEK", "KRW", "VND", "MVR", "TRY",
        "EGP", "PHP", "DKK", "NOK", "PLN", "CZK", "HUF", "ILS", "KES", "MUR", "BDT", "PKR", "TWD", "MXN",
        "BRL", "RUB", "KZT", "UZS", "GEL", "AZN", "JOD", "MAD", "TZS", "UGX", "ARS", "CLP", "COP", "PEN",
        "ISK", "RON", "BGN", "MMK", "KHR", "LAK", "BTN", "FJD", "SCR", "MOP",
    )

    /**
     * A number: up to 16 digits and commas, up to 3 decimals, and nothing numeric straight after it. A longer number
     * is not cut short and accepted; it simply does not match, so the message becomes Unreadable.
     */
    const val NUMBER = "(\\d[\\d,]{0,15}(?:\\.\\d{1,3})?)(?![\\d])(?!,\\d)(?!\\.\\d)"

    /**
     * A currency token: rupee forms in any case, or a code from [FOREIGN] in capitals. The codes are checked by a
     * fixed-width lookbehind after three capitals, which is much cheaper than trying every code at every position.
     */
    val TOKEN = "(?:Rs|INR|Rupees|(?-i:[A-Z]{3})(?<=(?-i:${FOREIGN.joinToString("|")})))"

    private val CURRENCY_AMOUNT = Regex(
        "(?<![A-Za-z])($TOKEN)\\.?\\s?:?\\s?$NUMBER",
        RegexOption.IGNORE_CASE,
    )

    /** Some banks (SBI UPI) print the amount with no currency: "debited by 320.0". */
    private val BARE_AMOUNT = Regex(
        "\\b(?:debited|credited)\\s+(?:by|with|for)\\s+(\\d[\\d,]{0,15}(?:\\.\\d{1,2})?)(?![\\d/-])(?!\\.\\d)",
        RegexOption.IGNORE_CASE,
    )

    private val KEYWORDS = Regex(
        "(\\blimits?\\b|\\blmt\\b)|(\\bbal\\b|balance|avlbal|शेष)",
        RegexOption.IGNORE_CASE,
    )
    /** An action between a balance word and the amount means the amount is the payment ("balance has been debited by Rs 299"). */
    private val ACTION = Regex(
        "\\b(?:debited|credited|spent|paid|deducted|withdrawn|sent|received|transferred|used|charged|refunded|reversed|added)\\b",
        RegexOption.IGNORE_CASE,
    )
    /** A sentence end followed by a new sentence ("... @ybl. Available balance ..."), but not "Bal. Rs". */
    private val SENTENCE_START = Regex("[.!?;]\\s+(?!(?:Rs|INR)\\b)(?=[A-Z][a-z]{2,})")
    private const val CONTEXT = 60

    /** Every currency amount in the text, in order, each tagged as plain, balance or limit. */
    fun all(text: String): List<Amount> {
        val result = ArrayList<Amount>()
        var previousEnd = 0
        for (match in CURRENCY_AMOUNT.findAll(text)) {
            val currency = currencyCode(match.groupValues[1])
            val minor = toMinor(match.groupValues[2], currency) ?: continue
            val start = match.range.first
            val end = match.range.last + 1
            result += Amount(minor, currency, kindOf(text, maxOf(previousEnd, start - CONTEXT), start), start, end)
            previousEnd = end
        }
        return result
    }

    /** Where each currency amount is printed, without reading its value or kind. */
    fun spans(text: String): Sequence<IntRange> = CURRENCY_AMOUNT.findAll(text).map { it.range }

    /** The kind from the nearest limit or balance word in the same line and sentence, unless an action word follows it. */
    private fun kindOf(text: String, from: Int, start: Int): AmountKind {
        var windowStart = start
        while (windowStart > from && text[windowStart - 1] != '\n') windowStart--
        var window = text.substring(windowStart, start)
        SENTENCE_START.findAll(window).lastOrNull()?.let { window = window.substring(it.range.last + 1) }
        val keyword = KEYWORDS.findAll(window).lastOrNull() ?: return AmountKind.PLAIN
        if (ACTION.containsMatchIn(window.substring(keyword.range.last + 1))) return AmountKind.PLAIN
        return if (keyword.groups[1] != null) AmountKind.LIMIT else AmountKind.BALANCE
    }

    /**
     * The payment amount: the first plain amount, preferring a rupee amount when a foreign one is printed with it.
     * Falls back to a bare number after "debited by" when no currency is printed.
     */
    fun payment(text: String, amounts: List<Amount>): Amount? {
        val plain = amounts.filter { it.kind == AmountKind.PLAIN && it.minor > 0 }
        val chosen = plain.firstOrNull { it.currency == "INR" }?.takeIf { plain.first().currency != "INR" }
            ?: plain.firstOrNull()
        if (chosen != null) return chosen
        val bare = BARE_AMOUNT.find(text) ?: return null
        val minor = toMinor(bare.groupValues[1], "INR") ?: return null
        if (minor <= 0) return null
        val group = bare.groups[1]!!.range
        return Amount(minor, "INR", AmountKind.PLAIN, group.first, group.last + 1)
    }

    fun balance(amounts: List<Amount>): Long? = amounts.firstOrNull { it.kind == AmountKind.BALANCE }?.minor

    private fun currencyCode(token: String): String {
        val upper = token.uppercase()
        return if (upper == "RS" || upper == "INR" || upper == "RUPEES") "INR" else upper
    }

    /**
     * "1,23,456.78" in INR gives 12345678. Minor units follow ISO 4217 (JPY 0, OMR 3). Null when the number cannot
     * be read, or when it has more decimals than the currency allows and they are not zero ("Rs 4.125").
     */
    fun toMinor(number: String, currency: String): Long? {
        val cleaned = number.trimEnd(',').replace(",", "")
        if (cleaned.isEmpty()) return null
        val digits = runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2)
            .coerceAtLeast(0)
        return runCatching { BigDecimal(cleaned).movePointRight(digits).setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact() }
            .getOrNull()
    }
}
