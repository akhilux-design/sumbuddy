package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppInputState
import com.expenses.app.ui.theme.Space

/** Width of one variant in design/spec/components/Input.png. */
private val InputDesignWidth = 312.dp

/**
 * Every state of [AppInput] with the sample text of the design, top to bottom in the order of
 * design/spec/components/Input.png: Default, Focused, Error. A fourth, empty field follows to show the
 * placeholder, which the design picture does not have. The fields are real: tap one to type in it.
 */
val appInputPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "input") {
        Column(
            modifier = Modifier.width(InputDesignWidth),
            verticalArrangement = Arrangement.spacedBy(Space.s16),
        ) {
            AppInputState.entries.forEach { state -> PhoneNumberInput(state = state) }
            EmptySearchInput()
        }
    },
)

/** The design's sample field, "Phone number" holding "98765 43210", in the given [state]. */
@Composable
private fun PhoneNumberInput(state: AppInputState) {
    var value by remember { mutableStateOf("98765 43210") }
    AppInput(
        value = value,
        onValueChange = { value = it },
        label = "Phone number",
        helper = "That does not look right.",
        state = state,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
}

/** An empty field that shows its placeholder, with the text of the search field on the Split with screen. */
@Composable
private fun EmptySearchInput() {
    var value by remember { mutableStateOf("") }
    AppInput(
        value = value,
        onValueChange = { value = it },
        label = "Search",
        placeholder = "Name or phone number",
    )
}
