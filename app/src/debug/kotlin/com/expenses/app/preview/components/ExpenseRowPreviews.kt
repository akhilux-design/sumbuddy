package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.ExpenseRow
import com.expenses.app.ui.components.ExpenseRowKind
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space

/**
 * Every kind of [ExpenseRow] with the sample text of the design, top to bottom in the order of
 * design/spec/components/Expense-Row.png: Personal, Shared, Review.
 */
val expenseRowPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "expense-row") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            ExpenseRowKind.entries.forEach { kind ->
                ExpenseRow(
                    title = "Swiggy",
                    subtitle = "Food · HDFC card ••4521",
                    amount = "₹450",
                    meta = "Just you",
                    icon = AppIcon.Receipt,
                    kind = kind,
                )
            }
        }
    },
)
