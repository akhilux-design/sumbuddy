package com.expenses.app.groups.splitwise

import com.expenses.app.data.category.Category
import org.junit.Assert.assertEquals
import org.junit.Test

/** Splitwise's categories to the app's (docs/splitwise-import.md 5.2, decision 16). */
class ImportCategoriesTest {
    @Test fun everyRowOfTheTableMaps() {
        val table = mapOf(
            Category.GROCERIES to listOf("Groceries"),
            Category.FOOD to listOf("Dining out", "Liquor", "Food and drink - Other", "Food and drink"),
            Category.TRAVEL to listOf("Taxi", "Bus/train", "Plane", "Car", "Gas/fuel", "Parking", "Hotel", "Bicycle", "Transportation - Other"),
            Category.RENT_AND_BILLS to listOf(
                "Rent", "Mortgage", "Electricity", "Heat/gas", "Water", "TV/Phone/Internet", "Trash", "Cleaning", "Maintenance",
                "Services", "Insurance", "Utilities - Other", "Home - Other",
            ),
            Category.SHOPPING to listOf("Clothing", "Electronics", "Furniture", "Gifts", "Household supplies"),
            Category.FUN to listOf("Games", "Movies", "Music", "Sports", "Entertainment - Other"),
            Category.OTHER to listOf("Childcare", "Education", "Medical expenses", "Taxes", "Pets", "Life - Other"),
        )
        for ((category, names) in table) {
            for (name in names) assertEquals(name, category, ImportCategories.toCategory(name, "Swiggy"))
        }
    }

    @Test fun generalBlankAndUnknownAreGuessedFromTheDescription() {
        assertEquals(Category.FOOD, ImportCategories.toCategory("General", "Swiggy"))
        assertEquals(Category.FOOD, ImportCategories.toCategory("", "Swiggy"))
        assertEquals(Category.FOOD, ImportCategories.toCategory(null, "Swiggy"))
        assertEquals(Category.OTHER, ImportCategories.toCategory("Something new", "Thing"))
        assertEquals(Category.OTHER, ImportCategories.toCategory("General", ""))
    }
}
