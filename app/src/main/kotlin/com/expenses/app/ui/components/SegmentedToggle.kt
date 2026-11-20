package com.expenses.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.expenses.app.ui.components.core.EdgeHeight
import com.expenses.app.ui.motion.FadingColorText
import com.expenses.app.ui.motion.fadingColor
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space
import com.expenses.app.ui.theme.motion

/** Which option of a [SegmentedToggle] is chosen. The names are the options of the Figma property `Selected`. */
enum class SegmentedToggleSide {
    /** The first option, at the start of the track ("Me" in the design). */
    Left,

    /** The second option, at the end of the track ("Family" in the design). */
    Right,
}

private val ToggleTrackBorderWidth = 1.5.dp

/** How far an option's touch area reaches past it above and below: 4.5 + the 39dp option + 4.5 = 48. */
private val SegmentTouchOverhang = 4.5.dp

/** From the outer edge of the track to the options: 4 padding plus the 1.5 border, which Figma counts as layout. */
private val ToggleTrackInset = Space.s4 + ToggleTrackBorderWidth

/**
 * How much lower the label of the option that is not chosen sits: the track centres a 28dp segment against the
 * 33dp pill with its edge, so (33 - 28) / 2.
 */
private val UnselectedLabelDrop = EdgeHeight / 2

/** Where the two labels were placed, so the one pill can be drawn behind either, or between them while it slides. */
private class PillSlots {
    var track: LayoutCoordinates? = null
    var left: LayoutCoordinates? = null
    var right: LayoutCoordinates? = null
}

/**
 * A choice between two options inside one pill-shaped track, for example "Me" and "Family" on Home.
 *
 * The chosen option is a raised green pill with a darker edge under it; the other is plain grey text. The raised
 * shape shows the choice, so it does not depend on colour alone. The track is as wide as its two labels need and
 * the two options are not forced to the same width.
 *
 * Each option reacts to touches on its whole half of the track, padding included. The track is 44dp high as in
 * the design; each option's touch area reaches past it above and below to at least 48dp without moving anything.
 *
 * Motion (`design/spec/motion.md`, "Chip and toggle"): one raised pill is drawn behind both options. Choosing the
 * other option slides it there, its x and width following `motion/spring/pill` (a slight overshoot), keeping its
 * 5dp edge; the labels change colour over 150 ms. A tap mid-slide continues from where the pill is. Choosing the
 * other option plays `haptic/selection`; the chosen option does nothing, and does not press. While pressed, the
 * option that is not chosen shows the flat overlay on its label area.
 *
 * @param left label of the first option. This is the Figma property `Left`.
 * @param right label of the second option. This is the Figma property `Right`.
 * @param selected the option that is chosen now.
 * @param onSelectedChange called with the other option when the user taps it. Tapping the option that is
 * already chosen does nothing.
 * @param modifier layout changes from the caller, applied to the track.
 */
@Composable
fun SegmentedToggle(
    left: String,
    right: String,
    selected: SegmentedToggleSide,
    onSelectedChange: (SegmentedToggleSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(Radius.full)
    val slots = remember { PillSlots() }
    // 0 is the pill at the first option, 1 at the second. It is read only while drawing and placing.
    val pill by animateFloatAsState(
        targetValue = if (selected == SegmentedToggleSide.Right) 1f else 0f,
        animationSpec = motion(Motion.Spring.Pill.spec()),
        label = "segmentPill",
    )
    val faceColor = colors.brandPrimary
    val edgeColor = colors.brandEdge
    Row(
        modifier = modifier
            .onPlaced { slots.track = it }
            .background(colors.bgSurfaceRaised, shape)
            .border(ToggleTrackBorderWidth, colors.borderSubtle, shape)
            .drawBehind {
                val track = slots.track ?: return@drawBehind
                val left = slots.left ?: return@drawBehind
                val right = slots.right ?: return@drawBehind
                if (!track.isAttached || !left.isAttached || !right.isAttached) return@drawBehind
                val from = track.localBoundingBoxOf(left, clipBounds = false)
                val to = track.localBoundingBoxOf(right, clipBounds = false)
                val x = lerp(from.left, to.left, pill)
                val width = lerp(from.width, to.width, pill)
                val face = Size(width, from.height)
                val radius = CornerRadius(from.height / 2f, from.height / 2f)
                drawRoundRect(edgeColor, Offset(x, from.top + EdgeHeight.toPx()), face, radius)
                drawRoundRect(faceColor, Offset(x, from.top), face, radius)
            }
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment(
            label = left,
            selected = selected == SegmentedToggleSide.Left,
            onSelect = { onSelectedChange(SegmentedToggleSide.Left) },
            trackPadding = PaddingValues(start = ToggleTrackInset, top = ToggleTrackInset, bottom = ToggleTrackInset),
            onLabelPlaced = { slots.left = it },
            chosen = { (1f - pill).coerceIn(0f, 1f) },
        )
        Segment(
            label = right,
            selected = selected == SegmentedToggleSide.Right,
            onSelect = { onSelectedChange(SegmentedToggleSide.Right) },
            trackPadding = PaddingValues(top = ToggleTrackInset, end = ToggleTrackInset, bottom = ToggleTrackInset),
            onLabelPlaced = { slots.right = it },
            chosen = { pill.coerceIn(0f, 1f) },
        )
    }
}

/**
 * One option together with its share of the track's padding, so that touches on the padding count too. Every
 * option keeps the pill's 5dp edge space under its label, so the track does not change when the pill moves. The
 * label of the option that is not chosen sits 2.5dp lower, as the design centres it; it rises as the pill arrives.
 *
 * @param onLabelPlaced gets the label area's place (before the drop), where the pill is drawn.
 * @param chosen how far the pill is at this option, 0 to 1. Read only while placing.
 */
@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    trackPadding: PaddingValues,
    onLabelPlaced: (LayoutCoordinates) -> Unit,
    chosen: () -> Float,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val source = remember { MutableInteractionSource() }
    val labelColor = fadingColor(if (selected) colors.textOnBrand else colors.textSecondary, label = "segmentLabel")
    Box(
        modifier = Modifier
            .touchOverhang(horizontal = Space.s0, vertical = SegmentTouchOverhang)
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = null,
                role = Role.Tab,
                onClick = {
                    if (!selected) {
                        haptics.selection()
                        onSelect()
                    }
                },
            )
            .padding(vertical = SegmentTouchOverhang)
            .padding(trackPadding),
    ) {
        Box(modifier = Modifier.padding(bottom = EdgeHeight)) {
            // The label with the padding of the pill around it (4 above and below, 12 at the sides).
            FadingColorText(
                text = label,
                style = AppTheme.type.bodyMStrong,
                color = labelColor,
                modifier = Modifier
                    .onPlaced(onLabelPlaced)
                    .offset { IntOffset(0, (UnselectedLabelDrop * (1f - chosen())).roundToPx()) }
                    .clip(RoundedCornerShape(Radius.full))
                    // The chosen option does not press; the other shows the overlay on its label area only.
                    .indication(source, if (selected) null else LocalIndication.current)
                    .padding(horizontal = Space.s12, vertical = Space.s4),
                maxLines = 1,
            )
        }
    }
}
