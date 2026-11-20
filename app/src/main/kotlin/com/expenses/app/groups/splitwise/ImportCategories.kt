package com.expenses.app.groups.splitwise

import com.expenses.app.data.category.Category
import com.expenses.app.data.category.CategoryGuesser
import com.expenses.splitwise.SplitwiseCategories

/**
 * The app's category of a Splitwise row (docs/splitwise-import.md 5.2, decision 16): Splitwise's own category names by
 * the fixed table of `:splitwise` ([SplitwiseCategories]); "General" (Splitwise's default), blank and unknown names are
 * guessed from the description with [CategoryGuesser] ("Swiggy" is Food), which gives Other when nothing matches.
 */
object ImportCategories {
    /** The category for Splitwise's category text [raw] of a row described as [description]. */
    fun toCategory(raw: String?, description: String): Category =
        SplitwiseCategories.map(raw)?.let { Category.valueOf(it.name) } ?: CategoryGuesser.guess(description)
}
