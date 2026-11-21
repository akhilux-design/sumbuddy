package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.screens.cardbill.CardBillSamples
import com.expenses.app.ui.screens.cardbill.CardBillScreen
import com.expenses.app.ui.screens.cardbill.CardBillUiState

/**
 * The Card bill view, one entry per design state in design/spec/screens/03-getting-paid-back: "cardbill-view" (04),
 * "cardbill-nothing-owed" (20), "cardbill-signed-out" (21), "cardbill-overdue" (22), "cardbill-paid" (23),
 * "cardbill-reminder-sent" (24), "cardbill-no-reminder-sent" (25) and "cardbill-offline" (26). Not drawn (copy only,
 * docs/card-bills.md D24): "cardbill-one-friend".
 */
val cardBillScreenPreviews: List<PreviewEntry> = listOf(
    cardBillPreview("cardbill-view", CardBillSamples.default),
    cardBillPreview("cardbill-nothing-owed", CardBillSamples.nothingOwed),
    cardBillPreview("cardbill-signed-out", CardBillSamples.signedOut),
    cardBillPreview("cardbill-overdue", CardBillSamples.overdue),
    cardBillPreview("cardbill-paid", CardBillSamples.paid),
    cardBillPreview("cardbill-reminder-sent", CardBillSamples.reminderSent),
    cardBillPreview("cardbill-no-reminder-sent", CardBillSamples.noReminderSent),
    cardBillPreview("cardbill-offline", CardBillSamples.offline),
    cardBillPreview("cardbill-one-friend", CardBillSamples.oneFriend),
)

private fun cardBillPreview(name: String, state: CardBillUiState): PreviewEntry =
    PreviewEntry(name = name, fullScreen = true) { CardBillScreen(state = state) }
