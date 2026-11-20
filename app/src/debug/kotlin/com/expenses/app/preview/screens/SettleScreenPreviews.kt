package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.screens.settle.BalanceClearedScreen
import com.expenses.app.ui.screens.settle.MarkAsPaidScreen
import com.expenses.app.ui.screens.settle.PaymentMatchScreen
import com.expenses.app.ui.screens.settle.PaymentMatchUiState
import com.expenses.app.ui.screens.settle.RemindScreen
import com.expenses.app.ui.screens.settle.SettleSamples
import com.expenses.app.ui.screens.settle.SettleUpScreen

/**
 * Previews of settling up, one entry per design state in design/spec/screens/03-getting-paid-back: 00 Settle up,
 * 07 no UPI ID, 01 Mark as paid, 02 Money received, 03 All square and 05 Remind on WhatsApp, plus the states the design
 * does not draw (Paid another way picked, no UPI app, a friend without the app, nothing to clear, offline). Owned by
 * the settle-up area (docs/groups-build-plan.md).
 */
val settleScreenPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "settle-up", fullScreen = true) {
        SettleUpScreen(state = SettleSamples.settleUp, onEvent = {})
    },
    PreviewEntry(name = "settle-up-other", fullScreen = true) {
        SettleUpScreen(state = SettleSamples.settleUpOther, onEvent = {})
    },
    PreviewEntry(name = "settle-up-no-upi-id", fullScreen = true) {
        SettleUpScreen(state = SettleSamples.settleUpNoUpiId, onEvent = {})
    },
    PreviewEntry(name = "settle-up-no-upi-app", fullScreen = true) {
        SettleUpScreen(state = SettleSamples.settleUpNoUpiApp, onEvent = {})
    },
    PreviewEntry(name = "settle-mark-as-paid", fullScreen = true) {
        MarkAsPaidScreen(state = SettleSamples.markAsPaid, onEvent = {})
    },
    PreviewEntry(name = "settle-mark-as-paid-no-app", fullScreen = true) {
        MarkAsPaidScreen(state = SettleSamples.markAsPaidNoApp, onEvent = {})
    },
    PreviewEntry(name = "settle-payment-match", fullScreen = true) {
        PaymentMatchScreen(state = SettleSamples.paymentMatch, onEvent = {})
    },
    PreviewEntry(name = "settle-payment-match-several", fullScreen = true) {
        PaymentMatchScreen(state = SettleSamples.paymentMatchSeveral, onEvent = {})
    },
    PreviewEntry(name = "settle-payment-match-upi-account", fullScreen = true) {
        PaymentMatchScreen(state = SettleSamples.paymentMatchUpiAccount, onEvent = {})
    },
    PreviewEntry(name = "settle-payment-match-gone", fullScreen = true) {
        PaymentMatchScreen(state = PaymentMatchUiState.Gone, onEvent = {})
    },
    PreviewEntry(name = "settle-balance-cleared", fullScreen = true) {
        BalanceClearedScreen(state = SettleSamples.balanceCleared, onEvent = {})
    },
    PreviewEntry(name = "settle-balance-cleared-still-owes", fullScreen = true) {
        BalanceClearedScreen(state = SettleSamples.balanceClearedStillOwes, onEvent = {})
    },
    PreviewEntry(name = "settle-remind", fullScreen = true) {
        RemindScreen(state = SettleSamples.remind, onEvent = {})
    },
    // Not designed: Copy link on Android 8 to 12, where the system confirms nothing itself.
    PreviewEntry(name = "settle-remind-copied", fullScreen = true) {
        RemindScreen(state = SettleSamples.remind, onEvent = {}, linkCopied = true)
    },
    PreviewEntry(name = "settle-remind-offline", fullScreen = true) {
        RemindScreen(state = SettleSamples.remindOffline, onEvent = {})
    },
    PreviewEntry(name = "settle-remind-failed", fullScreen = true) {
        RemindScreen(state = SettleSamples.remindFailed, onEvent = {})
    },
)
