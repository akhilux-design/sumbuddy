package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.theme.Space

/**
 * Preview "button": the six variants of [AppButton] with the label from `design/spec/components/Button.png`,
 * top to bottom in the picture's left-to-right order: Primary Large, Primary Medium, Secondary Large,
 * Secondary Medium, Ghost Large, Ghost Medium.
 */
val appButtonPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "button") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            ButtonStyle.entries.forEach { style ->
                ButtonSize.entries.forEach { size ->
                    AppButton(label = "Button", onClick = {}, style = style, size = size)
                }
            }
        }
    },
)
