package com.expenses.splitwise

import java.util.Locale

/**
 * The app's categories, by the same names as `com.expenses.app.data.category.Category` (and the server's): the app
 * takes one with `Category.valueOf(it.name)`.
 */
enum class SwCategory { FOOD, GROCERIES, TRAVEL, RENT_AND_BILLS, SHOPPING, FUN, OTHER }

/**
 * Splitwise's category text to the app's category (docs/splitwise-import.md 5.2, decision 16). Splitwise's own names
 * map by a fixed table; "General" (Splitwise's default), "Payment", blank and unknown text map to null, and the app
 * then guesses from the description with its `CategoryGuesser` ("Swiggy" is Food), which gives Other when nothing
 * matches.
 */
object SplitwiseCategories {
    // Declared first: the tables below are built with [normalize].
    private val SPACES = Regex("""\s+""")
    private val AROUND_MARKS = Regex("""\s*([/-])\s*""")

    /** Exact names. Compared lower-cased, spaces collapsed, no spaces around `/` and `-`. */
    private val EXACT: Map<String, SwCategory> = buildMap {
        fun add(category: SwCategory, vararg names: String) = names.forEach { this[normalize(it)] = category }
        add(SwCategory.GROCERIES, "groceries")
        add(SwCategory.FOOD, "dining out", "liquor")
        add(SwCategory.TRAVEL, "taxi", "bus/train", "plane", "car", "gas/fuel", "parking", "hotel", "bicycle")
        add(
            SwCategory.RENT_AND_BILLS,
            "rent", "mortgage", "electricity", "heat/gas", "water", "tv/phone/internet", "trash", "cleaning",
            "maintenance", "services", "insurance", "home - other",
        )
        add(SwCategory.SHOPPING, "clothing", "electronics", "furniture", "gifts", "household supplies")
        add(SwCategory.FUN, "games", "movies", "music", "sports")
        add(SwCategory.OTHER, "childcare", "education", "medical expenses", "taxes", "pets")
    }

    /** Parent names: the name itself, or the name followed by something that is not a letter ("Utilities - Other"). */
    private val PARENTS: List<Pair<String, SwCategory>> = listOf(
        "food and drink" to SwCategory.FOOD,
        "transportation" to SwCategory.TRAVEL,
        "utilities" to SwCategory.RENT_AND_BILLS,
        "entertainment" to SwCategory.FUN,
        "life" to SwCategory.OTHER,
    )

    /** The category for Splitwise's [raw] category text, or null to guess from the description. */
    fun map(raw: String?): SwCategory? {
        val text = normalize(raw.orEmpty())
        if (text.isEmpty()) return null
        EXACT[text]?.let { return it }
        return PARENTS.firstOrNull { (name, _) ->
            text == name || (text.startsWith(name) && !text[name.length].isLetterOrDigit())
        }?.second
    }

    private fun normalize(text: String): String =
        text.trim().lowercase(Locale.ROOT).replace(SPACES, " ").replace(AROUND_MARKS, "$1")
}
