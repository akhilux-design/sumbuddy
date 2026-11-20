package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.OptionCard
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space

/** Width of one variant in design/spec/components/Option-Card.png. */
private val OptionCardDesignWidth = 312.dp

/**
 * Both states of [OptionCard] with the sample text and icon of the design, top to bottom in the order of
 * design/spec/components/Option-Card.png: not selected, selected.
 */
val optionCardPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "option-card") {
        Column(
            modifier = Modifier.width(OptionCardDesignWidth),
            verticalArrangement = Arrangement.spacedBy(Space.s16),
        ) {
            listOf(false, true).forEach { selected ->
                OptionCard(
                    title = "This month",
                    subtitle = "Payments since the 1st are added.",
                    icon = AppIcon.Receipt,
                    selected = selected,
                    onClick = {},
                )
            }
        }
    },
)
