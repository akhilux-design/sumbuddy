package com.expenses.parser

/** Display names of banks and card issuers, and how to recognise them from a sender header or the text. */
object Banks {
    const val HDFC = "HDFC Bank"
    const val ICICI = "ICICI Bank"
    const val SBI = "State Bank of India"
    const val AXIS = "Axis Bank"
    const val KOTAK = "Kotak Mahindra Bank"
    const val PNB = "Punjab National Bank"
    const val BOB = "Bank of Baroda"
    const val CANARA = "Canara Bank"
    const val UNION = "Union Bank of India"
    const val IDFC = "IDFC FIRST Bank"
    const val YES = "Yes Bank"
    const val INDUSIND = "IndusInd Bank"
    const val FEDERAL = "Federal Bank"
    const val INDIAN = "Indian Bank"
    const val BOI = "Bank of India"
    const val CENTRAL = "Central Bank of India"
    const val IOB = "Indian Overseas Bank"
    const val UCO = "UCO Bank"
    const val IDBI = "IDBI Bank"
    const val RBL = "RBL Bank"
    const val AU = "AU Small Finance Bank"
    const val BANDHAN = "Bandhan Bank"
    const val SIB = "South Indian Bank"
    const val KARNATAKA = "Karnataka Bank"
    const val SC = "Standard Chartered"
    const val HSBC = "HSBC"
    const val CITI = "Citibank"
    const val AMEX = "American Express"
    const val SBI_CARD = "SBI Card"
    const val PAYTM_BANK = "Paytm Payments Bank"
    const val AIRTEL_BANK = "Airtel Payments Bank"
    const val IPPB = "India Post Payments Bank"
    const val JUPITER = "Jupiter"
    const val FI = "Fi"
    const val SLICE = "slice"
    const val ONECARD = "OneCard"
    const val AMAZON_PAY = "Amazon Pay"
    const val PAYTM = "Paytm"
    const val PHONEPE = "PhonePe"
    const val CRED = "CRED"

    /** Issuers whose money sits in a wallet rather than a bank account. */
    val WALLETS: Set<String> = setOf(AMAZON_PAY, PAYTM, PHONEPE)

    /** DLT header code (the part after the operator prefix, without suffix), upper case. */
    private val HEADERS: Map<String, String> = buildMap {
        fun add(name: String, vararg codes: String) { for (code in codes) this[code] = name }
        add(HDFC, "HDFCBK", "HDFCBN", "HDFCCC", "HDFCBA")
        add(ICICI, "ICICIB", "ICICIT", "ICICIO", "ICICIC")
        add(SBI, "SBIINB", "SBIPSG", "CBSSBI", "ATMSBI", "SBIUPI", "SBIBNK", "SBIOTP", "SBYONO")
        add(SBI_CARD, "SBICRD", "SBICARD", "SBICAR")
        add(AXIS, "AXISBK", "AXISCC", "AXISBN")
        add(KOTAK, "KOTAKB", "KOTAK", "KMBANK")
        add(PNB, "PNBSMS", "PNBBNK", "PUNBNK")
        add(BOB, "BOBSMS", "BOBTXN", "BOBCRD", "BARODA")
        add(CANARA, "CANBNK", "CANARA")
        add(UNION, "UNIONB", "UBINDB", "UBISMS")
        add(IDFC, "IDFCFB", "IDFCBK")
        add(YES, "YESBNK", "YESBK")
        add(INDUSIND, "INDUSB", "INDUSL")
        add(FEDERAL, "FEDBNK", "FEDFIB")
        add(INDIAN, "INDBNK", "INDIAN")
        add(BOI, "BOIIND", "BOISMS")
        add(CENTRAL, "CENTBK", "CBOI")
        add(IOB, "IOBCHN", "IOBBNK")
        add(UCO, "UCOBNK", "UCOBK")
        add(IDBI, "IDBIBK", "IDBIBN")
        add(RBL, "RBLBNK", "RBLCRD", "RBLBK")
        add(AU, "AUBANK", "AUBNK", "AUSFB")
        add(BANDHAN, "BDNSMS", "BANDHN")
        add(SIB, "SIBSMS", "SIBANK")
        add(KARNATAKA, "KTKBNK", "KBLBNK")
        add(SC, "SCBANK", "STANCB")
        add(HSBC, "HSBCIN", "HSBCBK")
        add(CITI, "CITIBK", "CITIBA")
        add(AMEX, "AMEXIN", "AMEX")
        add(PAYTM_BANK, "PAYTMB", "PYTMBK")
        add(AIRTEL_BANK, "AIRBNK", "AIRTBK")
        add(IPPB, "IPBMSG", "IPPBNK")
        add(JUPITER, "JUPITR", "JUPTER")
        add(FI, "FIMONY", "FIBANK")
        add(SLICE, "SLICEIT", "SLCEIT")
        add(ONECARD, "ONECRD", "ONECARD")
        add(AMAZON_PAY, "AMAZON", "AMZPAY", "APAYIN")
        add(PAYTM, "PAYTM", "IPAYTM")
        add(PHONEPE, "PHONPE", "PHONEPE")
        add(CRED, "CREDIN", "CREDCL")
    }

    /** Names and short forms as they appear in message text, most specific first. */
    private val TEXT_NAMES: List<Pair<Regex, String>> = listOf(
        "SBI\\s*Card|SBI\\s*Credit\\s*Card" to SBI_CARD,
        "India\\s*Post\\s*Payments?\\s*Bank|IPPB" to IPPB,
        "Airtel\\s*Payments?\\s*Bank" to AIRTEL_BANK,
        "Paytm\\s*Payments?\\s*Bank" to PAYTM_BANK,
        "Union\\s*Bank\\s*of\\s*India|Union\\s*Bank" to UNION,
        "Central\\s*Bank\\s*of\\s*India|CBoI" to CENTRAL,
        "Indian\\s*Overseas\\s*Bank|IOB" to IOB,
        "Bank\\s*of\\s*Baroda|BOB" to BOB,
        "Bank\\s*of\\s*India|BOI" to BOI,
        "Punjab\\s*National\\s*Bank|PNB" to PNB,
        "State\\s*Bank\\s*of\\s*India|State\\s*Bank|SBI" to SBI,
        "HDFC\\s*Bank|HDFCBANK|HDFC" to HDFC,
        "ICICI\\s*Bank|ICICI" to ICICI,
        "Axis\\s*Bank" to AXIS,
        "Kotak(?:\\s*Mahindra)?(?:\\s*Bank)?" to KOTAK,
        "Canara\\s*Bank" to CANARA,
        "IDFC\\s*FIRST(?:\\s*Bank)?|IDFC" to IDFC,
        "YES\\s*BANK" to YES,
        "IndusInd(?:\\s*Bank)?" to INDUSIND,
        "Federal\\s*Bank" to FEDERAL,
        "South\\s*Indian\\s*Bank" to SIB,
        "Indian\\s*Bank" to INDIAN,
        "UCO\\s*Bank" to UCO,
        "IDBI(?:\\s*Bank)?" to IDBI,
        "RBL(?:\\s*Bank)?" to RBL,
        "AU\\s*Small\\s*Finance\\s*Bank|AU\\s*Bank" to AU,
        "Bandhan\\s*Bank" to BANDHAN,
        "Karnataka\\s*Bank" to KARNATAKA,
        "Standard\\s*Chartered|StanChart" to SC,
        "HSBC" to HSBC,
        "Citi\\s*bank|Citi" to CITI,
        "American\\s*Express|AMEX" to AMEX,
        "OneCard" to ONECARD,
        "Amazon\\s*Pay" to AMAZON_PAY,
        "PhonePe" to PHONEPE,
        "Paytm" to PAYTM,
        "Jupiter" to JUPITER,
        "slice" to SLICE,
        "CRED" to CRED,
    ).map { (pattern, name) -> Regex("(?<![A-Za-z0-9@.])(?:$pattern)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE) to name }

    private val PREFIX = Regex("^[A-Z]{2}-")
    private val SUFFIX = Regex("-[STPG]$")
    private val BARE_PREFIX = Regex("^[A-Z]{2}(?=[A-Z0-9]{6}$)")

    /**
     * The header code without operator prefix and suffix, upper case: "VM-HDFCBK-S" gives "HDFCBK". Some phones give
     * the header without the hyphen: "VMHDFCBK" gives "HDFCBK" too. Those two letters are dropped
     * only when what is left is a known six-letter header, so an unknown eight-letter header stays as it is.
     */
    fun headerCode(sender: String): String {
        val code = sender.trim().uppercase().replace(PREFIX, "").replace(SUFFIX, "")
        if (code in HEADERS) return code
        val bare = code.replace(BARE_PREFIX, "")
        return if (bare in HEADERS) bare else code
    }

    /** Bank for a sender header, or null when the header is not known. */
    fun fromSender(sender: String): String? = HEADERS[headerCode(sender)]

    /** First bank named in the text, or null. */
    fun fromText(text: String): String? {
        var best: Pair<Int, String>? = null
        for ((regex, name) in TEXT_NAMES) {
            val match = regex.find(text) ?: continue
            if (best == null || match.range.first < best.first) best = match.range.first to name
        }
        return best?.second
    }
}
