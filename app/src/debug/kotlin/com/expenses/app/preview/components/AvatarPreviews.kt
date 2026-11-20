package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.Avatar
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.theme.Space

/**
 * Every colour of [Avatar] with the sample initial of the design, top to bottom in the order that
 * design/spec/components/Avatar.png shows from left to right: Green, Yellow, Pink, Purple, Blue, Orange.
 */
val avatarPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "avatar") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            AvatarColor.entries.forEach { color ->
                Avatar(initial = "R", color = color)
            }
        }
    },
)
