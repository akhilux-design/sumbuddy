package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.Tag
import com.expenses.app.ui.components.TagTone
import com.expenses.app.ui.theme.Space

/**
 * Preview "tag": the six tones of [Tag] with the label from `design/spec/components/Tag.png`, top to bottom in
 * the picture's left-to-right order: Brand, Yellow, Purple, Blue, Negative, Neutral.
 */
val tagPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "tag") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            TagTone.entries.forEach { tone ->
                Tag(label = "Auto", tone = tone)
            }
        }
    },
)
