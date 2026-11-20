package com.expenses.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.expenses.app.ui.components.core.IconBubble
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.Space

/**
 * A row for a setting, a linked account or one choice in a list: a 3D icon in a bubble, a title with a subtitle,
 * and an optional control at the end (in the design, a switch). The row is flat (no raised edge), fills the width
 * it is given and grows taller when the title or subtitle wraps.
 *
 * The row can be used in three ways:
 * - **A switch row** (accounts, "Do this every time"): pass [checked] and [onCheckedChange], and put
 *   `AppSwitch(checked = checked, onCheckedChange = null)` in [trailing]. The whole row is then one switch: a tap
 *   anywhere on it flips the setting, and a screen reader reads the title, the subtitle and "on" or "off"
 *   together. The switch in [trailing] is only the picture of the state, so it needs no handler or description
 *   of its own.
 * - **A row that opens or picks something** (the category list): pass [onClick]. The whole row is one button.
 * - **A row that only shows something** (a chosen file, a group member): pass neither. A control placed in
 *   [trailing] then handles its own taps and must bring its own role and description.
 *
 * When both are given, [checked] with [onCheckedChange] wins over [onClick].
 *
 * While pressed, a tappable row shows the flat pressed overlay over the whole row, switch included. A switch row
 * plays `haptic/toggle-on` or `haptic/toggle-off` when tapped (`design/spec/motion.md`).
 *
 * @param title main line, for example "HDFC Bank".
 * @param subtitle second line under the title, for example "Savings ••4521". Wraps when it is long.
 * @param modifier layout changes from the caller, applied to the whole row.
 * @param icon the 3D icon in the bubble at the start.
 * @param onClick called when the row is tapped. Leave null for a row that is not a button.
 * @param checked whether the setting is on. Only used together with [onCheckedChange]; leave null for a row
 * without a switch.
 * @param onCheckedChange called with the new state when a switch row is tapped. Leave null for a row without a
 * switch.
 * @param trailing what is drawn at the end of the row, centred vertically: the 52 x 32 switch in the design, or
 * another small mark such as a chevron. Leave null for a row with nothing at the end, as in the category list.
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: AppIcon = AppIcon.Receipt,
    onClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val isSwitchRow = checked != null && onCheckedChange != null
    val haptics = LocalAppHaptics.current
    FlatRow(
        title = title,
        subtitle = subtitle,
        leading = { IconBubble(icon = icon) },
        modifier = modifier
            .flatRowSurface(AppTheme.colors.bgSurface)
            .then(
                if (checked != null && onCheckedChange != null) {
                    // The row owns the tap, so it plays the toggle haptic; the drawn switch has no handler.
                    Modifier.toggleable(
                        value = checked,
                        role = Role.Switch,
                        onValueChange = { on ->
                            haptics.toggle(on)
                            onCheckedChange(on)
                        },
                    )
                } else {
                    Modifier.rowClick(onClick)
                },
            )
            .padding(Space.s12),
        trailing = trailing?.let { content ->
            {
                if (isSwitchRow) {
                    // The row already says "switch, on/off" for screen readers; the drawn switch must not say it
                    // twice.
                    Box(modifier = Modifier.clearAndSetSemantics {}) { content() }
                } else {
                    content()
                }
            }
        },
    )
}
