package com.expenses.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.readAs
import com.expenses.app.ui.format.upiIdLineBreaks
import com.expenses.app.ui.format.withoutUpiIdLineBreaks
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

// The skeleton that Balance Row, Expense Row, Setting Row and Info Row share.

/**
 * The inside of a flat list row: something at the start, a title with a second line under it (2dp apart) that
 * takes the room left over, and something at the end, 12dp apart and centred vertically. It draws no background,
 * padding or touch handling of its own: pass those in [modifier], usually [flatRowSurface], [rowClick] and a
 * 12dp padding.
 *
 * @param title main line, in `Title` and `color/text/primary`. Wraps when it is long; a UPI ID
 * ("rahul.kumar@okaxis") breaks only after its "@" ([upiIdLineBreaks]), never in the middle, and is never cut off.
 * @param subtitle second line, in [subtitleStyle] and `color/text/secondary`. Wraps when it is long. A sentence that
 * names a payee by its UPI ID is passed with the ID through [upiIdLineBreaks] ("rahul.kumar@okaxis is always Food"),
 * so the ID breaks only after its "@"; screen readers read the sentence without the marks.
 * @param leading what sits at the start: an icon bubble or an avatar.
 * @param modifier the row's background, touch handling and padding.
 * @param subtitleStyle text style of the second line: `Caption` in most rows, `Body/M` in Info Row.
 * @param trailing what sits at the end, or null for nothing.
 */
@Composable
internal fun FlatRow(
    title: String,
    subtitle: String,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitleStyle: TextStyle = AppTheme.type.caption,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            // A payee or account known only by its UPI ID is shown whole and breaks only after its "@"
            // ("rahul.kumar@" / "okaxis"), never in the middle; screen readers read the plain ID.
            val shownTitle = upiIdLineBreaks(title)
            AppText(
                text = shownTitle,
                style = AppTheme.type.title,
                modifier = if (shownTitle != title) Modifier.readAs(title) else Modifier,
                color = AppTheme.colors.textPrimary,
            )
            // A sentence that holds a UPI ID comes with the ID's line break marks; screen readers read it without.
            val spokenSubtitle = withoutUpiIdLineBreaks(subtitle)
            AppText(
                text = subtitle,
                style = subtitleStyle,
                modifier = if (spokenSubtitle != subtitle) Modifier.readAs(spokenSubtitle) else Modifier,
                color = AppTheme.colors.textSecondary,
            )
        }
        if (trailing != null) trailing()
    }
}

/**
 * The card look of a flat row: the full width it is given, radius 16, filled with [color]. Put it before the
 * touch handling, so the press feedback is clipped to the rounded shape.
 *
 * @param color the fill, `color/bg/surface` in every row of the design.
 */
internal fun Modifier.flatRowSurface(color: Color): Modifier =
    fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(color)

/**
 * How a row reacts to touch: a button when [onClick] is given; otherwise not tappable, and read by screen readers
 * as one item.
 *
 * @param onClick called when the row is tapped, or null for a row that only shows something.
 */
internal fun Modifier.rowClick(onClick: (() -> Unit)?): Modifier = if (onClick != null) {
    clickable(role = Role.Button, onClick = onClick)
} else {
    semantics(mergeDescendants = true) {}
}
