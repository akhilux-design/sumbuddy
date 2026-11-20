package com.expenses.app.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.components.core.IconBubble
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/** How far a tappable line's touch area reaches past it above and below: 2 + the 44dp line + 2 = 48. */
private val InfoRowTouchOverhang = 2.dp

/**
 * A plain information line: a 3D icon in a bubble, a title and an explanation under it. It has no background,
 * padding, corner radius or control of its own, so it sits directly on the screen or inside a card. It fills the
 * width it is given and grows taller when the title or the explanation wraps.
 *
 * Most of these lines only inform ("Read on your phone only"). In the list cards of the You, Account and Group
 * settings screens the same line is a link ("What we've read", "Sign out"): pass [onClick] for those. The line
 * keeps its 44dp height from the design; its touch area reaches 2dp past it above and below, so it is 48dp
 * without moving anything. Keep at least 4dp between two tappable lines so that these areas do not meet (the
 * design uses 12dp).
 *
 * @param title main line, for example "Read on your phone only".
 * @param subtitle the explanation under the title, for example "Nothing is uploaded, ever.". Wraps when it is long.
 * @param modifier layout changes from the caller, applied to the whole line.
 * @param icon the 3D icon in the bubble at the start.
 * @param onClick called when the line is tapped. Leave null for a line that only informs.
 */
@Composable
fun InfoRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: AppIcon = AppIcon.Receipt,
    onClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    FlatRow(
        title = title,
        subtitle = subtitle,
        leading = { IconBubble(icon = icon) },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .touchOverhang(horizontal = Space.s0, vertical = InfoRowTouchOverhang)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                        )
                        .padding(vertical = InfoRowTouchOverhang)
                        .indication(interactionSource, LocalIndication.current)
                } else {
                    Modifier.rowClick(onClick = null)
                },
            ),
        subtitleStyle = AppTheme.type.bodyM,
    )
}
