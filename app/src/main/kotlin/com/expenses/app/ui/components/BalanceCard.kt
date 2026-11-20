package com.expenses.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.core.RaisedSurface
import com.expenses.app.ui.components.core.designLineHeight
import com.expenses.app.ui.motion.FadeThroughContent
import com.expenses.app.ui.theme.AppColors
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/**
 * The colour of a [BalanceCard] (Figma variant `Tone`). The card's label always says in words what the amount
 * means, so the tone is never the only signal.
 */
enum class BalanceCardTone {
    /** Green card: money the user is owed, or the month's total. The default. */
    Positive,

    /** Pink card: money the user owes. */
    Negative,

    /** Grey card: nothing owed either way. */
    Neutral,
}

/**
 * The large summary card at the top of Home, Groups and a group's page: a small upper-case label,
 * one big amount and a line of detail under it, on a raised coloured face.
 *
 * The card fills the width it is given and grows taller when the label or caption wraps. The amount always
 * stays on one line and whole: if it is too long for the card at full size it is drawn smaller, never cut.
 *
 * @param label what the amount is, for example "Spent in November". Shown in upper case.
 * @param amount the amount as it should be shown, for example "₹18,240" (see `formatRupees`).
 * @param caption the detail under the amount, for example "from 4 friends".
 * @param modifier outer modifier. The 5dp band under the card is part of the card's height.
 * @param tone which of the three colours to use.
 * @param onClick called when the card is tapped, or null when the card does nothing on tap.
 * @param fadeKey what the card shows, for example Home's Me or Family view. When it changes, the label, amount and
 * caption fade through (`design/spec/motion.md`, "Fade-through") while the face stays; null (the default) swaps them
 * in place.
 */
@Composable
fun BalanceCard(
    label: String,
    amount: String,
    caption: String,
    modifier: Modifier = Modifier,
    tone: BalanceCardTone = BalanceCardTone.Positive,
    onClick: (() -> Unit)? = null,
    fadeKey: Any? = null,
) {
    val colors = tone.colors(AppTheme.colors)
    // A tappable card is already read as one item. A card that is not tappable is grouped here,
    // so a screen reader says label, amount and caption together.
    val grouping = if (onClick == null) Modifier.semantics(mergeDescendants = true) {} else Modifier
    RaisedSurface(
        faceColor = colors.face,
        edgeColor = colors.edge,
        modifier = modifier.fillMaxWidth().then(grouping),
        cornerRadius = Radius.xl,
        onClick = onClick,
        role = Role.Button,
        contentAlignment = Alignment.TopStart,
    ) {
        val text = BalanceCardText(fadeKey, label, amount, caption)
        if (fadeKey == null) {
            BalanceCardContent(text, colors.content)
        } else {
            FadeThroughContent(targetState = text, contentKey = { it.key }) { shown ->
                BalanceCardContent(shown, colors.content)
            }
        }
    }
}

/** What a [BalanceCard] says, with the key its fade-through follows. */
@Immutable
private data class BalanceCardText(val key: Any?, val label: String, val amount: String, val caption: String)

/** The label, amount and caption on the card's face. */
@Composable
private fun BalanceCardContent(text: BalanceCardText, color: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(Space.s20),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        AppText(
            text = text.label.uppercase(),
            style = AppTheme.type.label,
            color = color,
            // Screen readers get the label as it was written, not the capitals.
            modifier = Modifier
                .semantics { contentDescription = text.label },
        )
        BalanceAmount(amount = text.amount, color = color)
        AppText(text = text.caption, style = AppTheme.type.bodyMStrong, color = color)
    }
}

/**
 * The big amount in `Amount/XL`. It keeps to one line: when the amount is too wide for the card (a large sum,
 * or a large font size setting) the text is drawn smaller, down to the `Heading/L` size, so no digit is lost.
 */
@Composable
private fun BalanceAmount(amount: String, color: Color) {
    val type = AppTheme.type
    BasicText(
        text = amount,
        // The box is the 56dp Amount/XL line, as in the design, at every step of the size.
        modifier = Modifier.designLineHeight(type.amountXL),
        style = type.amountXL.copy(color = color),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = type.headingL.fontSize,
            maxFontSize = type.amountXL.fontSize,
        ),
    )
}

/** The three colours one tone needs: the face, the band under it, and the text on the face. */
@Immutable
private class BalanceCardColors(val face: Color, val edge: Color, val content: Color)

private fun BalanceCardTone.colors(theme: AppColors): BalanceCardColors = when (this) {
    BalanceCardTone.Positive -> BalanceCardColors(theme.brandPrimary, theme.brandEdge, theme.textOnBrand)
    BalanceCardTone.Negative -> BalanceCardColors(theme.accentPink, theme.accentPinkEdge, theme.textOnBrand)
    BalanceCardTone.Neutral -> BalanceCardColors(theme.bgSurfaceRaised, theme.edgeNeutral, theme.textPrimary)
}
