package com.expenses.parser

/** How the money moved, from the words in the message. */
internal object Channels {
    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private val ATM = rx("\\bATM\\b|\\bwithdrawn\\b|\\bcash withdrawal\\b")
    private val NEFT = rx("\\bNEFT\\b")
    private val RTGS = rx("\\bRTGS\\b")
    private val IMPS = rx("\\bIMPS\\b")
    private val UPI = rx("\\bUPI\\b|\\bVPA\\b|[A-Za-z0-9._-]@[A-Za-z]{2,}(?![A-Za-z]*\\.(?:com|in|co|org|net)\\b)")
    private val AUTOPAY = rx("\\bNACH\\b|\\bECS\\b|\\bauto-?debit\\b|\\bautopay\\b|\\bstanding instruction\\b|\\bmandate\\b")
    private val NET_BANKING = rx("\\bnet\\s?banking\\b|\\binternet banking\\b")
    private val CHEQUE = rx("\\bCLG\\b|\\bCHQ\\b|\\bcheque\\s?no\\b|\\bcheque\\s+number\\b")
    private val CARD = rx("\\bcard\\b")
    private val UPI_WORD = rx("\\bUPI\\b")

    /** Each pattern runs only when the lower-cased text holds a substring every match of it contains. */
    private fun String.has(regex: Regex, lower: String, vararg hints: String): Boolean =
        hints.any { lower.contains(it) } && regex.containsMatchIn(this)

    fun read(text: String, instrument: InstrumentType): Channel {
        val lower = text.lowercase()
        return when {
            text.has(ATM, lower, "atm", "withdraw") && instrument != InstrumentType.CREDIT_CARD -> Channel.ATM
            text.has(NEFT, lower, "neft") -> Channel.NEFT
            text.has(RTGS, lower, "rtgs") -> Channel.RTGS
            text.has(IMPS, lower, "imps") -> Channel.IMPS
            text.has(AUTOPAY, lower, "nach", "ecs", "auto", "standing", "mandate") && !text.has(UPI_WORD, lower, "upi") -> Channel.AUTOPAY
            text.has(UPI, lower, "upi", "vpa", "@") -> Channel.UPI
            text.has(NET_BANKING, lower, "banking") -> Channel.NET_BANKING
            text.has(CHEQUE, lower, "clg", "chq", "cheque") -> Channel.CHEQUE
            instrument == InstrumentType.WALLET -> Channel.WALLET
            instrument == InstrumentType.CREDIT_CARD || instrument == InstrumentType.DEBIT_CARD -> Channel.CARD
            text.has(CARD, lower, "card") && instrument != InstrumentType.BANK_ACCOUNT -> Channel.CARD
            else -> Channel.UNKNOWN
        }
    }
}
