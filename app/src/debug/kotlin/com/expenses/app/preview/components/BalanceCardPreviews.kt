package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.BalanceCard
import com.expenses.app.ui.components.BalanceCardTone
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.theme.Space

/**
 * Preview "balance-card": the three tones of [BalanceCard] with the sample text of
 * `design/spec/components/Balance-Card.png`, top to bottom in the picture's left-to-right order:
 * Positive, Negative, Neutral.
 */
val balanceCardPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "balance-card") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            listOf(BalanceCardTone.Positive, BalanceCardTone.Negative, BalanceCardTone.Neutral).forEach { tone ->
                BalanceCard(
                    label = "You are owed",
                    amount = formatRupees(amountMinor = 345_000),
                    caption = "from 4 friends",
                    tone = tone,
                )
            }
        }
    },
)
