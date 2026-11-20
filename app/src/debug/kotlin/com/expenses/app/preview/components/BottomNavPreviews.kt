package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.BottomNav
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.theme.Space

/**
 * Width of the Bottom Nav in the design picture. It is applied as a required width, so on a 360dp phone the bars
 * reach past the preview's 16dp padding to both screen edges, as in the picture.
 */
private val BottomNavDesignWidth = 360.dp

/**
 * Preview "bottom-nav". The four variants in the order of the design picture: Home, Groups, Activity and You
 * as the active tab.
 */
val bottomNavPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "bottom-nav") {
        Column(
            modifier = Modifier.requiredWidth(BottomNavDesignWidth),
            verticalArrangement = Arrangement.spacedBy(Space.s16),
        ) {
            MainTab.entries.forEach { tab ->
                BottomNav(active = tab, onSelect = {})
            }
        }
    },
)
