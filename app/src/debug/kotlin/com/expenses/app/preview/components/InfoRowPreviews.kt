package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space

/**
 * [InfoRow] with the sample text of design/spec/components/Info-Row.png first (the component has no variants),
 * then the two other ways the screens use it: an explanation that wraps onto a second line, and a line that can
 * be tapped.
 */
val infoRowPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "info-row") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            InfoRow(
                title = "Read on your phone only",
                subtitle = "Nothing is uploaded, ever.",
                icon = AppIcon.Receipt,
            )
            InfoRow(
                title = "Payment detected",
                subtitle = "One notification per payment, with three choices.",
                icon = AppIcon.Cash,
            )
            InfoRow(
                title = "What we've read",
                subtitle = "214 messages · 0 sent off this phone",
                icon = AppIcon.Eye,
                onClick = {},
            )
        }
    },
)
