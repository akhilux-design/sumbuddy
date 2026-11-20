package com.expenses.parser

internal data class Instrument(val type: InstrumentType, val last4: String?)

/** Account or card digits ("XX4521", "**4521", "••4521", "ending 4521", "ending in 4521", "4129XXXXXXXX4521") and what kind of instrument they belong to. */
internal object Instruments {
    private val MASKED = Regex(
        "(?<![A-Za-z0-9])(?:\\d{1,6})?(?:[Xx]{1,16}|\\*{1,16}|\\.{2,4}|[•●·]{1,16})\\s?(\\d{3,6})(?!\\d)",
    )
    private val AFTER_ACCOUNT = Regex(
        "\\b(?:a/c|acct|ac)\\s?(?:no\\.?)?\\s?[:.]?\\s?[Xx*]*(\\d{3,6})(?![\\d/-])",
        RegexOption.IGNORE_CASE,
    )
    private val UNMASKED = Regex(
        "\\b(?:ending(?:\\s+(?:with|in))?|card(?:\\s+no\\.?)?)\\s?[:\\-]?\\s?(\\d{4,6})(?![\\dXx*/-])",
        RegexOption.IGNORE_CASE,
    )
    private val FROM_BEFORE = Regex("\\bfrom\\b", RegexOption.IGNORE_CASE)
    private val PHONE_BEFORE = Regex("(?:mobile|mob|ph|phone)\\s?(?:no\\.?)?\\s?$", RegexOption.IGNORE_CASE)

    private val WALLET = Regex("\\bwallet\\b|amazon pay balance", RegexOption.IGNORE_CASE)
    private val CREDIT_CARD = Regex("credit\\s?card", RegexOption.IGNORE_CASE)
    private val DEBIT_CARD = Regex("debit\\s?card", RegexOption.IGNORE_CASE)
    private val ACCOUNT = Regex("\\b(?:a/c|acct|account|ac)(?![A-WYZa-wyz0-9])|खाते", RegexOption.IGNORE_CASE)
    private val CARD = Regex("\\bcard\\b", RegexOption.IGNORE_CASE)

    /**
     * "CC" and "DC", short for credit and debit card, as printed just before the digits ("CC XX4521", "DC ending 1234").
     * Read only in the words right before the digits, where they cannot mean anything else.
     */
    private val CC_NEAR = Regex("\\bCC\\b", RegexOption.IGNORE_CASE)
    private val DC_NEAR = Regex("\\bDC\\b", RegexOption.IGNORE_CASE)

    /**
     * Issuer wording that names a credit card without the words "credit card": HDFC's block line "SMS BLOCK CC 4521"
     * (Yes Bank "BLKCC 2207"), "CC ending 4521" and "Dear Cardmember" (HDFC, ICICI, Amex).
     */
    private val CREDIT_HINT = Regex(
        "\\b(?:block|blk)\\s?cc\\b|\\bcc\\s?(?:no\\.?\\s?|ending\\s(?:with\\s|in\\s)?)?[Xx*•]*\\d{4}\\b|\\bcard\\s?members?\\b",
        RegexOption.IGNORE_CASE,
    )

    /** A printed limit ("Avl Lmt", "available limit", "credit limit"): a debit card has a balance, not a limit. */
    private val LIMIT = Regex("\\blimit\\b|\\blmt\\b", RegexOption.IGNORE_CASE)

    /** HDFC's block line of a debit card ("SMS BLOCK DC 1234"), and the like. */
    private val DEBIT_HINT = Regex("\\b(?:block|blk)\\s?dc\\b|\\bdc\\s?(?:no\\.?\\s?)?[Xx*•]*\\d{4}\\b", RegexOption.IGNORE_CASE)
    private val CARD_ISSUERS = setOf(Banks.SBI_CARD, Banks.AMEX, Banks.ONECARD)
    private const val CONTEXT = 30

    private data class Digits(val value: String, val start: Int)

    fun read(text: String, bank: String?, direction: Direction): Instrument {
        val digits = digits(text, preferFrom = direction == Direction.DEBIT)
        val read = digits?.let { typeNear(text, it.start, bank) } ?: typeAnywhere(text, bank)
        // Issuers that only give credit cards: whatever the SMS calls it ("your OneCard XX7788", "your card account").
        val type = if (bank in CARD_ISSUERS && (read == InstrumentType.UNKNOWN || read == InstrumentType.BANK_ACCOUNT)) {
            InstrumentType.CREDIT_CARD
        } else {
            read
        }
        return Instrument(type, digits?.value)
    }

    /** Last digits of the first account or card printed, skipping masked phone numbers. */
    fun last4(text: String): String? = digits(text)?.value

    /**
     * The first account or card printed. For a debit, an account the money left "from" wins over one named
     * earlier ("EMI for Loan A/c XX8890 debited from your A/c XX1102").
     */
    private fun digits(text: String, preferFrom: Boolean = false): Digits? {
        val candidates = sequenceOf(MASKED, AFTER_ACCOUNT, UNMASKED)
            .flatMap { it.findAll(text) }
            .filterNot { PHONE_BEFORE.containsMatchIn(text.substring(maxOf(0, it.range.first - 12), it.range.first)) }
            .map { Digits(it.groupValues[1], it.groups[1]!!.range.first) }
            .sortedBy { it.start }
            .toList()
        if (preferFrom) {
            candidates.firstOrNull { FROM_BEFORE.containsMatchIn(text.substring(maxOf(0, it.start - CONTEXT), it.start)) }
                ?.let { return it }
        }
        return candidates.firstOrNull()
    }

    /** The instrument named closest before the digits. */
    private fun typeNear(text: String, start: Int, bank: String?): InstrumentType? {
        val window = text.substring(maxOf(0, start - CONTEXT), start)
        val positions = listOf(
            InstrumentType.CREDIT_CARD to CREDIT_CARD,
            InstrumentType.DEBIT_CARD to DEBIT_CARD,
            InstrumentType.CREDIT_CARD to CC_NEAR,
            InstrumentType.DEBIT_CARD to DC_NEAR,
            InstrumentType.BANK_ACCOUNT to ACCOUNT,
            InstrumentType.UNKNOWN to CARD,
        ).mapNotNull { (type, regex) -> regex.findAll(window).lastOrNull()?.let { type to it.range.last } }
        val nearest = positions.maxWithOrNull(compareBy<Pair<InstrumentType, Int>> { it.second }.thenBy { it.first != InstrumentType.UNKNOWN })
            ?: return null
        return if (nearest.first == InstrumentType.UNKNOWN) genericCard(text, bank) else nearest.first
    }

    private fun typeAnywhere(text: String, bank: String?): InstrumentType = when {
        WALLET.containsMatchIn(text) || bank in Banks.WALLETS -> InstrumentType.WALLET
        CREDIT_CARD.containsMatchIn(text) -> InstrumentType.CREDIT_CARD
        DEBIT_CARD.containsMatchIn(text) || DEBIT_HINT.containsMatchIn(text) -> InstrumentType.DEBIT_CARD
        CREDIT_HINT.containsMatchIn(text) -> InstrumentType.CREDIT_CARD
        ACCOUNT.containsMatchIn(text) -> InstrumentType.BANK_ACCOUNT
        CARD.containsMatchIn(text) -> genericCard(text, bank)
        else -> InstrumentType.UNKNOWN
    }

    /**
     * A card that is not called credit or debit right before its digits. A debit card named anywhere in the message
     * (or its "BLOCK DC" line) wins; then issuers that only give credit cards, and credit card wording anywhere
     * ([CREDIT_HINT], a printed [LIMIT]), make it a credit card. Otherwise the kind is unknown.
     */
    private fun genericCard(text: String, bank: String?): InstrumentType = when {
        DEBIT_CARD.containsMatchIn(text) || DEBIT_HINT.containsMatchIn(text) -> InstrumentType.DEBIT_CARD
        bank in CARD_ISSUERS || CREDIT_CARD.containsMatchIn(text) || CREDIT_HINT.containsMatchIn(text) ||
            LIMIT.containsMatchIn(text) -> InstrumentType.CREDIT_CARD
        else -> InstrumentType.UNKNOWN
    }
}
