package com.expenses.app.data.category

import androidx.annotation.StringRes
import com.expenses.app.R
import com.expenses.app.ui.icons.AppIcon

/**
 * The categories an expense can have, in the order the Choose category screen lists them
 * (design 02-everyday/06-choose-category). The names are stored in the database, so never rename one.
 *
 * @param icon the icon drawn for the category; rows and tiles use its 3D art.
 * @param label the category's name.
 * @param examples the line under the name on Choose category ("Swiggy, Zomato, restaurants").
 */
enum class Category(val icon: AppIcon, @StringRes val label: Int, @StringRes val examples: Int) {
    /** Restaurants and food delivery. */
    FOOD(AppIcon.Food, R.string.category_food, R.string.category_food_examples),

    /** Groceries and quick commerce. */
    GROCERIES(AppIcon.Cart, R.string.category_groceries, R.string.category_groceries_examples),

    /** Cabs, trains, flights, fuel and metro. */
    TRAVEL(AppIcon.Car, R.string.category_travel, R.string.category_travel_examples),

    /** Rent, electricity, phone, broadband and other bills. */
    RENT_AND_BILLS(AppIcon.Home, R.string.category_rent_and_bills, R.string.category_rent_and_bills_examples),

    /** Online and offline shopping. */
    SHOPPING(AppIcon.Bag, R.string.category_shopping, R.string.category_shopping_examples),

    /** Movies, events and subscriptions. */
    FUN(AppIcon.Film, R.string.category_fun, R.string.category_fun_examples),

    /** Anything else. The category of a payment no keyword matches. */
    OTHER(AppIcon.Plus, R.string.category_other, R.string.category_other_examples),
}
