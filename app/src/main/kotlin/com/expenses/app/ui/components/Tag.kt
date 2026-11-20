package com.expenses.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.theme.AppColors
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/** Colour scheme of a [Tag]. One entry per Figma `Tone` variant. The label, not the tone, carries the meaning. */
enum class TagTone {
    /** Dark green badge with green text, for things the app did by itself ("Auto"). */
    Brand,

    /** Solid yellow badge with dark text, for things that want attention ("Payment detected", "Check this"). */
    Yellow,

    /** Solid purple badge with dark text. */
    Purple,

    /** Soft blue badge with blue text, for where something came from ("From SMS"). */
    Blue,

    /** Soft red badge with red text, for problems. */
    Negative,

    /** Grey badge with grey text, for plain facts such as a group name. */
    Neutral,
}

/**
 * A small badge with a short label in capital letters. It only informs and cannot be tapped.
 *
 * The badge is as wide as its label plus padding, 24dp high at the standard font size, and grows with the
 * phone's font size. It has the same size in every tone.
 *
 * @param label text of the badge, for example "Auto". Write it in normal case: the tag shows it in capitals.
 * One line.
 * @param modifier layout changes from the caller, applied to the badge.
 * @param tone colour scheme; see [TagTone].
 */
@Composable
fun Tag(
    label: String,
    modifier: Modifier = Modifier,
    tone: TagTone = TagTone.Brand,
) {
    val colors = AppTheme.colors
    val style = AppTheme.type.label
    Box(
        modifier = modifier
            .background(colors.fillFor(tone), RoundedCornerShape(Radius.sm))
            .padding(horizontal = Space.s8, vertical = Space.s4),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = label.uppercase(),
            style = style,
            // The box is the 16dp Label line, so the badge is 24dp high. Screen readers get the label as written,
            // not the capitals.
            modifier = Modifier
                .semantics { contentDescription = label },
            color = colors.labelFor(tone),
            maxLines = 1,
        )
    }
}

/** Background colour of the badge for each tone. */
private fun AppColors.fillFor(tone: TagTone): Color = when (tone) {
    TagTone.Brand -> brandSoft
    TagTone.Yellow -> accentYellow
    TagTone.Purple -> accentPurple
    TagTone.Blue -> accentBlueSoft
    TagTone.Negative -> statusNegativeSoft
    TagTone.Neutral -> bgSurfaceRaised
}

/** Colour of the label for each tone. */
private fun AppColors.labelFor(tone: TagTone): Color = when (tone) {
    TagTone.Brand -> textBrand
    TagTone.Yellow -> textOnBrand
    TagTone.Purple -> textOnBrand
    TagTone.Blue -> textInfo
    TagTone.Negative -> statusNegative
    TagTone.Neutral -> textSecondary
}
