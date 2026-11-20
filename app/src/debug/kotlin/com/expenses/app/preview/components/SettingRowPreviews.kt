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
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space

/**
 * [SettingRow] with the sample text of design/spec/components/Setting-Row.png first (the component has no
 * variants: a switch that is on), then the other ways the screens use it: the switch off, a subtitle that wraps
 * onto a second line, and a row with nothing at the end that is tapped as a whole. Tapping a switch row flips it.
 */
val settingRowPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "setting-row") { SettingRowVariants() },
)

@Composable
private fun SettingRowVariants() {
    var hdfc by remember { mutableStateOf(true) }
    var sbi by remember { mutableStateOf(false) }
    var reminders by remember { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
        SettingRow(
            title = "HDFC Bank",
            subtitle = "Savings ••4521",
            icon = AppIcon.Receipt,
            checked = hdfc,
            onCheckedChange = { hdfc = it },
            trailing = { AppSwitch(checked = hdfc, onCheckedChange = null) },
        )
        SettingRow(
            title = "SBI",
            subtitle = "Savings ••0034",
            icon = AppIcon.Bank,
            checked = sbi,
            onCheckedChange = { sbi = it },
            trailing = { AppSwitch(checked = sbi, onCheckedChange = null) },
        )
        SettingRow(
            title = "Automatic reminders",
            subtitle = "3 days before a card bill is due",
            icon = AppIcon.Clock,
            checked = reminders,
            onCheckedChange = { reminders = it },
            trailing = { AppSwitch(checked = reminders, onCheckedChange = null) },
        )
        SettingRow(
            title = "Food",
            subtitle = "Swiggy, Zomato, restaurants",
            icon = AppIcon.Food,
            onClick = {},
        )
    }
}
