package com.expenses.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/** What the number in a [StatTile] means. It only sets the colour of the number. */
enum class StatTileTone {
    /** Just a number, for example what was spent this month. The number is in the normal text colour. */
    Neutral,

    /** Money coming to the user, for example "Owed to you". The number is green. */
    Positive,

    /** Money the user has to pay, for example "You owe". The number is red. */
    Negative,
}

/**
 * A small tile that shows one number with a short label above it, for example "SPENT IN NOV" over "₹18,240".
 * The tile is flat (no raised edge) and only as wide as its text unless the caller sizes it: give it
 * `Modifier.weight(1f)` in a row of tiles, or `Modifier.fillMaxWidth()` when it stands alone. It grows taller
 * when the label or the number wraps.
 *
 * The tone changes only the colour of the number, so the label must say what the number means
 * ("Owed to you", "You owe").
 *
 * @param label what the number is, in normal writing ("Spent in Nov"). It is drawn in capitals; screen readers
 * get it as written here.
 * @param value the number, already formatted, for example "₹18,240".
 * @param modifier layout changes from the caller, applied to the whole tile.
 * @param tone neutral, positive or negative; see [StatTileTone].
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: StatTileTone = StatTileTone.Neutral,
) {
    Column(
        modifier = modifier
            .background(AppTheme.colors.bgSurface, RoundedCornerShape(Radius.lg))
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Space.s16, vertical = Space.s12),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        val type = AppTheme.type
        AppText(
            text = label.uppercase(),
            style = type.label,
            modifier = Modifier
                .semantics { contentDescription = label },
            color = AppTheme.colors.textSecondary,
        )
        AppText(
            text = value,
            style = type.headingL,
                        color = valueColor(tone),
        )
    }
}

/** Measure passes of [StatTilePair]: each tile is measured at its own height, then laid out at the taller one. */
private enum class StatTilePairSlot { FirstNatural, SecondNatural, First, Second }

/**
 * Two [StatTile]s side by side in equal widths, 12dp apart, both as tall as the taller one, as on Home and Activity.
 * When one label wraps at a large font size the other tile grows with it. Unlike a row sized from intrinsic
 * heights, it measures each tile at its real height, so no text is squeezed into a box shorter than it needs.
 *
 * @param first the tile on the left (on the right in right-to-left languages). Do not size it: the pair does.
 * @param second the other tile. Do not size it either.
 * @param modifier outer modifier. The pair fills the width it is given.
 */
@Composable
fun StatTilePair(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    SubcomposeLayout(modifier = modifier.fillMaxWidth()) { constraints ->
        val gap = Space.s12.roundToPx()
        val firstWidth = ((constraints.maxWidth - gap) / 2).coerceAtLeast(0)
        val secondWidth = (constraints.maxWidth - gap - firstWidth).coerceAtLeast(0)
        fun naturalHeight(slot: StatTilePairSlot, width: Int, content: @Composable () -> Unit): Int =
            subcompose(slot, content).maxOfOrNull { it.measure(Constraints.fixedWidth(width)).height } ?: 0
        val height = maxOf(
            naturalHeight(StatTilePairSlot.FirstNatural, firstWidth, first),
            naturalHeight(StatTilePairSlot.SecondNatural, secondWidth, second),
        )
        val firstPlaceables = subcompose(StatTilePairSlot.First, first)
            .map { it.measure(Constraints.fixed(firstWidth, height)) }
        val secondPlaceables = subcompose(StatTilePairSlot.Second, second)
            .map { it.measure(Constraints.fixed(secondWidth, height)) }
        layout(constraints.maxWidth, height) {
            firstPlaceables.forEach { it.placeRelative(0, 0) }
            secondPlaceables.forEach { it.placeRelative(firstWidth + gap, 0) }
        }
    }
}

/** Colour of the number for each tone. */
@Composable
private fun valueColor(tone: StatTileTone): Color = when (tone) {
    StatTileTone.Neutral -> AppTheme.colors.textPrimary
    StatTileTone.Positive -> AppTheme.colors.statusPositive
    StatTileTone.Negative -> AppTheme.colors.statusNegative
}
