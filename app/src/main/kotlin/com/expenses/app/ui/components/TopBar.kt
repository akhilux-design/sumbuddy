package com.expenses.app.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.expenses.app.R
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.TintedIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/** Height of the bar in the design: 12 padding, the 40 back button, 12 padding. Kept when the button is hidden. */
private val TopBarMinHeight = 64.dp

/** Diameter of the round back button. */
private val TopBarBackCircle = 40.dp

/** Size of the arrow inside the back button. */
private val TopBarBackIcon = 20.dp

/** Smallest touch area of the back button and of the action. */
private val TopBarTouchTarget = 48.dp

/** How far the touch area of the back button reaches past its circle on each side: 4 + 40 + 4 = 48. */
private val TopBarBackOverhang = (TopBarTouchTarget - TopBarBackCircle) / 2

/** How far the touch area of the action reaches past its text to the left and right. Equal to the gap to the title. */
private val TopBarActionOverhangX = Space.s12

/** How far the touch area of the action reaches past its text above and below: 14 + the 20 line + 14 = 48. */
private val TopBarActionOverhangY = 14.dp

/**
 * The header of a screen: a round back button on the left, the screen title, and one text action on the right.
 * The bar has no background, border or shadow of its own, so the screen colour shows through.
 *
 * @param title name of the screen. Screen readers announce it as a heading. A long title wraps onto a second line.
 * @param modifier outer modifier. The bar always fills the width it is given.
 * @param onBack called when the back button is tapped. Null hides the back button.
 * @param backDescription what a screen reader says for the back button, which has no text of its own. By default
 * the shared "Back" string resource.
 * @param actionLabel text of the action on the right, for example "Edit". Null hides the action.
 * @param onAction called when the action is tapped. Null hides the action.
 */
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backDescription: String = stringResource(R.string.shell_back),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TopBarMinHeight)
            .padding(horizontal = Space.s16, vertical = Space.s12),
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TopBarBackButton(description = backDescription, onClick = onBack)
        }
        AppText(
            text = title,
            style = AppTheme.type.headingM,
            modifier = Modifier.weight(1f).semantics { heading() },
            color = AppTheme.colors.textPrimary,
            maxLines = 2,
        )
        if (actionLabel != null && onAction != null) {
            TopBarAction(label = actionLabel, onClick = onAction)
        }
    }
}

/**
 * The round back button: a 40dp circle with the arrow, inside a 48dp touch area.
 *
 * @param description what a screen reader says for the button.
 * @param onClick called when the button is tapped.
 */
@Composable
private fun TopBarBackButton(description: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .touchOverhang(horizontal = TopBarBackOverhang, vertical = TopBarBackOverhang)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(TopBarBackOverhang),
    ) {
        Box(
            modifier = Modifier
                .size(TopBarBackCircle)
                .clip(CircleShape)
                .background(AppTheme.colors.bgSurfaceRaised)
                .indication(interactionSource, LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) {
            TintedIcon(id = R.drawable.ic_back, size = TopBarBackIcon, tint = AppTheme.colors.textPrimary)
        }
    }
}

/**
 * The text action on the right of the bar. The text sits where the design puts it; the touch area around it is
 * at least 48dp in both directions.
 *
 * @param label text of the action.
 * @param onClick called when the action is tapped.
 */
@Composable
private fun TopBarAction(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .touchOverhang(horizontal = TopBarActionOverhangX, vertical = TopBarActionOverhangY)
            .defaultMinSize(minWidth = TopBarTouchTarget, minHeight = TopBarTouchTarget)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .clip(RoundedCornerShape(Radius.full))
            .indication(interactionSource, LocalIndication.current)
            .padding(horizontal = TopBarActionOverhangX, vertical = TopBarActionOverhangY),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = label,
            style = AppTheme.type.bodyMStrong,
            color = AppTheme.colors.textBrand,
            maxLines = 1,
        )
    }
}
