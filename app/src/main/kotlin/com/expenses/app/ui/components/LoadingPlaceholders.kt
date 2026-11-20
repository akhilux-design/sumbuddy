package com.expenses.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

// The grey placeholders of 02-everyday/22-groups-loading: where a Balance Card and Balance Rows will be. The Groups
// tab and the Group page show them while their data loads.
//
// Motion (design/spec/motion.md, "Placeholder pulse"): a placeholder is in the tree at once (screen readers and tests
// find it), but shows only if loading takes longer than 200 ms: it then fades in over 150 ms. Its bars pulse from full
// to 55% and back, 900 ms each way; placeholders drawn in the same frame pulse together. It is the only loop in the app,
// and it stays still with Remove animations on.

/** Placeholder sizes from the loading design. */
private val PlaceholderLabelWidth = 150.dp
private val PlaceholderAmountWidth = 180.dp
private val PlaceholderCaptionWidth = 120.dp
private val PlaceholderLineHeight = 12.dp
private val PlaceholderAmountHeight = 32.dp
private val PlaceholderAvatarSize = 44.dp
private val PlaceholderTitleWidth = 140.dp
private val PlaceholderDetailWidth = 90.dp
private val PlaceholderDetailHeight = 10.dp

/** How many placeholder rows the design draws under the card. */
const val PlaceholderRowCount: Int = 3

/**
 * Stand-in for a Balance Card: label, amount and caption bars on a surface card with radius 24. It is the one item
 * screen readers hear while the screen loads.
 *
 * @param description what screen readers say, for example "Loading groups".
 * @param modifier layout changes from the caller.
 */
@Composable
fun PlaceholderCard(description: String, modifier: Modifier = Modifier) {
    val motion = rememberPlaceholderMotion()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = motion.shown.value }
            .clearAndSetSemantics { contentDescription = description }
            .background(AppTheme.colors.bgSurface, RoundedCornerShape(Radius.xl))
            .padding(Space.s16),
        verticalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        PlaceholderBar(PlaceholderLabelWidth, PlaceholderLineHeight, motion.pulse)
        PlaceholderBar(PlaceholderAmountWidth, PlaceholderAmountHeight, motion.pulse)
        PlaceholderBar(PlaceholderCaptionWidth, PlaceholderLineHeight, motion.pulse)
    }
}

/** Stand-in for a Balance Row: a rounded square and two bars on a surface row with radius 16. Silent for screen readers. */
@Composable
fun PlaceholderRow(modifier: Modifier = Modifier) {
    val motion = rememberPlaceholderMotion()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = motion.shown.value }
            .clearAndSetSemantics {}
            .background(AppTheme.colors.bgSurface, RoundedCornerShape(Radius.lg))
            .padding(Space.s12),
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(PlaceholderAvatarSize)
                .graphicsLayer { alpha = motion.pulse.value }
                .background(AppTheme.colors.bgSurfaceRaised, RoundedCornerShape(Radius.md)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            PlaceholderBar(PlaceholderTitleWidth, PlaceholderLineHeight, motion.pulse)
            PlaceholderBar(PlaceholderDetailWidth, PlaceholderDetailHeight, motion.pulse)
        }
    }
}

/** Placeholder tile sizes (06-family/06-home-family-month-loading). */
private val PlaceholderTileLabelWidth = 72.dp
private val PlaceholderTileValueWidth = 96.dp
private val PlaceholderTileValueHeight = 24.dp

/**
 * Stand-in for a Stat Tile (Home's Top categories while the family month loads, 06-family/06): a short label bar and a
 * value bar, 8dp apart, on a surface tile with radius 16 and the tile's 12 / 16 padding. Silent for screen readers.
 */
@Composable
fun PlaceholderTile(modifier: Modifier = Modifier) {
    val motion = rememberPlaceholderMotion()
    Column(
        modifier = modifier
            .graphicsLayer { alpha = motion.shown.value }
            .clearAndSetSemantics {}
            .background(AppTheme.colors.bgSurface, RoundedCornerShape(Radius.lg))
            .padding(horizontal = Space.s16, vertical = Space.s12),
        verticalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        PlaceholderBar(PlaceholderTileLabelWidth, PlaceholderLineHeight, motion.pulse)
        PlaceholderBar(PlaceholderTileValueWidth, PlaceholderTileValueHeight, motion.pulse)
    }
}

/** One grey pill-ended bar of a placeholder, at the [pulse]'s alpha. */
@Composable
private fun PlaceholderBar(width: Dp, height: Dp, pulse: State<Float>) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .graphicsLayer { alpha = pulse.value }
            .background(AppTheme.colors.bgSurfaceRaised, RoundedCornerShape(Radius.full)),
    )
}

/** A placeholder's motion: [shown] is its alpha as it fades in, [pulse] its bars' alpha. Read both in the layer only. */
internal class PlaceholderMotion(val shown: State<Float>, val pulse: State<Float>)

/**
 * The fade-in (after 200 ms, over 150 ms) and the pulse (1 to 0.55 and back, 900 ms each way) of one placeholder. With
 * Remove animations on it is shown at once and still.
 */
@Composable
internal fun rememberPlaceholderMotion(): PlaceholderMotion {
    val reduced = LocalReducedMotion.current
    val shown = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (reduced) {
            shown.snapTo(1f)
        } else {
            shown.animateTo(1f, Motion.fade(Motion.RevealFade.InMillis, delayMillis = Motion.PlaceholderDelayMillis))
        }
    }
    val pulse = if (reduced) {
        remember { mutableFloatStateOf(1f) }
    } else {
        rememberInfiniteTransition(label = "placeholder").animateFloat(
            initialValue = 1f,
            targetValue = Motion.PlaceholderPulseAlpha,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.PlaceholderPulseMillis, easing = Motion.Easings.Standard),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
    }
    return remember(shown, pulse) { PlaceholderMotion(shown.asState(), pulse) }
}
