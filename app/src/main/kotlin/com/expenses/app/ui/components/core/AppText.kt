package com.expenses.app.ui.components.core

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.expenses.app.ui.theme.AppTheme

/**
 * Text in one of the app's text styles. Always pass a style from `AppTheme.type` and a colour from `AppTheme.colors`.
 * Text scales with the phone's font size setting, so never put it in a fixed-height box.
 * Each line takes exactly the style's line height, as in the design (see [designLineHeight]).
 */
@Composable
fun AppText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.textPrimary,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    BasicText(
        text = text,
        modifier = modifier.designLineHeight(style),
        style = style.copy(color = color, textAlign = textAlign),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** Same as [AppText] for text with mixed colours or weights inside one paragraph. */
@Composable
fun AppText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.textPrimary,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    BasicText(
        text = text,
        modifier = modifier.designLineHeight(style),
        style = style.copy(color = color, textAlign = textAlign),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/**
 * Makes a text box exactly as tall as the design draws it: the line height of [style] for each line.
 *
 * Compose never makes a line shorter than the font's own height, so a style whose line height is smaller than
 * the font (Label is 14sp text on a 16sp line) measures taller than in the design: one line of Label is about
 * 18.7dp instead of 16. Figma keeps the line box and lets the glyphs reach past it. This does the same: the box
 * becomes the number of lines times the line height, the text is centred in it and nothing is clipped. Styles
 * whose line height is already taller than the font are left as they are.
 *
 * [AppText] applies it to all text, so components and screens never call it themselves.
 *
 * @param style the text style the text is drawn in; only its line height is used.
 */
internal fun Modifier.designLineHeight(style: TextStyle): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val lineHeight = style.lineHeight.roundToPx()
    // The font only adds a few dp to the first and the last line, far less than a whole line, so the number of
    // whole lines that fit is the number of lines of text.
    val lines = if (lineHeight > 0) placeable.height / lineHeight else 0
    val height = if (lines > 0) {
        (lines * lineHeight).coerceIn(constraints.minHeight, constraints.maxHeight)
    } else {
        placeable.height
    }
    layout(placeable.width, height) {
        placeable.place(0, (height - placeable.height) / 2)
    }
}
