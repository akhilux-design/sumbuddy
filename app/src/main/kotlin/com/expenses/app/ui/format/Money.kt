package com.expenses.app.ui.format

import kotlin.math.abs

/**
 * Formats an amount held in paise as rupees with Indian digit grouping: 1824000 becomes "₹18,240",
 * 12345678 becomes "₹1,23,456.78". Paise are shown only when they are not zero, unless [alwaysShowPaise] is set.
 */
fun formatRupees(amountMinor: Long, alwaysShowPaise: Boolean = false): String {
    val negative = amountMinor < 0
    val value = abs(amountMinor)
    val rupees = value / 100
    val paise = (value % 100).toInt()
    val sb = StringBuilder()
    if (negative) sb.append('-')
    sb.append('₹').append(groupIndian(rupees))
    if (paise != 0 || alwaysShowPaise) sb.append('.').append(paise.toString().padStart(2, '0'))
    return sb.toString()
}

/** Formats a count with Indian digit grouping, the same as amounts: 1902 becomes "1,902", 123456 "1,23,456". */
fun formatCount(count: Int): String = (if (count < 0) "-" else "") + groupIndian(abs(count.toLong()))

/** Indian grouping: the last three digits, then groups of two (12,34,567). */
internal fun groupIndian(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val head = s.dropLast(3)
    val tail = s.takeLast(3)
    val groups = ArrayList<String>()
    var i = head.length
    while (i > 0) {
        groups.add(head.substring(maxOf(0, i - 2), i))
        i -= 2
    }
    return groups.reversed().joinToString(",") + "," + tail
}
