package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.SegmentedToggle
import com.expenses.app.ui.components.SegmentedToggleSide
import com.expenses.app.ui.theme.Space

/**
 * Both states of [SegmentedToggle] with the labels of the design, top to bottom in the order that
 * design/spec/components/Segmented-Toggle.png shows from left to right: Left selected, then Right selected.
 * Tapping the other option moves the pill, so the touch area can be tried on a phone.
 */
val segmentedTogglePreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "segmented-toggle") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            SegmentedToggleSide.entries.forEach { side ->
                SampleToggle(startsOn = side)
            }
        }
    },
)

/** A toggle that starts on the side of its variant and remembers taps. */
@Composable
private fun SampleToggle(startsOn: SegmentedToggleSide) {
    var selected by remember { mutableStateOf(startsOn) }
    SegmentedToggle(
        left = "Me",
        right = "Family",
        selected = selected,
        onSelectedChange = { selected = it },
    )
}
