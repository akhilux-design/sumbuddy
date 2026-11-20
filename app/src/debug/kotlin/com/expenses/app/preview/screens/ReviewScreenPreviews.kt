package com.expenses.app.preview.screens

import androidx.compose.ui.unit.dp
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.screens.review.ReviewPaymentsSamples
import com.expenses.app.ui.screens.review.ReviewPaymentsScreen

/**
 * Review payments, one entry per design state in design/spec/screens/02-everyday: 34 the stack, 35 and 36 dragged
 * 64dp right and left, 37 the Add all sheet and 38 All sorted. Not designed: "fixes-review-all-sorted-only-splits",
 * All sorted with no Undo after a run whose payments were all split.
 */
val reviewScreenPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "review-payments", fullScreen = true) {
        ReviewPaymentsScreen(state = ReviewPaymentsSamples.reviewing, onEvent = {}, reduceMotion = false)
    },
    PreviewEntry(name = "review-payments-dragging-right", fullScreen = true) {
        ReviewPaymentsScreen(
            state = ReviewPaymentsSamples.reviewing,
            onEvent = {},
            reduceMotion = false,
            initialDrag = 64.dp,
        )
    },
    PreviewEntry(name = "review-payments-dragging-left", fullScreen = true) {
        ReviewPaymentsScreen(
            state = ReviewPaymentsSamples.reviewing,
            onEvent = {},
            reduceMotion = false,
            initialDrag = (-64).dp,
        )
    },
    // Not in the design: a payee known only by its UPI ID, shown whole and broken only after its "@".
    PreviewEntry(name = "review-payments-upi-payee", fullScreen = true) {
        ReviewPaymentsScreen(
            state = ReviewPaymentsSamples.reviewing.copy(
                card = ReviewPaymentsSamples.reviewing.card.copy(payee = "rahul.kumar.sharma@okhdfcbank"),
            ),
            onEvent = {},
            reduceMotion = false,
        )
    },
    PreviewEntry(name = "review-payments-add-all", fullScreen = true) {
        ReviewPaymentsScreen(state = ReviewPaymentsSamples.addAll, onEvent = {}, reduceMotion = false)
    },
    PreviewEntry(name = "review-payments-all-sorted", fullScreen = true) {
        ReviewPaymentsScreen(state = ReviewPaymentsSamples.sorted, onEvent = {})
    },
    // Not in the design: All sorted after a run whose only payment was split, so there is nothing to undo here.
    PreviewEntry(name = "fixes-review-all-sorted-only-splits", fullScreen = true) {
        ReviewPaymentsScreen(
            state = ReviewPaymentsSamples.sorted.copy(addedCount = 1, addedMinor = 15_000, ignoredCount = 0, canUndo = false),
            onEvent = {},
        )
    },
)
