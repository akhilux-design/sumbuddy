package com.expenses.app.data.category

/**
 * Guesses a category from a merchant name or UPI address with a small keyword table. The person can always change
 * the guess, and a merchant they fixed with "Do this every time" is looked up before this table (see
 * [com.expenses.app.data.repository.ExpenseRepository.guessCategory]).
 *
 * Keywords match whole words only, ignoring case and punctuation, so "Ola" matches "OLA CABS" but not "Motorola".
 * Categories are tried in a fixed order so that the more specific one wins: "Swiggy Instamart" is Groceries,
 * not Food, and "Amazon Prime Video" is Fun, not Shopping.
 */
object CategoryGuesser {
    private val KEYWORDS: List<Pair<Category, List<String>>> = listOf(
        Category.GROCERIES to listOf(
            "blinkit", "bigbasket", "big basket", "bbnow", "zepto", "instamart", "grofers", "jiomart", "dmart",
            "amazon fresh", "flipkart minutes", "more retail", "nature s basket", "grocery", "groceries",
            "supermarket", "kirana",
        ),
        Category.FUN to listOf(
            "netflix", "bookmyshow", "spotify", "hotstar", "jiohotstar", "prime video", "pvr", "inox", "zee5",
            "sonyliv", "youtube premium", "apple music", "gaana", "district", "cinepolis",
        ),
        Category.FOOD to listOf(
            "swiggy", "zomato", "eatsure", "dominos", "domino s", "pizza hut", "mcdonalds", "mcdonald s", "kfc",
            "burger king", "starbucks", "chaayos", "haldiram", "restaurant", "cafe", "dhaba", "bakery",
        ),
        Category.TRAVEL to listOf(
            "uber", "ola", "ola cabs", "rapido", "irctc", "redbus", "makemytrip", "goibibo", "ixigo", "indigo",
            "air india", "akasa", "vistara", "metro", "fastag", "fuel", "petrol", "diesel", "indian oil", "iocl",
            "bharat petroleum", "bpcl", "hpcl", "hindustan petroleum", "shell",
        ),
        Category.RENT_AND_BILLS to listOf(
            "rent", "electricity", "recharge", "broadband", "bescom", "tata power", "adani electricity", "msedcl",
            "bses", "tneb", "airtel", "jio", "vodafone", "vi", "bsnl", "act fibernet", "hathway", "dth", "tata play",
            "gas", "lpg", "indane", "water", "nobroker", "maintenance",
        ),
        Category.SHOPPING to listOf(
            "amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "tata cliq", "croma", "reliance digital",
            "decathlon", "ikea", "lenskart", "snapdeal",
        ),
    )

    private val PATTERNS: List<Pair<Category, Regex>> = KEYWORDS.map { (category, words) ->
        category to Regex("\\b(?:" + words.joinToString("|") { Regex.escape(it) } + ")\\b")
    }

    /**
     * For UPI addresses, whose handles often run words together ("uberindia.rides", "airtelrecharge"): keywords of
     * at least [VPA_PREFIX_MIN] letters also match at the start of a longer word. Shorter ones ("ola", "vi") still need
     * the whole word, so a person's address such as "vikram@okaxis" is not taken for a merchant.
     */
    private val VPA_PATTERNS: List<Pair<Category, Regex>> = KEYWORDS.map { (category, words) ->
        val (prefixes, whole) = words.partition { it.length >= VPA_PREFIX_MIN }
        val alternatives = prefixes.map { Regex.escape(it) } + whole.map { Regex.escape(it) + "\\b" }
        category to Regex("\\b(?:" + alternatives.joinToString("|") + ")")
    }

    private const val VPA_PREFIX_MIN = 4

    private val NOT_WORD = Regex("[^a-z0-9]+")

    /**
     * The category for a payment to [counterparty], also looking at its UPI address [vpa] when the name does not
     * match. Only the part of the address before "@" is read, since the part after it names the payment app or bank.
     * [Category.OTHER] when neither matches.
     */
    fun guess(counterparty: String?, vpa: String? = null): Category {
        counterparty?.let { match(merchantKey(it), PATTERNS) }?.let { return it }
        vpa?.let { match(merchantKey(it.substringBefore('@')), VPA_PATTERNS) }?.let { return it }
        return Category.OTHER
    }

    private fun match(key: String, patterns: List<Pair<Category, Regex>>): Category? =
        if (key.isEmpty()) null else patterns.firstOrNull { (_, regex) -> regex.containsMatchIn(key) }?.first

    /**
     * The merchant name in the form used for keyword matching and for the person's "always this category" rules:
     * lower case, every run of characters other than letters and digits turned into one space, trimmed.
     * "SWIGGY*Order" and "swiggy order" give the same key.
     */
    fun merchantKey(name: String): String = name.lowercase().replace(NOT_WORD, " ").trim()
}
