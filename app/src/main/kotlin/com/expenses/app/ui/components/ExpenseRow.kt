package com.expenses.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.core.IconBubble
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/** Which sort of expense an [ExpenseRow] shows. It sets the colour of the note under the amount and the outline. */
enum class ExpenseRowKind {
    /** An expense that is only the user's own. The note is grey. */
    Personal,

    /** An expense split with other people. The note is in the brand green. */
    Shared,

    /**
     * A payment the app could not read fully and the user should check. The note is yellow and the row has a
     * yellow outline.
     */
    Review,
}

/** Width of the yellow outline on a [ExpenseRowKind.Review] row. It sits inside the row and pushes the content in. */
private val ExpenseRowReviewStroke = 1.5.dp

/**
 * One expense in a list: a 3D icon in a bubble, a title with a subtitle, and on the right the amount with a
 * short note under it. The row is flat (no raised edge), fills the width it is given and grows taller when the
 * title or subtitle wraps.
 *
 * The kind is never shown by colour alone: the note text says it ("Just you", "Your share", "Check this") and a
 * Review row also has an outline.
 *
 * @param title main line, for example the merchant ("Swiggy"). It wraps when long; a UPI ID ("rahul.kumar@okaxis")
 * breaks only after its "@" (see [FlatRow]).
 * @param subtitle second line under the title, for example "Food · HDFC card ••4521". Wraps when it is long.
 * @param amount the amount, already formatted, for example "₹450". Always shown whole on one line.
 * @param meta short note under the amount, for example "Just you" or a group name. One line in the design; a
 * note that would take more than half of the space beside the icon wraps onto a second line instead.
 * @param modifier layout changes from the caller, applied to the whole row.
 * @param icon the 3D icon in the bubble at the start.
 * @param kind personal, shared or needs review; see [ExpenseRowKind].
 * @param onClick called when the row is tapped. Leave null for a row that cannot be tapped.
 */
@Composable
fun ExpenseRow(
    title: String,
    subtitle: String,
    amount: String,
    meta: String,
    modifier: Modifier = Modifier,
    icon: AppIcon = AppIcon.Receipt,
    kind: ExpenseRowKind = ExpenseRowKind.Personal,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Radius.lg)
    FlatRow(
        title = title,
        subtitle = subtitle,
        leading = { IconBubble(icon = icon) },
        modifier = modifier
            .flatRowSurface(AppTheme.colors.bgSurface)
            .rowClick(onClick)
            .then(
                if (kind == ExpenseRowKind.Review) {
                    Modifier
                        .border(ExpenseRowReviewStroke, AppTheme.colors.accentYellow, shape)
                        .padding(ExpenseRowReviewStroke)
                } else {
                    Modifier
                },
            )
            .padding(Space.s12),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s2), horizontalAlignment = Alignment.End) {
            AppText(text = amount, style = AppTheme.type.amountM, color = AppTheme.colors.textPrimary, maxLines = 1)
            AppText(
                text = meta,
                style = AppTheme.type.caption,
                modifier = Modifier.atMostHalfWidth(),
                color = metaColor(kind),
                textAlign = TextAlign.End,
                maxLines = 2,
            )
        }
    }
}

/** Colour of the note under the amount for each kind. */
@Composable
private fun metaColor(kind: ExpenseRowKind): Color = when (kind) {
    ExpenseRowKind.Personal -> AppTheme.colors.textTertiary
    ExpenseRowKind.Shared -> AppTheme.colors.textBrand
    ExpenseRowKind.Review -> AppTheme.colors.textWarning
}

/**
 * Lets the note use at most half of the width on offer, so a long group name or a large font setting cannot push
 * the title out of the row. Notes of the length used in the design are far below this and are not affected.
 */
private fun Modifier.atMostHalfWidth(): Modifier = layout { measurable, constraints ->
    val widest = if (constraints.hasBoundedWidth) constraints.maxWidth / 2 else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = widest))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}
