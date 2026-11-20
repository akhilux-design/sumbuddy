package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.StatTile
import com.expenses.app.ui.components.StatTileTone
import com.expenses.app.ui.theme.Space

/** Width of one tile in the design file. On screens the tile takes the width its place gives it. */
private val DesignTileWidth = 158.dp

/**
 * Every tone of [StatTile] with the sample text of the design, top to bottom in the order that
 * design/spec/components/Stat-Tile.png shows from left to right: Neutral, Positive, Negative.
 */
val statTilePreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "stat-tile") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            StatTileTone.entries.forEach { tone ->
                StatTile(
                    label = "Spent in Nov",
                    value = "₹18,240",
                    modifier = Modifier.width(DesignTileWidth),
                    tone = tone,
                )
            }
        }
    },
)
