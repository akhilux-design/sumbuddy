package com.expenses.app.ui.screens.split

import androidx.compose.runtime.Immutable
import com.expenses.app.ui.screens.payment.amountFieldText
import com.expenses.splits.SplitMath
import com.expenses.splits.SplitMethod
import java.math.BigDecimal

/**
 * The line under the people on Split it (design 02-everyday/09, 10, 11): what goes into the person's month, or what
 * still stops the split. [isProblem] notes are drawn in `color/status/negative`; their words say the problem too.
 */
sealed interface SplitNote {
    /** True while "Split ₹450" stays off. */
    val isProblem: Boolean

    /** "Only your ₹150 share goes into your November expenses." */
    data class YourShare(val myShareMinor: Long) : SplitNote {
        override val isProblem: Boolean get() = false
    }

    /** "Amounts add up to ₹450. Only your ₹200 share goes into your November expenses." */
    data class AmountsAddUp(val totalMinor: Long, val myShareMinor: Long) : SplitNote {
        override val isProblem: Boolean get() = false
    }

    /** "₹50 is still left to assign. The parts must add up to ₹450." */
    data class LeftToAssign(val leftMinor: Long, val totalMinor: Long) : SplitNote {
        override val isProblem: Boolean get() = true
    }

    /** The typed amounts add up to more than the payment (not designed). */
    data class TooMuch(val sumMinor: Long, val totalMinor: Long) : SplitNote {
        override val isProblem: Boolean get() = true
    }

    /** Percentages below 100% (not designed); [basisPoints] are still to assign. */
    data class PercentLeft(val basisPoints: Long) : SplitNote {
        override val isProblem: Boolean get() = true
    }

    /** Percentages above 100% (not designed); [basisPoints] is their sum. */
    data class PercentTooMuch(val basisPoints: Long) : SplitNote {
        override val isProblem: Boolean get() = true
    }

    /** A percentage or a number of shares is empty or 0 (not designed). */
    data object ZeroPart : SplitNote {
        override val isProblem: Boolean get() = true
    }

    /** A typed part is not a number, or is too large (not designed). */
    data object NotRight : SplitNote {
        override val isProblem: Boolean get() = true
    }
}

/**
 * What the typed parts of a split come to.
 *
 * @param shares each member's part in paise as the rows show it (worked out as far as the typed parts allow).
 * @param inputs what `SplitMath.shares` and `GroupsRepository.splitPayment` get, or null while the split cannot be
 * made.
 * @param note the line under the people.
 */
@Immutable
internal data class SplitOutcome(val shares: Map<String, Long>, val inputs: Map<String, Long?>?, val note: SplitNote)

/** The rules of the parts typed on Split it: reading them, the parts to start from, and what they come to. */
internal object SplitParts {
    private val RupeesPattern = Regex("""\d{1,10}(\.\d{0,2})?|\.\d{1,2}""")
    private val PercentPattern = Regex("""\d{1,3}(\.\d{0,2})?|\.\d{1,2}""")
    private val SharesPattern = Regex("""\d{1,4}""")
    private val Hundred = BigDecimal(100)

    /** The longest text kept in a part's field. */
    const val MAX_TEXT = 13

    /** Keeps only what a part can hold: digits and one point (none for shares). */
    fun clean(method: SplitMethod, text: String): String {
        val kept = text.filter { it.isDigit() || (it == '.' && method != SplitMethod.SHARES) }
        val point = kept.indexOf('.')
        val onePoint = if (point < 0) kept else kept.substring(0, point + 1) + kept.substring(point + 1).replace(".", "")
        return onePoint.take(MAX_TEXT)
    }

    /** Rupees typed for Amounts, in paise: blank is 0; null when it is not an amount. */
    fun parseRupees(text: String): Long? {
        val t = text.trim().replace(",", "")
        if (t.isEmpty()) return 0L
        if (!RupeesPattern.matches(t)) return null
        return BigDecimal(t).multiply(Hundred).toLong().takeIf { it <= SplitMath.MAX_AMOUNT_MINOR }
    }

    /** A percentage typed for %, in basis points (40 is 4000): blank is 0; null when it is not a percentage. */
    fun parsePercent(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return 0L
        if (!PercentPattern.matches(t)) return null
        return BigDecimal(t).multiply(Hundred).toLong().takeIf { it <= SplitMath.PERCENT_TOTAL }
    }

    /** Shares typed for Shares: blank is 0; null when it is not a whole number up to 1,000. */
    fun parseShares(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return 0L
        if (!SharesPattern.matches(t)) return null
        return t.toLong().takeIf { it <= SplitMath.MAX_WEIGHT }
    }

    /** Basis points as the field shows them: 4000 is "40", 3334 is "33.34", 3330 is "33.3". */
    fun percentText(basisPoints: Long): String =
        BigDecimal.valueOf(basisPoints).movePointLeft(2).stripTrailingZeros().toPlainString()

    /**
     * The parts the fields start from when [method] is picked: equal parts that already add up (Amounts in rupees,
     * % in percent), or 1 share each. Nothing for Equally.
     */
    fun defaults(method: SplitMethod, amountMinor: Long, ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val equal = { total: Long -> SplitMath.shares(SplitMethod.EQUAL, total, ids.associateWith { null }) }
        return when (method) {
            SplitMethod.EQUAL -> emptyMap()
            SplitMethod.AMOUNTS -> equal(amountMinor).mapValues { amountFieldText(it.value) }
            SplitMethod.PERCENT -> equal(SplitMath.PERCENT_TOTAL).mapValues { percentText(it.value) }
            SplitMethod.SHARES -> ids.associateWith { "1" }
        }
    }

    /** A kept rule's inputs ("Do this every time") as the texts of the fields. */
    fun texts(method: SplitMethod, inputs: Map<String, Long?>): Map<String, String> = when (method) {
        SplitMethod.EQUAL -> emptyMap()
        SplitMethod.AMOUNTS -> inputs.mapValues { (_, v) -> v?.let(::amountFieldText).orEmpty() }
        SplitMethod.PERCENT -> inputs.mapValues { (_, v) -> v?.let(::percentText).orEmpty() }
        SplitMethod.SHARES -> inputs.mapValues { (_, v) -> v?.toString().orEmpty() }
    }

    /**
     * What [texts] (member id to the typed part; a missing one is blank) come to for [method] when [amountMinor] is
     * split between [ids], the person being [myId].
     */
    fun evaluate(method: SplitMethod, amountMinor: Long, ids: List<String>, myId: String, texts: Map<String, String>): SplitOutcome {
        if (ids.isEmpty()) return SplitOutcome(emptyMap(), null, SplitNote.NotRight)
        val text = { id: String -> texts[id].orEmpty() }
        return when (method) {
            SplitMethod.EQUAL -> {
                val inputs = ids.associateWith<String, Long?> { null }
                if (SplitMath.check(method, amountMinor, inputs) != null) return SplitOutcome(emptyMap(), null, SplitNote.NotRight)
                val shares = SplitMath.shares(method, amountMinor, inputs)
                SplitOutcome(shares, inputs, SplitNote.YourShare(shares[myId] ?: 0L))
            }
            SplitMethod.AMOUNTS -> {
                val parsed = ids.associateWith { parseRupees(text(it)) }
                val shown = parsed.mapValues { it.value ?: 0L }
                if (parsed.values.any { it == null }) return SplitOutcome(shown, null, SplitNote.NotRight)
                val left = SplitMath.leftToAssign(amountMinor, parsed.values)
                when {
                    left > 0 -> SplitOutcome(shown, null, SplitNote.LeftToAssign(left, amountMinor))
                    left < 0 -> SplitOutcome(shown, null, SplitNote.TooMuch(amountMinor - left, amountMinor))
                    else -> {
                        val inputs: Map<String, Long?> = shown
                        if (SplitMath.check(method, amountMinor, inputs) != null) return SplitOutcome(shown, null, SplitNote.NotRight)
                        SplitOutcome(shown, inputs, SplitNote.AmountsAddUp(amountMinor, shown[myId] ?: 0L))
                    }
                }
            }
            SplitMethod.PERCENT -> {
                val parsed = ids.associateWith { parsePercent(text(it)) }
                val points = parsed.mapValues { it.value ?: 0L }
                val shown = points.mapValues { (_, bp) -> amountMinor * bp / SplitMath.PERCENT_TOTAL }
                val sum = points.values.sum()
                when {
                    parsed.values.any { it == null } -> SplitOutcome(shown, null, SplitNote.NotRight)
                    sum < SplitMath.PERCENT_TOTAL -> SplitOutcome(shown, null, SplitNote.PercentLeft(SplitMath.PERCENT_TOTAL - sum))
                    sum > SplitMath.PERCENT_TOTAL -> SplitOutcome(shown, null, SplitNote.PercentTooMuch(sum))
                    points.values.any { it == 0L } -> SplitOutcome(shown, null, SplitNote.ZeroPart)
                    else -> made(method, amountMinor, points, myId, shown)
                }
            }
            SplitMethod.SHARES -> {
                val parsed = ids.associateWith { parseShares(text(it)) }
                val weights = parsed.mapValues { it.value ?: 0L }
                val total = weights.values.sum()
                val shown = weights.mapValues { (_, w) -> if (total == 0L) 0L else amountMinor * w / total }
                when {
                    parsed.values.any { it == null } -> SplitOutcome(shown, null, SplitNote.NotRight)
                    weights.values.any { it == 0L } -> SplitOutcome(shown, null, SplitNote.ZeroPart)
                    else -> made(method, amountMinor, weights, myId, shown)
                }
            }
        }
    }

    /** The split as SplitMath makes it from valid [values], or [NotRight][SplitNote.NotRight] when it refuses them. */
    private fun made(method: SplitMethod, amountMinor: Long, values: Map<String, Long>, myId: String, shown: Map<String, Long>): SplitOutcome {
        val inputs: Map<String, Long?> = values
        if (SplitMath.check(method, amountMinor, inputs) != null) return SplitOutcome(shown, null, SplitNote.NotRight)
        val shares = SplitMath.shares(method, amountMinor, inputs)
        return SplitOutcome(shares, inputs, SplitNote.YourShare(shares[myId] ?: 0L))
    }
}
