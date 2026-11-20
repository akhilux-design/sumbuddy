package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.screens.payment.AddExpenseScreen
import com.expenses.app.ui.screens.payment.ExpenseDetailScreen
import com.expenses.app.ui.screens.payment.PaymentSamples
import com.expenses.app.ui.screens.split.ChoosePeopleScreen
import com.expenses.app.ui.screens.split.SameSplitScreen
import com.expenses.app.ui.screens.split.SameSplitUiState
import com.expenses.app.ui.screens.split.SplitAddedScreen
import com.expenses.app.ui.screens.split.SplitSamples
import com.expenses.app.ui.screens.split.SplitScreen

/**
 * Previews of the split flow and the split parts of the payment screens, one entry per design state in
 * design/spec/screens/02-everyday: 09, 10 and 11 Split it, 12 and 48 Choose people, 13 Split. Sorted., 14 Same split?,
 * 15 Add expense with "Split this", 47 Expense detail of a split; plus the states the design does not draw. Owned by
 * the split-flow area (docs/groups-build-plan.md).
 */
val splitScreenPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "split-equally", fullScreen = true) { SplitScreen(state = SplitSamples.equally, onEvent = {}) },
    PreviewEntry(name = "split-amounts", fullScreen = true) { SplitScreen(state = SplitSamples.amounts, onEvent = {}) },
    PreviewEntry(name = "split-amounts-left", fullScreen = true) { SplitScreen(state = SplitSamples.amountsLeft, onEvent = {}) },
    PreviewEntry(name = "split-no-group", fullScreen = true) { SplitScreen(state = SplitSamples.noGroup, onEvent = {}) },
    PreviewEntry(name = "split-choose-people", fullScreen = true) {
        ChoosePeopleScreen(state = SplitSamples.choosePeople, onEvent = {})
    },
    PreviewEntry(name = "split-choose-people-several", fullScreen = true) {
        ChoosePeopleScreen(state = SplitSamples.choosePeopleSeveral, onEvent = {})
    },
    PreviewEntry(name = "split-choose-people-empty", fullScreen = true) {
        ChoosePeopleScreen(state = SplitSamples.choosePeopleEmpty, onEvent = {})
    },
    PreviewEntry(name = "split-added", fullScreen = true) { SplitAddedScreen(state = SplitSamples.added, onEvent = {}) },
    PreviewEntry(name = "split-added-one-friend", fullScreen = true) {
        SplitAddedScreen(state = SplitSamples.addedOneFriend, onEvent = {})
    },
    // Not designed: nobody owes anybody through a split with one friend ("Split with Rahul.").
    PreviewEntry(name = "split-added-one-friend-nothing-owed", fullScreen = true) {
        SplitAddedScreen(state = SplitSamples.addedOneFriendNothingOwed, onEvent = {})
    },
    PreviewEntry(name = "split-same-split", fullScreen = true) { SameSplitScreen(state = SplitSamples.sameSplit, onEvent = {}) },
    // Not designed: the earlier split was not last month ("You've split Swiggy with Flat 302 before.").
    PreviewEntry(name = "split-same-split-before", fullScreen = true) {
        SameSplitScreen(state = SplitSamples.sameSplitBefore, onEvent = {})
    },
    // Not designed: a payee known only by its UPI ID, whole in the headline and broken only after its "@".
    PreviewEntry(name = "split-same-split-upi-payee", fullScreen = true) {
        SameSplitScreen(
            state = (SplitSamples.sameSplitBefore as SameSplitUiState.Shown).copy(payee = "uberindia.rides@hdfcbank"),
            onEvent = {},
        )
    },
    PreviewEntry(name = "split-add-expense", fullScreen = true) {
        AddExpenseScreen(state = PaymentSamples.addExpenseSplit, onEvent = {}, focusAmount = false)
    },
    PreviewEntry(name = "split-add-expense-friend-paid", fullScreen = true) {
        AddExpenseScreen(state = PaymentSamples.addExpenseFriendPaid, onEvent = {}, focusAmount = false)
    },
    PreviewEntry(name = "split-expense-detail", fullScreen = true) {
        ExpenseDetailScreen(state = PaymentSamples.expenseDetailSplit, onEvent = {})
    },
    PreviewEntry(name = "split-expense-detail-delete", fullScreen = true) {
        ExpenseDetailScreen(state = PaymentSamples.expenseDetailSplitDelete, onEvent = {})
    },
    PreviewEntry(name = "split-expense-detail-splits-itself", fullScreen = true) {
        ExpenseDetailScreen(state = PaymentSamples.expenseDetailSplitsItself, onEvent = {})
    },
    PreviewEntry(name = "split-expense-detail-friend-paid", fullScreen = true) {
        ExpenseDetailScreen(state = PaymentSamples.expenseDetailFriendPaid, onEvent = {})
    },
)
