package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.TopBar
import com.expenses.app.ui.theme.Space

/**
 * Width of the Top Bar in the design picture. It is applied as a required width, so on a 360dp phone the bars
 * reach past the preview's 16dp padding to both screen edges, as in the picture.
 */
private val TopBarDesignWidth = 360.dp

/**
 * Preview "top-bar". First the bar exactly as the design picture shows it (back button, "Flat 302", "Edit"),
 * then the states the design has no property for: without the action, without the back button, title only.
 */
val topBarPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "top-bar") {
        Column(
            modifier = Modifier.requiredWidth(TopBarDesignWidth),
            verticalArrangement = Arrangement.spacedBy(Space.s16),
        ) {
            TopBar(title = "Flat 302", onBack = {}, actionLabel = "Edit", onAction = {})
            TopBar(title = "Import from Splitwise", onBack = {})
            TopBar(title = "November 2026", actionLabel = "Change", onAction = {})
            TopBar(title = "Step 1 of 3")
        }
    },
)
