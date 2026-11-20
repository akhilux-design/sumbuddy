package com.expenses.app.ui.screens.review

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.data.model.PaidWith
import com.expenses.app.ui.screens.payment.PaymentAccount
import com.expenses.app.ui.screens.payment.PaymentTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Sample Review payments states with the payments and amounts drawn in the design, for previews. */
object ReviewPaymentsSamples {
    private val swiggy = ReviewCard(
        id = 2,
        amountMinor = 45_000,
        payee = "Swiggy",
        time = PaymentTime(at = LocalDateTime.of(2026, 11, 16, 21, 12), today = LocalDate.of(2026, 11, 16)),
        account = PaymentAccount(name = "HDFC", type = AccountType.CREDIT_CARD, last4 = "4521", upiId = null),
        paidWith = PaidWith.CARD,
        category = Category.FOOD,
    )

    /** Design 34: the third of 14 payments, with Undo. */
    val reviewing: ReviewPaymentsUiState.Reviewing = ReviewPaymentsUiState.Reviewing(
        card = swiggy,
        cardsBehind = 2,
        position = 3,
        total = 14,
        canUndo = true,
        addAll = null,
    )

    /** Design 37: the Add all sheet over the first of 14 payments. */
    val addAll: ReviewPaymentsUiState.Reviewing = reviewing.copy(
        position = 1,
        canUndo = false,
        addAll = AddAllSummary(
            count = 14,
            totalMinor = 864_000,
            byCategory = listOf(
                CategoryTotal(Category.FOOD, 6, 218_000),
                CategoryTotal(Category.GROCERIES, 3, 392_000),
                CategoryTotal(Category.TRAVEL, 4, 114_000),
                CategoryTotal(Category.SHOPPING, 1, 140_000),
            ),
        ),
    )

    /** Design 38: 12 added and 2 ignored. */
    val sorted: ReviewPaymentsUiState.Sorted = ReviewPaymentsUiState.Sorted(
        addedCount = 12,
        addedMinor = 719_000,
        ignoredCount = 2,
        month = YearMonth.of(2026, 11),
        addedAllInMonth = true,
        monthTotalMinor = 2_543_000,
    )
}
