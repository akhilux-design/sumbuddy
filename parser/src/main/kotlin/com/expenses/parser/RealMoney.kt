package com.expenses.parser

/**
 * Points, coins, miles and store vouchers, which are not the user's money. The rule is closed (lead decision
 * 2026-11-20, round 6): a real payment lost is worse than points shown as money in, and money in is never counted as
 * spending, so a message is skipped as [SkipReason.REWARD_POINTS] only when ALL of these hold:
 *
 * 1. It reads as money in. Debits are read as before, whatever they say about points.
 * 2. No bank sent it ([SmsParser.isFromBank] is false). Everything a bank sends is read as before.
 * 3. Its payment amount is labelled as points in the words right after it ([amountIsPoints]).
 *
 * Nothing else is guessed: a store credit, a store wallet or points "worth" rupees that do not say so right after the
 * amount stay money in. The only other skip is a relabel: a message that prints no money amount and moves only points
 * was never a payment, and is skipped as points instead of being unreadable ([onlyPointsMove]).
 */
internal object RealMoney {
    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    /** What points, coins, miles and store vouchers are counted in. A closed list. */
    private const val UNIT =
        "(?:reward points|points|point|pts|coins|miles|supercoins|neucoins|fi-coins|cashpoints|timespoints|intermiles" +
            "|bluchips|gocash|supercash|cred coins|gift card|e-gift card|gift voucher|e-gift voucher|voucher|store credit" +
            "|credit note)\\b"

    /** A word that may name points. Nothing else: "gold coin", "meal voucher" or "Solana coins" are never points. */
    private const val LABEL = "(?:loyalty|rewards?|bonus|welcome|cashback|club|membership|member)\\b"

    /** One capitalised brand word ("PAYBACK", "Flipkart", "Tata"), but not a metal, a meal, a coin or a number. */
    private const val BRAND = "(?!(?:gold|silver|platinum|solana|meal|four)\\b)(?-i:[A-Z][A-Za-z]*)\\b"

    /** At most two label words, of which at most one brand word. */
    private const val LABELS = "(?:(?:$LABEL ){0,2}|$BRAND (?:$LABEL )?|$LABEL $BRAND )"

    private const val CREDITED = "(?:(?:has been|have been|is|was|are|were) )?(?:credited|added|awarded|earned|issued)\\b"

    /**
     * After a unit that comes after "credited as/in", the phrase ends: the end, a punctuation mark, a linking word or a
     * code ("credited as credit note CN4412"). "Rs 2,000 credited as gift card refund to your bank" is a refund, not a
     * voucher, and "Rs 250 credited in PAYBACK points value to your account" is money.
     */
    private const val PHRASE_ENDS = "(?=\\s*$|[.,;:!)]|\\s+(?:to|for|on|at|in|from|against|with|valid|and)\\b|\\s+[A-Za-z-]*\\d)"

    /**
     * Right after the amount: labels, a unit and a credit phrase ("Rs.200 loyalty points have been credited"), or a
     * credit phrase, "as" or "in", labels and a unit that ends the phrase ("Rs 100 credited as NeuCoins"). The unit must
     * touch the credit phrase: "Rs 300 Four Points refund credited" is not points.
     */
    private val AMOUNT_IS_POINTS =
        rx("^(?: ?/-)? $LABELS$UNIT $CREDITED|^(?: ?/-)? $CREDITED (?:as|in) $LABELS$UNIT$PHRASE_ENDS")
    private const val REACH = 80

    /** True when the payment amount of [text] (the one a payment is read with) is labelled as points. */
    fun amountIsPoints(text: String): Boolean {
        val amount = Amounts.payment(text, Amounts.all(text)) ?: return false
        return AMOUNT_IS_POINTS.containsMatchIn(text.substring(amount.end, minOf(text.length, amount.end + REACH)))
    }

    /** A count of points, never a rupee amount, nor part of a date or reference. */
    private const val COUNT = "\\b(?<![.,/:-])(?<!(?:Rs|INR)\\.?\\s?)\\d[\\d,]{0,12}(?:\\.\\d{1,2})?"
    private const val POINTS_ACTION =
        "(?:credited|earned|added|redeemed|awarded|issued|given|won|debited|deducted|used|reversed|refunded|expired" +
            "|transferred|received|accrued|converted)"

    /** A count of points with its own action word: "500 points credited", "earned 1,500 CRED coins". */
    private val POINTS_COUNT = rx(
        "$COUNT $LABELS$UNIT(?: (?:(?:have|has|had) been|are|were|is|was|got|get))?(?: (?:successfully|also|now))? " +
            "$POINTS_ACTION\\b|\\b$POINTS_ACTION (?:with |of |you )?$COUNT $LABELS$UNIT",
    )

    /** A piece of every [UNIT], so the points patterns run only on text that can hold one. */
    private val UNIT_PIECES = arrayOf(
        "point", "pts", "coins", "miles", "bluchips", "gocash", "supercash", "gift", "voucher", "store credit", "credit note",
    )

    private fun mayNamePoints(text: String): Boolean = text.lowercase().let { lower -> UNIT_PIECES.any { lower.contains(it) } }

    /** [text] with every count of points and its action word blanked ("300 reward points credited"). */
    fun withoutPointsPhrases(text: String): String = if (mayNamePoints(text)) POINTS_COUNT.replace(text, " ") else text

    /** A number with paise ("2,340.00"), which is money even when no Rs or INR is printed before it. */
    private val PAISE = Regex("\\d\\.\\d{2}\\b")

    /**
     * True when [text] prints no money amount and every debit or credit word in it belongs to a count of points ("500
     * SuperCoins credited", "1,250 Reward Points have been credited to your Credit Card"). Only for a message that was
     * not read as a payment. A number with paise left once the points are taken out is money without its Rs ("Salary
     * 52,000.00 and 100 bonus points credited"), so that message stays for Needs review.
     */
    fun onlyPointsMove(text: String): Boolean {
        if (!mayNamePoints(text) || Amounts.spans(text).any() || !POINTS_COUNT.containsMatchIn(text)) return false
        val rest = POINTS_COUNT.replace(text, " ")
        return Classifier.direction(rest) == null && !PAISE.containsMatchIn(rest)
    }

    /** A DLT header code that reads as a bank's: "COOPBK", "DBSBNK", "MAHABK", "XYZSFB", "FINOPB", "...BANK...". */
    private val BANK_HEADER = Regex("(?:BK|BNK|SFB|PB)$|BANK")
    /** The message signs off as a bank: "-DBS Bank", "-Equitas SFB", "- Saraswat Co-op Bank Ltd". */
    private val SIGN_OFF = rx("(?:^|\\s)-\\s?([A-Za-z][A-Za-z&'. -]{1,40}?)\\.?\\s*$")
    private val BANK_WORD = rx("\\bbank\\b|\\bsfb\\b")

    /**
     * True when a sender that is not in [Banks] is a bank all the same: its header code reads as a bank's, or the
     * message signs off with a bank's name ("-Equitas SFB", "-Bank of Maharashtra"). [text] has its safety footers
     * removed.
     */
    fun isUnlistedBank(sender: String, text: String): Boolean {
        if (BANK_HEADER.containsMatchIn(Banks.headerCode(sender))) return true
        val signOff = SIGN_OFF.find(text) ?: return false
        return BANK_WORD.containsMatchIn(signOff.groupValues[1])
    }
}
