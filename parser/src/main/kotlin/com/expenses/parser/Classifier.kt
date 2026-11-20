package com.expenses.parser

/** Sender check, text normalisation, early skips and the direction of money. */
internal object Classifier {
    private val BUSINESS_SENDER = Regex("^(?:[A-Za-z]{2}-)?(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{3,9}(?:-[STPGstpg])?$")

    /** True for DLT business headers such as "VM-HDFCBK" or "JD-SBIINB-S"; false for phone numbers and short codes. */
    fun isBusinessSender(sender: String): Boolean = BUSINESS_SENDER.matches(sender.trim())

    /** Invisible format characters (zero-width space and joiners, BOM, soft hyphen). */
    private val FORMAT_CHARS = Regex("\\p{Cf}+")
    private val LINE_BREAKS = Regex("\\r\\n|[\\r\\u0085\\u2028\\u2029]")
    /** Every Unicode space separator (thin, hair, figure, ideographic, no-break...) plus tab, VT and FF. */
    private val SPACES = Regex("[\\t\\u000B\\f\\p{Zs}]+")
    private val LINE_RUNS = Regex(" ?\\n[\\n ]*")
    /** The Hindi rupee abbreviation, only when an ASCII amount follows ("रु.500"), so words such as "रुपये" stay intact. */
    private val HINDI_RUPEE = Regex("(?:रु|रू)\\.?\\s?(?=\\d)")

    /** One line per original line (blank lines dropped), single spaces, the rupee sign written as "Rs". */
    fun normalise(body: String): String =
        body.replace(FORMAT_CHARS, "")
            .replace(LINE_BREAKS, "\n")
            .replace("₹", " Rs ")
            .replace("₨", " Rs ")
            .let { if (it.contains('र')) it.replace(HINDI_RUPEE, " Rs ") else it }
            .replace(SPACES, " ")
            .replace(LINE_RUNS, "\n")
            .trim()

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    /** An amount as printed, used to tie an action word to money. */
    private val AMT = "(?<![A-Za-z])${Amounts.TOKEN}\\.?\\s?:?\\s?\\d[\\d,]{0,15}(?:\\.\\d{1,3})?(?:/-)?"

    /**
     * Safety footers ("Never share your OTP with anyone", "Do not disclose ...") are removed before the skip checks,
     * so a footer word cannot turn a real payment into an OTP or promotion.
     */
    private val SAFETY_FOOTER = rx(
        "\\b(?:never|do\\s+not|don['’]?t|dont|pls\\s+do\\s+not|please\\s+do\\s+not)\\s+(?:ever\\s+)?" +
            "(?:share|disclose|reveal|tell|give|forward)\\b[^.!\\n]{0,120}",
    )

    fun withoutSafetyFooters(text: String): String = text.replace(SAFETY_FOOTER, " ")

    private const val CODE_WORD =
        "(?:otp|one[ -]?time[ -]?pass(?:word|code)|(?:verification|authentication|auth|secure|security|3d\\s?secure|confirmation)\\s+code|passcode)"

    private val OTP = rx(
        "\\b$CODE_WORD\\b\\s?(?:\\(\\s?otp\\s?\\)\\s?)?(?:is|:|-|for|of)(?![a-z])" +
            "|\\b\\d{4,8}\\s(?:is\\s)?(?:the\\s|your\\s){0,2}$CODE_WORD\\b" +
            "|\\botp\\s?[-:]?\\s?(?:is\\s?)?\\d{4,8}\\b" +
            "|\\b$CODE_WORD\\b[^.\\n]{0,30}\\bis\\s?:?\\s?\\d{4,8}\\b" +
            "|\\bis\\s+your\\s+$CODE_WORD\\b",
    )

    private val PROMOTION = rx(
        "\\bpre-?\\s?approved\\b|\\bT\\s?&\\s?C\\b|\\boffers?\\b|\\bapply now\\b|\\buse code\\b|\\bflat\\b.{0,20}\\boff\\b" +
            "|\\b\\d{1,2}\\s?%\\s?(?:instant\\s)?(?:off|discount|cashback)\\b|\\bconvert\\b.{0,80}\\bemis?\\b" +
            "|\\b(?:get|win|earn)\\b.{0,25}\\b(?:cashback|rewards?|prizes?|scratch card)\\b|\\bmissed call\\b|\\bcoupon\\b" +
            "|\\b(?:instant|personal|pre-?approved|quick)\\s+(?:personal\\s+)?loan\\b|\\bloan\\s+of\\s+up\\s?to\\b" +
            "|\\brefer(?:\\s+a)?\\s+friends?\\b|\\breferral\\b|\\bhurry\\b|\\blimited period\\b|\\bknow more\\b|\\bclick here\\b",
    )

    private val FAILED = rx(
        "(?<!reversal of )(?<!refund of )(?<!refund for )(?<!\\bfor )(?<!against )(?<!towards )\\bfailed\\b" +
            "|\\bdeclined\\b|\\bunsuccessful\\b|\\bnot successful\\b|\\bcould not be (?:completed|processed)\\b" +
            "|\\bnot (?:been )?processed\\b|\\brejected\\b|\\btimed?[ -]?out\\b|\\bwill be reversed\\b" +
            "|\\b(?:has been|is|was) reversed\\b(?!\\s+(?:to|in|into|back)\\b)",
    )

    private val REQUEST = rx(
        "\\bcollect request\\b|\\b(?:payment|money)\\s+request\\b|\\bhas requested\\b|\\bis requesting\\b" +
            "|\\brequest(?:ed|s|ing)?\\s+(?:for\\s+|of\\s+)?(?:Rs|INR)\\b" +
            "|\\b(?:mandate|autopay|e-?mandate|standing instruction)\\b.{0,80}\\b(?:created|set\\s?up|registered|activated)\\b" +
            "|\\bUMN\\b",
    )

    private val UPCOMING = rx(
        "\\b(?:will|shall|would|to|can|could|may|might)\\s+(?:be|get)\\s+(?:auto-?)?" +
            "(?:debited|deducted|charged|paid|credited|refunded|transferred|added|disbursed|deposited|processed|sent)\\b" +
            "|\\bupcoming\\b|\\bis due\\b|\\bdue (?:on|date|by)\\b|\\b(?:is|has been|are) scheduled\\b|\\bscheduled (?:for|on)\\b" +
            "|\\bpayable by\\b|\\bpre-?debit\\b|\\breminder\\b",
    )

    /**
     * Substrings that every match of a skip pattern contains. A pattern runs only when one of them is in the lower-cased
     * text, which keeps ordinary payments from paying for every skip regex.
     */
    private class Gated(val regex: Regex, vararg val hints: String) {
        fun find(lower: String, text: String): MatchResult? = if (hints.any { lower.contains(it) }) regex.find(text) else null
        fun matches(lower: String, text: String): Boolean = find(lower, text) != null
    }

    private val OTP_GATE = Gated(OTP, "otp", "pass", "code")
    private val PROMOTION_GATE = Gated(
        PROMOTION, "approved", "t&c", "t & c", "offer", "apply now", "use code", "flat", "%", "convert", "get", "win", "earn",
        "missed call", "coupon", "loan", "refer", "hurry", "limited period", "know more", "click here",
    )
    private val FAILED_GATE = Gated(FAILED, "fail", "declin", "success", "could not", "process", "reject", "time", "reversed")
    private val REQUEST_GATE = Gated(REQUEST, "request", "mandate", "autopay", "standing", "umn")
    private val UPCOMING_GATE = Gated(UPCOMING, "be ", "get ", "upcoming", "due", "schedul", "payable", "debit", "reminder")

    private const val LITE = "\\bupi\\s?lite\\b"
    private const val TOP_UP = "(?:top[- ]?ups?|topped[- ]up|auto[- ]?top[- ]?up|load(?:ed|ing)?|add(?:ed|ing)?\\s+money|added|recharged?)"

    /**
     * Money moved between the user's own places, which is not spending: a UPI Lite top-up (in either word order,
     * including auto top-up), the UPI Lite balance going back to the bank, and money loaded into the user's own
     * wallet. A spend paid from UPI Lite or a wallet has no top-up word and is still read as a payment.
     */
    /** Any character inside one sentence: a full stop counts as the end only when a space or line break follows ("Rs.300" stays). */
    private const val GAP = "(?:[^.\\n]|\\.(?=\\S))"

    private val SELF_TRANSFER = rx(
        "$LITE$GAP{0,40}\\b$TOP_UP\\b|\\b$TOP_UP\\b$GAP{0,40}$LITE" +
            "|\\b(?:from|of)\\s+(?:your\\s+)?$LITE(?:\\s+(?:account|balance|wallet))?$GAP{0,60}\\b(?:credited|transferred|moved|returned)\\b" +
            "|$LITE$GAP{0,40}\\b(?:disabled|deactivated|closed)\\b" +
            "|(?<!cashback\\s)(?<!refund\\s)\\b(?:added|loaded|topped[- ]up)\\s+(?:to|in|into)\\s+(?:your\\s+)?(?:[A-Za-z]+\\s){0,2}wallet\\b" +
            "|\\bwallet\\s+(?:top[- ]?up|load|recharge)\\b|\\b(?:add|load)\\s+money\\s+(?:to|in|into)\\s+(?:your\\s+)?(?:[A-Za-z]+\\s){0,2}wallet\\b",
    )
    private val SELF_TRANSFER_GATE = Gated(SELF_TRANSFER, "lite", "wallet")
    /** Cashback, refunds and rewards that land in a wallet are real income, not a top-up. */
    private val WALLET_INCOME = rx("\\b(?:cashback|refund(?:ed)?|reward|reversal|reversed)\\b")

    /**
     * A payment made with UPI Lite ("Paid Rs 20 via UPI Lite", "Rs 50 spent from your UPI Lite"). It is spending even
     * when the message also invites the user to top up ("Top up your UPI Lite now").
     */
    private val SPEND_WITH_LITE = rx(
        "\\b(?:via|using|through|with)\\s+(?:your\\s+)?$LITE" +
            "|\\b(?:paid|spent|sent|debited)\\b$GAP{0,60}\\bfrom\\s+(?:your\\s+)?$LITE(?!$GAP{0,40}\\b(?:to|into)\\s+(?:your\\s+)?(?:bank|a/c|account)\\b)",
    )

    /** True when the message moves money between the user's own accounts or wallets (see [SkipReason.SELF_TRANSFER]). */
    fun isSelfTransfer(text: String): Boolean {
        val lower = text.lowercase()
        if (!SELF_TRANSFER_GATE.matches(lower, text)) return false
        if (lower.contains("lite")) return !SPEND_WITH_LITE.containsMatchIn(text)
        return !WALLET_INCOME.containsMatchIn(text)
    }

    /**
     * A biller or merchant confirming the user's payment: "Payment of Rs 599 received for mobile 98XXXXXX10",
     * "Recharge of Rs 299 successful", "We have received premium of Rs 8,214", "Thank you for your payment of Rs 1,450".
     * Checked only for senders that are not banks, so a bank's "payment received towards your credit card" is kept.
     */
    private val BILLER_CONFIRMATION = rx(
        "\\b(?:payment|premium|recharge|amount|bill(?:\\s+payment)?)\\s+(?:of\\s+)?(?:Rs|INR)$GAP{0,60}\\b(?:received|successful|is\\s+successful|was\\s+successful|done|confirmed)\\b" +
            "|\\b(?:received|recd\\.?)\\s+(?:your\\s+|the\\s+)?(?:payment|premium|bill\\s+payment)\\b" +
            "|\\bthank(?:s| you) for (?:your |the |making (?:the |a )?)?(?:payment|recharge|bill payment)\\b" +
            "|\\b(?:payment|recharge|transaction)\\s+(?:is\\s+|was\\s+|has been\\s+)?successful\\b",
    )
    private val BILLER_RECEIPT_GATE = Gated(BILLER_CONFIRMATION, "received", "recd", "successful", "thank", "done", "confirmed")

    /** True when a non-bank sender confirms it received the user's payment (see [SkipReason.BILLER_RECEIPT]). */
    fun isBillerReceipt(text: String): Boolean = BILLER_RECEIPT_GATE.matches(text.lowercase(), text)

    private val BALANCE_INFO = rx("\\bbal\\b|balance|\\bstatement\\b|\\bstmt\\b|e-statement|\\bsummary\\b|\\bvaluation\\b")

    /** Words just before an action that make it hypothetical, negated or still to come ("will be debited", "if debited"). */
    private val NOT_DONE_BEFORE = rx("\\b(?:be|get|not|not been|if|already)\\s$")
    private const val NOT_DONE_REACH = 12

    private fun isDone(text: String, at: Int): Boolean =
        !NOT_DONE_BEFORE.containsMatchIn(text.substring(maxOf(0, at - NOT_DONE_REACH), at))

    /** The first match of [regex] that is not hypothetical or negated. */
    private fun firstDone(regex: Regex, text: String): MatchResult? = regex.findAll(text).firstOrNull { isDone(text, it.range.first) }

    /** Completed action words, with the shared word boundary factored out so most positions fail at once. */
    private const val PAST =
        "\\b(debited|credited|spent|withdrawn|deducted|paid|sent|received|refunded|reversed|transferred|added|deposited|disbursed|used)\\b"
    /** The word that ties an amount printed after it: "debited by Rs 450", "Paid Rs 20", "used for INR 3,450". */
    private val PAST_BEFORE_AMOUNT = rx("$PAST\\s+(?:with\\s+|by\\s+|for\\s+|of\\s+)?(?:an?\\s+amount\\s+of\\s+)?$")
    private val PAST_WORD = rx(PAST)
    /** How far after an amount its action word may be ("EMI of Rs 12,345 for Home Loan A/c XX8890 debited"). */
    private const val AFTER_REACH = 60
    private const val BEFORE_REACH = 40
    private val SENTENCE_END = Regex("[!?\\n]|\\.(?!\\S)")
    private val HINDI_PAST = Regex("डेबिट|जमा")
    /** Bank words strong enough to override a request or mandate mention. */
    private val STRONG = setOf("debited", "credited", "spent", "withdrawn", "deducted")

    /** A completed payment action tied to an amount: where it starts and the action word. */
    class Completed(val start: Int, val word: String)

    /** The first completed action tied to an amount ("Rs.450 debited", "Paid Rs 20"), or null. */
    fun completedAction(text: String): Completed? {
        HINDI_PAST.find(text)?.let { return Completed(it.range.first, "debited") }
        for (amount in Amounts.spans(text)) {
            val before = text.substring(maxOf(0, amount.first - BEFORE_REACH), amount.first)
            PAST_BEFORE_AMOUNT.find(before)?.let { m ->
                val at = amount.first - before.length + m.range.first
                if (isDone(text, at)) return Completed(at, m.groupValues[1].lowercase())
            }
            var after = text.substring(amount.last + 1, minOf(text.length, amount.last + 1 + AFTER_REACH))
            SENTENCE_END.find(after)?.let { after = after.substring(0, it.range.first) }
            for (m in PAST_WORD.findAll(after)) {
                if (isDone(text, amount.last + 1 + m.range.first)) return Completed(amount.first, m.groupValues[1].lowercase())
            }
        }
        return null
    }

    /**
     * The skip that applies before any card bill or payment is read, or null. [text] has its safety footers removed;
     * [completed] is [completedAction] of it. A completed payment is not a promotion because it mentions an offer,
     * and a strong bank word ("debited") outweighs a mandate or request mention.
     */
    fun earlySkip(text: String, completed: Completed?): SkipReason? {
        val lower = text.lowercase()
        val strong = completed != null && completed.word in STRONG
        return when {
            OTP_GATE.matches(lower, text) -> SkipReason.OTP
            completed == null && PROMOTION_GATE.matches(lower, text) -> SkipReason.PROMOTION
            FAILED_GATE.matches(lower, text) -> SkipReason.FAILED
            !strong && REQUEST_GATE.matches(lower, text) -> SkipReason.REQUEST
            else -> null
        }
    }

    /**
     * True when the message is about money still to move. An upcoming phrase that comes after a completed action
     * ("EMI of Rs X debited ... Next EMI is due on ...") does not make the completed payment upcoming.
     */
    fun isUpcoming(text: String, completed: Completed?): Boolean {
        val upcoming = UPCOMING_GATE.find(text.lowercase(), text) ?: return false
        if (completed == null) return true
        return upcoming.range.first < completed.start
    }

    fun mentionsBalance(text: String): Boolean = BALANCE_INFO.containsMatchIn(text)

    private val DEBIT = rx(
        "\\b(?:debit(?:ed\\b|(?=\\s{0,2}(?:INR|Rs)\\b))|spent\\b|paid\\b|paying\\s+(?:Rs|INR)\\b|withdrawn\\b|withdrawal of\\b" +
            "|deducted\\b|has a debit\\b|purchase\\b|used for\\b|Dr\\.?\\s+from\\b|transferred to\\b|sent\\s+(?:Rs|INR)\\b" +
            "|(?<![^\\n])txn\\s+(?:Rs|INR)\\b)|डेबिट",
    )
    /** "Rs.500.00 sent to x@ybl": "sent" after the amount. Runs only when the text says "sent". */
    private val SENT_AFTER_AMOUNT = rx("$AMT\\s(?:has been\\s|was\\s|is\\s)?sent\\b")

    private val CREDIT = rx(
        "\\b(?:credited|refunded|deposited|disbursed|added to|credit ho|has a credit|refund|reversal|reversed\\s+(?:to|in|into|back)" +
            "|Cr\\.?\\s+to)\\b|क्रेडिट|जमा",
    )

    /** "Received" is money in only when tied to an amount: "Received Rs.750", "Rs.25 Cashback received", "received your payment of INR". */
    private val RECEIVED = rx(
        "\\breceived\\s+(?:an?\\s+)?(?:your\\s+)?(?:(?:payment|amount|money|credit|cashback)\\s+(?:of\\s+)?)?(?:Rs|INR)\\b" +
            "|$AMT\\s(?:[A-Za-z]+\\s)?(?:has been\\s|is\\s|was\\s)?received\\b",
    )
    /** A biller or insurer saying it received the user's money: that is the user's spend, not income. */
    private val BILLER_RECEIPT = rx("\\b(?:premium|policy|bill(?!\\s*pay)|invoice|consumer|order)\\b")

    /** Direction from the earliest action word that is not hypothetical or negated, or null when the text has none. */
    fun direction(text: String): Direction? {
        val lower = text.lowercase()
        val debit = listOfNotNull(
            firstDone(DEBIT, text)?.range?.first,
            if (lower.contains("sent")) firstDone(SENT_AFTER_AMOUNT, text)?.range?.first else null,
        ).minOrNull()
        val credit = listOfNotNull(
            firstDone(CREDIT, text)?.range?.first,
            if (lower.contains("received")) {
                firstDone(RECEIVED, text)?.takeUnless { BILLER_RECEIPT.containsMatchIn(text) }?.range?.first
            } else {
                null
            },
        ).minOrNull()
        return when {
            debit == null && credit == null -> null
            credit == null -> Direction.DEBIT
            debit == null -> Direction.CREDIT
            debit <= credit -> Direction.DEBIT
            else -> Direction.CREDIT
        }
    }
}
