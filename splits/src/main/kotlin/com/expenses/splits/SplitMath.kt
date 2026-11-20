package com.expenses.splits

/**
 * How a group expense is divided between its people. The names are the server's (`splitMethod` in the API) and are
 * stored by name on the phone: never rename a constant.
 */
enum class SplitMethod {
    /** Everyone pays the same; the paise that do not divide go one each to the first people by member id. */
    EQUAL,

    /** Each person's amount is typed; the amounts add up to the total. */
    AMOUNTS,

    /** Each person's percentage, in basis points (100 = 1%), adding up to 10,000. */
    PERCENT,

    /** Each person's weight (1 to 1,000), for example 2 shares for a couple. */
    SHARES,
}

/**
 * The shares of a group expense from its split method (docs/server.md 6.1). The server runs the same rules
 * (`server/src/domain/splits.ts`); both are checked against `shared/vectors/splits.json`, so the phone and the server
 * always agree to the paisa. All values are whole paise.
 */
object SplitMath {
    /** The largest amount of one expense: ₹1 crore in paise. */
    const val MAX_AMOUNT_MINOR: Long = 1_000_000_000L

    /** The most people one expense can be split between. */
    const val MAX_PARTICIPANTS: Int = 50

    /** Basis points of 100%. */
    const val PERCENT_TOTAL: Long = 10_000L

    /** The largest weight one person can have with [SplitMethod.SHARES]. */
    const val MAX_WEIGHT: Long = 1_000L

    /**
     * Each participant's share of [amountMinor], ordered by member id (plain string order, as the server sorts).
     *
     * @param method how the expense is divided.
     * @param amountMinor the whole expense in paise, 1 to [MAX_AMOUNT_MINOR].
     * @param inputs one entry per participant, keyed by member id: null for [SplitMethod.EQUAL], the share in paise
     * for [SplitMethod.AMOUNTS], basis points for [SplitMethod.PERCENT], the weight for [SplitMethod.SHARES].
     * @return member id to share in paise; the shares add up to [amountMinor].
     * @throws IllegalArgumentException when the inputs do not fit the method (see [check]).
     */
    fun shares(method: SplitMethod, amountMinor: Long, inputs: Map<String, Long?>): Map<String, Long> {
        check(method, amountMinor, inputs)?.let { throw IllegalArgumentException(it.message) }
        val ids = inputs.keys.sorted()
        val result = LinkedHashMap<String, Long>(ids.size)
        when (method) {
            SplitMethod.EQUAL -> {
                val n = ids.size.toLong()
                val base = amountMinor / n
                val extra = amountMinor % n
                ids.forEachIndexed { index, id -> result[id] = base + if (index < extra) 1 else 0 }
            }
            SplitMethod.AMOUNTS -> ids.forEach { id -> result[id] = inputs.getValue(id)!! }
            SplitMethod.PERCENT, SplitMethod.SHARES -> {
                val total = if (method == SplitMethod.PERCENT) PERCENT_TOTAL else ids.sumOf { inputs.getValue(it)!! }
                val raw = ids.associateWith { amountMinor * inputs.getValue(it)!! }
                ids.forEach { id -> result[id] = raw.getValue(id) / total }
                var left = amountMinor - result.values.sum()
                // The paise left over go one each to the largest remainders; equal remainders go to the lower id.
                val byRemainder = ids.sortedWith(
                    compareByDescending<String> { raw.getValue(it) % total }.thenBy { it },
                )
                for (id in byRemainder) {
                    if (left == 0L) break
                    result[id] = result.getValue(id) + 1
                    left--
                }
            }
        }
        return result
    }

    /**
     * What is wrong with these inputs for [method], or null when [shares] can use them. The same rules as the
     * server's validation (docs/server.md 4.7), so the phone never sends what the server would refuse.
     */
    fun check(method: SplitMethod, amountMinor: Long, inputs: Map<String, Long?>): SplitProblem? {
        if (amountMinor !in 1..MAX_AMOUNT_MINOR) return SplitProblem.AMOUNT_OUT_OF_RANGE
        if (inputs.isEmpty()) return SplitProblem.NO_PARTICIPANTS
        if (inputs.size > MAX_PARTICIPANTS) return SplitProblem.TOO_MANY_PARTICIPANTS
        val values = inputs.values
        return when (method) {
            SplitMethod.EQUAL -> if (values.any { it != null }) SplitProblem.INPUT_NOT_EXPECTED else null
            SplitMethod.AMOUNTS -> when {
                values.any { it == null || it < 0 } -> SplitProblem.INPUT_OUT_OF_RANGE
                values.sumOf { it!! } != amountMinor -> SplitProblem.AMOUNTS_DO_NOT_ADD_UP
                else -> null
            }
            SplitMethod.PERCENT -> when {
                values.any { it == null || it !in 1..PERCENT_TOTAL } -> SplitProblem.INPUT_OUT_OF_RANGE
                values.sumOf { it!! } != PERCENT_TOTAL -> SplitProblem.PERCENTS_DO_NOT_ADD_UP
                else -> null
            }
            SplitMethod.SHARES -> if (values.any { it == null || it !in 1..MAX_WEIGHT }) SplitProblem.INPUT_OUT_OF_RANGE else null
        }
    }

    /**
     * For [SplitMethod.AMOUNTS] while the person types: the paise still to assign ("₹50 is still left to assign"),
     * negative when the typed amounts are more than the total. Blank fields count as 0.
     */
    fun leftToAssign(amountMinor: Long, typed: Collection<Long?>): Long = amountMinor - typed.sumOf { it ?: 0L }
}

/** Why a split cannot be made. */
enum class SplitProblem(val message: String) {
    /** The amount is not between ₹0.01 and ₹1 crore. */
    AMOUNT_OUT_OF_RANGE("amount out of range"),

    /** Nobody to split with. */
    NO_PARTICIPANTS("no participants"),

    /** More than 50 people. */
    TOO_MANY_PARTICIPANTS("too many participants"),

    /** An input was given for an equal split. */
    INPUT_NOT_EXPECTED("equal splits take no input"),

    /** An input is missing, negative or above its limit. */
    INPUT_OUT_OF_RANGE("input out of range"),

    /** Typed amounts do not add up to the total. */
    AMOUNTS_DO_NOT_ADD_UP("amounts do not add up"),

    /** Percentages do not add up to 100%. */
    PERCENTS_DO_NOT_ADD_UP("percentages do not add up"),
}
