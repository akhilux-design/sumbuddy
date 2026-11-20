package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.format.PayeeWording
import com.expenses.app.ui.screens.payment.AddExpenseScreen
import com.expenses.app.ui.screens.payment.AddToExpensesScreen
import com.expenses.app.ui.screens.payment.AddToExpensesUiState
import com.expenses.app.ui.screens.payment.ChooseCategoryScreen
import com.expenses.app.ui.screens.payment.ExpenseAddedScreen
import com.expenses.app.ui.screens.payment.ExpenseAddedUiState
import com.expenses.app.ui.screens.payment.ExpenseDetailScreen
import com.expenses.app.ui.screens.payment.NeedsReviewScreen
import com.expenses.app.ui.screens.payment.PaymentSamples

/**
 * The payment screens, one entry per design state in design/spec/screens/02-everyday: 05 Add to expenses, 06 Choose
 * category, 07 Expense added, 08 Expense detail, 15 Add expense and 19 Needs review, plus the states the design does
 * not draw (editing, the original message, the amount error and nothing left to review). The split states of 08 and
 * 15 (and 47) are in the split-flow area's previews.
 */
val paymentScreenPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "payment-add-to-expenses", fullScreen = true) {
        AddToExpensesScreen(state = PaymentSamples.addToExpenses, onEvent = {})
    },
    // Not in the design: a payee known only by its UPI ID. "Do this every time" names it whole, broken only after "@".
    PreviewEntry(name = "payment-add-to-expenses-upi-payee", fullScreen = true) {
        AddToExpensesScreen(
            state = (PaymentSamples.addToExpenses as AddToExpensesUiState.Form).copy(payee = "rahul.kumar.sharma@okhdfcbank"),
            onEvent = {},
        )
    },
    PreviewEntry(name = "payment-choose-category", fullScreen = true) {
        ChooseCategoryScreen(state = PaymentSamples.chooseCategory, onEvent = {})
    },
    PreviewEntry(name = "payment-expense-added", fullScreen = true) {
        ExpenseAddedScreen(state = PaymentSamples.expenseAdded, onEvent = {})
    },
    // Not in the design: the same payee in Expense added's sentence and row.
    PreviewEntry(name = "payment-expense-added-upi-payee", fullScreen = true) {
        ExpenseAddedScreen(
            state = (PaymentSamples.expenseAdded as ExpenseAddedUiState.Shown)
                .copy(merchant = "rahul.kumar.sharma@okhdfcbank", payeeWording = PayeeWording.TO),
            onEvent = {},
        )
    },
    PreviewEntry(name = "payment-expense-detail", fullScreen = true) {
        ExpenseDetailScreen(state = PaymentSamples.expenseDetail, onEvent = {})
    },
    PreviewEntry(name = "payment-expense-detail-message", fullScreen = true) {
        ExpenseDetailScreen(state = PaymentSamples.expenseDetailWithMessage, onEvent = {})
    },
    PreviewEntry(name = "payment-expense-detail-edit", fullScreen = true) {
        ExpenseDetailScreen(state = PaymentSamples.expenseDetailEdit, onEvent = {})
    },
    PreviewEntry(name = "payment-add-expense", fullScreen = true) {
        AddExpenseScreen(state = PaymentSamples.addExpense, onEvent = {}, focusAmount = false)
    },
    PreviewEntry(name = "payment-add-expense-error", fullScreen = true) {
        AddExpenseScreen(state = PaymentSamples.addExpenseError, onEvent = {}, focusAmount = false)
    },
    PreviewEntry(name = "payment-needs-review", fullScreen = true) {
        NeedsReviewScreen(state = PaymentSamples.needsReview, onEvent = {})
    },
    PreviewEntry(name = "payment-needs-review-empty", fullScreen = true) {
        NeedsReviewScreen(state = PaymentSamples.needsReviewEmpty, onEvent = {})
    },
)
