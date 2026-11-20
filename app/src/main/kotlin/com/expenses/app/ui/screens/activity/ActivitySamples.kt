package com.expenses.app.ui.screens.activity

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.PaidWith
import com.expenses.app.ui.icons.AppIcon
import java.time.YearMonth

/** Sample states of the Activity tab with the people, merchants and amounts of the design, one per design state. */
object ActivitySamples {
    private val november: YearMonth = YearMonth.of(2026, 11)

    /** The list as design/spec/screens/02-everyday/16-activity.json draws it: two days, five payments. */
    val default: ActivityUiState = ActivityUiState(
        month = november,
        spentPaise = 1_824_000,
        fromSplitsPaise = 576_000,
        content = ActivityContent.Days(
            selectedFilter = ActivityFilter.All,
            days = listOf(
                ActivityDay(
                    date = ActivityDate.Today,
                    items = listOf(
                        ActivityItem(
                            id = ActivityItemId.Expense(1),
                            title = "Swiggy",
                            icon = AppIcon.Food,
                            amountPaise = 15_000,
                            detail = ActivityItemDetail.Split(
                                category = Category.FOOD,
                                totalPaise = 45_000,
                                groupName = "Flat 302",
                            ),
                        ),
                        ActivityItem(
                            id = ActivityItemId.Expense(2),
                            title = "Blinkit",
                            icon = AppIcon.Cart,
                            amountPaise = 31_200,
                            detail = ActivityItemDetail.Personal(
                                category = Category.GROCERIES,
                                paidWith = PaidWithLabel.Method(PaidWith.UPI),
                            ),
                        ),
                        ActivityItem(
                            id = ActivityItemId.Expense(3),
                            title = "Uber",
                            icon = AppIcon.Car,
                            amountPaise = 18_600,
                            detail = ActivityItemDetail.Split(
                                category = Category.TRAVEL,
                                totalPaise = 55_800,
                                groupName = "Goa trip",
                            ),
                        ),
                    ),
                ),
                ActivityDay(
                    date = ActivityDate.Yesterday,
                    items = listOf(
                        ActivityItem(
                            id = ActivityItemId.Expense(4),
                            title = "BigBasket",
                            icon = AppIcon.Cart,
                            amountPaise = 234_000,
                            detail = ActivityItemDetail.Pooled(
                                category = Category.GROCERIES,
                                paidWith = PaidWithLabel.Account(name = "HDFC", last4 = "4521"),
                                groupName = "Family",
                            ),
                        ),
                        ActivityItem(
                            id = ActivityItemId.Review(1),
                            title = "UPI payment",
                            icon = AppIcon.Help,
                            amountPaise = 120_000,
                            detail = ActivityItemDetail.NeedsReview,
                        ),
                    ),
                ),
            ),
        ),
    )

    /** The Splits filter: only the two split payments of [default]. */
    val splits: ActivityUiState = default.copy(
        content = ActivityContent.Days(
            selectedFilter = ActivityFilter.Splits,
            days = (default.content as ActivityContent.Days).days.mapNotNull { day ->
                day.copy(items = day.items.filter { it.detail is ActivityItemDetail.Split }).takeIf { it.items.isNotEmpty() }
            },
        ),
    )

    /** Money in that names nobody: titled "Money in", with only how it came in ("UPI") under it. */
    val moneyIn: ActivityUiState = default.copy(
        content = ActivityContent.Days(
            selectedFilter = ActivityFilter.All,
            days = listOf(
                ActivityDay(
                    date = ActivityDate.Today,
                    items = listOf(
                        ActivityItem(
                            id = ActivityItemId.Expense(5),
                            title = null,
                            icon = AppIcon.Cash,
                            amountPaise = 120_000,
                            detail = ActivityItemDetail.MoneyIn(paidWith = PaidWithLabel.Method(PaidWith.UPI)),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** Nothing added yet, as design/spec/screens/02-everyday/17-activity-empty.json draws it. */
    val empty: ActivityUiState = ActivityUiState(
        month = november,
        spentPaise = 0,
        fromSplitsPaise = 0,
        content = ActivityContent.Empty,
    )
}
