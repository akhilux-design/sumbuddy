package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.AppChip
import com.expenses.app.ui.theme.Space

/**
 * Preview "chip": both variants of [AppChip] with the label from `design/spec/components/Chip.png`, top to
 * bottom in the picture's left-to-right order: not selected, selected.
 */
val appChipPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "chip") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            AppChip(label = "Equally", selected = false, onClick = {})
            AppChip(label = "Equally", selected = true, onClick = {})
        }
    },
)
