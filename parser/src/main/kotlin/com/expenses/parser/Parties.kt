package com.expenses.parser

/** The other side of a payment (name and UPI address) and the reference number. */
internal object Parties {
    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    // ---- UPI address ----

    private val VPA = Regex(
        "(?<![A-Za-z0-9._-])([A-Za-z0-9][A-Za-z0-9._-]{0,63}@[A-Za-z][A-Za-z0-9]{1,20})(?!\\.(?:com|in|co|org|net)\\b)(?![A-Za-z0-9@])",
    )
    /** Words before the user's own UPI address ("from your UPI ID a@ptyes", "to your VPA me@ybl"). */
    private val OWN_VPA_BEFORE = rx("\\byour\\s+(?:UPI\\s?(?:ID|address|handle)|VPA)\\s?:?\\s?$")
    private val FROM_BEFORE = rx("\\bfrom\\s+(?:VPA\\s+)?$")
    private val TO_YOUR_BEFORE = rx("\\b(?:to|in)\\s+your\\s+(?:VPA\\s+)?$")
    private const val OWN_CONTEXT = 30

    /**
     * The other side's UPI address. The user's own address is skipped when the text marks it ("your UPI ID"), or when it
     * is the "from" side of a debit or the "to your" side of a credit, as long as another address is printed.
     */
    fun vpa(text: String, direction: Direction): String? {
        val all = VPA.findAll(text).toList()
        if (all.size <= 1) return all.firstOrNull()?.groupValues?.get(1)
        val other = all.firstOrNull { match ->
            val before = text.substring(maxOf(0, match.range.first - OWN_CONTEXT), match.range.first)
            !OWN_VPA_BEFORE.containsMatchIn(before) && when (direction) {
                Direction.DEBIT -> !FROM_BEFORE.containsMatchIn(before)
                Direction.CREDIT -> !TO_YOUR_BEFORE.containsMatchIn(before)
            }
        }
        return (other ?: all.first()).groupValues[1]
    }

    // ---- reference ----

    private val REFERENCE = rx(
        "\\b(?:UPI\\s?Ref(?:erence)?\\.?(?:\\s?No\\.?)?|IMPS\\s?Ref\\.?(?:\\s?No\\.?)?|Ref(?:erence)?\\.?\\s?no\\.?|Ref(?:erence)?\\.?\\s?#?|Refno" +
            "|RRN(?:\\s?No\\.?)?|UTR(?:\\s?No\\.?)?|Txn\\s?(?:ID|No\\.?)|txn#|Transaction\\s?(?:ID|No\\.?|Number)" +
            "|UPI|IMPS|NEFT|RTGS)\\s?[:#.\\-]?\\s?([A-Z0-9]{6,25})(?![A-Za-z0-9])",
    )
    /** "UPI/P2M/<ref>/<name>/...", "UPI/DR/<ref>/<name>/...", "UPI/<ref>/<name>", "NEFT/<ref>/<name>". */
    private val SLASH_NARRATION = rx("\\b(?:UPI/(?:P2[AMB]|DR|CR)|UPI|NEFT|IMPS|RTGS)/([A-Z0-9]{6,25})/([^/\\n]{2,60})")
    private val DASH_NARRATION = rx("\\b(?:UPI|NEFT|IMPS|RTGS)-([A-Z0-9]{9,25})-([^\\n.]{2,60})")
    private val DIGIT = Regex("\\d")
    private val DIGITS4 = Regex("(?:\\D*\\d){4}")

    fun reference(text: String): String? {
        SLASH_NARRATION.find(text)?.let { return it.groupValues[1] }
        return REFERENCE.findAll(text).map { it.groupValues[1] }.firstOrNull { DIGITS4.containsMatchIn(it) && DIGIT.containsMatchIn(it) }
    }

    // ---- name ----

    private const val HONORIFIC = "(?:mr|mrs|ms|dr|smt|shri|sri|m/s)"
    private const val TLD = "(?:com|in|co|net|org|io|app|store|shop)\\b"

    /**
     * A display name: a letter first (any script, accents included), up to 60 characters. A dot belongs to the name
     * only after an initial ("B.K."), after an honorific ("MR."), or before a web domain ending ("AMAZON.IN").
     */
    private const val NAME =
        "(\\p{L}(?:[\\p{L}\\p{M}0-9&'’\\- ]|(?<=\\b\\p{L})\\.|(?<=\\b$HONORIFIC)\\.|\\.(?=$TLD)){0,59}?)"

    /** Where a name ends: a known following word, a digit, punctuation, any symbol or emoji, or the end of the text. */
    private const val END =
        "(?=\\s(?:(?:on|in|into|your|via|using|thru|through|from|ref|refno|upi|imps|neft|rtgs|txn|avl|avbl|dated|has|is|was|for|with|at|by|credited|debited|if|not|info|IST)\\b|\\d)" +
            "|(?<!\\b\\p{L})(?<!\\b$HONORIFIC)\\s?\\.(?!$TLD)|\\s?[,;:(\\n/]|\\s-\\s|\\s?[^\\p{L}\\p{M}\\p{N}&'’\\-.\\s]|\\s?$)"

    private val MANDATE_FOR = rx("\\bmandate\\s+for\\s+$NAME$END")
    private val TRANSFER = rx("\\b(?:trf|transfer|transferred)\\s+(?:to|from)\\s+$NAME$END")
    private val LINKED_MOBILE = rx("\\blinked to mobile\\s+[0-9Xx*]{6,12}-$NAME$END")
    private val ICICI_CREDITED = rx(";\\s?$NAME\\s+credited\\b")
    private val REFUND_FROM = rx("\\b(?:refund|reversal)\\b[^\\n]{0,50}?\\b(?:from|at)\\s+$NAME$END")
    private val TO = rx("\\bto\\s+(?:VPA\\s+)?$NAME$END")
    private val AT = rx("(?:\\bat|(?<=\\s)@)\\s?$NAME$END")
    private val TOWARDS = rx("\\btowards\\s+$NAME$END")
    private val FROM = rx("\\bfrom\\s+$NAME$END")
    private val ON = rx("\\bon\\s+$NAME$END")
    private val BY = rx("\\bby\\s+$NAME$END")
    private val MULTILINE_MERCHANT = Regex("\\n\\d{2}-\\d{2}-\\d{2}[0-9: ,]{0,12}(?:IST)?\\n([A-Za-z][^\\n]{1,60})\\n")

    /** First words that show a capture is not a name. */
    private val NOT_A_NAME = rx(
        "^(?:your|you|yours|a|an|the|a/c|ac|acct|account|vpa|mobile|mob|block|report|dispute|avoid|pay|be|this|us|card|" +
            "bank|rs|inr|cash|charges?|sms|imps|neft|rtgs|upi|nach|ecs|atm|pos|debit|credit|emi|net|internet|registered|" +
            "trf|transfer|payment|mandate|reversal|refund|sufficient|non|use|apply|call|keep|ensure|get|know|date)\\b",
    )
    /** The user's own card or account printed where a merchant could be ("on HDFC Bank Card x4521"). */
    private val MASKED_DIGITS = Regex("[Xx*•●]+\\d{3,}")
    private val INSTRUMENT_WORD = rx("\\b(?:card|a/c|acct|account|wallet)\\b")
    private val HONORIFIC_ONLY = rx("^$HONORIFIC\\.?$")

    fun counterparty(text: String, direction: Direction): String? {
        val narration = SLASH_NARRATION.find(text)?.groupValues?.get(2) ?: DASH_NARRATION.find(text)?.groupValues?.get(2)
        if (narration != null) return clean(narration.substringBefore(" - ").substringBefore("/"))
        val patterns = when (direction) {
            Direction.DEBIT -> listOf(MANDATE_FOR, ICICI_CREDITED, TRANSFER, TO, AT, TOWARDS, ON)
            Direction.CREDIT -> listOf(REFUND_FROM, LINKED_MOBILE, TRANSFER, FROM, AT, BY)
        }
        for (pattern in patterns) {
            for (match in pattern.findAll(text)) {
                val name = match.groupValues[1].trim()
                if (NOT_A_NAME.containsMatchIn(name) || isOwnInstrument(name)) continue
                if (isPartOfVpa(text, match.groups[1]!!.range.last + 1)) continue
                clean(name)?.let { return it }
            }
        }
        return MULTILINE_MERCHANT.find(text)?.groupValues?.get(1)
            ?.takeUnless { NOT_A_NAME.containsMatchIn(it) || it.contains('/') || DIGIT.containsMatchIn(it) }
            ?.let(::clean)
    }

    private fun isOwnInstrument(name: String): Boolean =
        MASKED_DIGITS.containsMatchIn(name) || (INSTRUMENT_WORD.containsMatchIn(name) && Banks.fromText(name) != null)

    /** True when the captured name runs straight into a UPI address ("rahul" of "rahul.example@okaxis"). */
    private fun isPartOfVpa(text: String, end: Int): Boolean {
        val next = text.getOrNull(end) ?: return false
        return next == '@' || (next == '.' && text.getOrNull(end + 1)?.isLetterOrDigit() == true && text.indexOf('@', end).let {
            it in end..(end + 64) && text.substring(end, it).none { c -> c.isWhitespace() }
        })
    }

    private val LEADING_VPA = rx("^VPA\\s+")
    private val REFERENCE_TOKEN = Regex("\\b[A-Z0-9]*\\d{6,}[A-Z0-9]*\\b")
    private val TRAILING = Regex("[\\s.,;:\\-/()]+$")
    private val SPACES = Regex("\\s+")
    /** The first letter of each word: not after a letter or apostrophe ("Domino's"), nor after a domain dot ("Amazon.in"). */
    private val WORD_START = Regex("(?<![\\p{L}\\p{M}'’])(?<!\\p{L}{2}\\.)\\p{L}")

    /** No "VPA", no reference numbers, no trailing punctuation; ALL CAPS becomes Title Case. */
    fun clean(raw: String): String? {
        var name = raw.replace(LEADING_VPA, "").replace(REFERENCE_TOKEN, " ").replace(SPACES, " ").trim()
        name = name.replace(TRAILING, "").trim()
        if (name.length < 2 || !name.any { it.isLetter() } || HONORIFIC_ONLY.matches(name)) return null
        if (name.none { it.isLowerCase() }) {
            name = WORD_START.replace(name.lowercase()) { it.value.uppercase() }
        }
        return name
    }
}
