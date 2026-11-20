package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.components.BalanceRow
import com.expenses.app.ui.components.BalanceRowDirection
import com.expenses.app.ui.theme.Space

/**
 * Every direction of [BalanceRow] with the sample text of the design, top to bottom in the order of
 * design/spec/components/Balance-Row.png: Owes you, You owe, Settled, Plain.
 */
val balanceRowPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "balance-row") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            BalanceRowDirection.entries.forEach { direction ->
                BalanceRow(
                    name = "Rahul",
                    detail = "owes you",
                    amount = "+₹1,200",
                    initial = "R",
                    direction = direction,
                    avatarColor = AvatarColor.Green,
                )
            }
        }
    },
)
