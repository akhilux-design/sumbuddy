package com.expenses.app.ui.motion

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.expenses.app.ui.components.core.designLineHeight
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.motion

/**
 * A colour that changes over 150 ms (`motion/duration/short`, a tween: fades and colour changes are tweens, never
 * springs). It snaps when Remove animations is on, and does not animate on the first composition. Read the value in a
 * draw lambda or pass the state to [FadingColorText], never in composition (`docs/motion.md` 3.8).
 */
@Composable
fun fadingColor(target: Color, label: String = "colour"): State<Color> =
    animateColorAsState(targetValue = target, animationSpec = motion(Motion.fade(Motion.Duration.Short)), label = label)

/** An alpha (0 to 1) that changes over 150 ms, like [fadingColor]. Read it in a draw or layer lambda. */
@Composable
fun fadingAlpha(target: Float, label: String = "alpha"): State<Float> =
    animateFloatAsState(targetValue = target, animationSpec = motion(Motion.fade(Motion.Duration.Short)), label = label)

/**
 * The same text as `AppText` (the style's design line height), whose colour follows [color] in the draw phase only:
 * a colour change over 150 ms does not recompose the text every frame.
 */
@Composable
fun FadingColorText(
    text: String,
    style: TextStyle,
    color: State<Color>,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier.designLineHeight(style),
        style = style.copy(textAlign = textAlign),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
        color = { color.value },
    )
}
