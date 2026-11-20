package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.AppSwitch
import com.expenses.app.ui.theme.Space

/**
 * Both states of [AppSwitch], top to bottom in the order that design/spec/components/Switch.png shows from left
 * to right: off, then on. Tapping a switch flips it, so the slide and the touch area can be tried on a phone.
 */
val appSwitchPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "switch") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            SampleSwitch(startsOn = false)
            SampleSwitch(startsOn = true)
        }
    },
)

/** A switch that starts in the state of its variant and remembers taps. */
@Composable
private fun SampleSwitch(startsOn: Boolean) {
    var checked by remember { mutableStateOf(startsOn) }
    AppSwitch(
        checked = checked,
        onCheckedChange = { checked = it },
        contentDescription = if (startsOn) "Sample switch, starts on" else "Sample switch, starts off",
    )
}
