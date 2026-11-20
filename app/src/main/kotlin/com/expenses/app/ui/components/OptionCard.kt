package com.expenses.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.core.EdgeHeight
import com.expenses.app.ui.components.core.IconBubble
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.fadingAlpha
import com.expenses.app.ui.motion.fadingColor
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space
import com.expenses.app.ui.theme.motion

/** Outline weight of the card that is not selected (Figma: 1.5). */
private val OptionCardRestingStroke = 1.5.dp

/** Outline weight of the selected card (Figma: 2). */
private val OptionCardSelectedStroke = 2.dp

/** Diameter of the radio mark at the end of the card. */
private val OptionCardRadioSize = 24.dp

/** Weight of the empty radio ring. */
private val OptionCardRadioStroke = 2.dp

/** Diameter of the dot inside the selected radio mark. */
private val OptionCardRadioDotSize = 10.dp

/**
 * A card for picking one option out of a few: a 3D icon, a title, a line of explanation and a radio mark.
 *
 * Not selected, the card is flat with a thin grey outline and an empty ring. Selected, it gets the green
 * outline, the soft green fill, the raised edge under it and a filled ring with a dot, so the choice shows
 * by shape as well as by colour. The 5dp for the edge is kept in both states. The outline is part of the
 * size, as in the design, so the selected card is 1dp taller and its text column 1dp narrower.
 *
 * The card is as wide as the space it is given and grows taller when the text wraps. The whole card is one
 * radio button for touch and for screen readers. Put the cards of one question in a column with
 * `Modifier.selectableGroup()`.
 *
 * Motion (`design/spec/motion.md`, "Option radio pop"): choosing a card fades its face, outline, edge and ring over
 * 150 ms while the radio dot pops from nothing with `motion/spring/pop`. The card does not rise as well (one motion
 * per change), and it presses flat: while pressed the overlay (`state/pressed`) covers the face, not the edge space.
 * Choosing a card that was not chosen plays `haptic/selection`; a repeat tap on the chosen card plays nothing.
 *
 * @param title the name of the option, for example "This month".
 * @param subtitle one short sentence that explains the option. It wraps onto more lines when needed.
 * @param icon the 3D icon shown in the bubble at the start. It is decorative: the title names the option.
 * @param selected true when this card is the chosen one.
 * @param onClick called when the user taps the card. The caller marks it as selected.
 * @param modifier layout changes from the caller, applied to the whole card including the edge space.
 */
@Composable
fun OptionCard(
    title: String,
    subtitle: String,
    icon: AppIcon,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val source = remember { MutableInteractionSource() }
    // The outline is part of the layout (the selected card is 1dp taller), so its width changes at once.
    val strokeWidth = if (selected) OptionCardSelectedStroke else OptionCardRestingStroke
    val face by fadingColor(if (selected) colors.brandSoft else colors.bgSurface, label = "optionFace")
    val outline by fadingColor(if (selected) colors.brandPrimary else colors.borderSubtle, label = "optionOutline")
    val edge by fadingAlpha(if (selected) 1f else 0f, label = "optionEdge")
    val edgeColor = colors.brandEdge
    Box(
        // The tap is handled on the whole card, so that "selected" and the radio role sit on the same element for
        // screen readers. The 5dp edge space is kept in both states, as in RaisedSurface.
        modifier = modifier
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = null,
                role = Role.RadioButton,
                onClick = {
                    if (!selected) haptics.selection()
                    onClick()
                },
            )
            .padding(bottom = EdgeHeight)
            .drawBehind {
                if (edge > 0f) {
                    val r = minOf(Radius.lg.toPx(), size.minDimension / 2f)
                    translate(top = EdgeHeight.toPx()) {
                        drawRoundRect(color = edgeColor, cornerRadius = CornerRadius(r, r), alpha = edge)
                    }
                }
            },
        propagateMinConstraints = true,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.lg))
                // The pressed overlay is drawn on the face, over everything in it, and not on the edge space.
                .indication(source, LocalIndication.current)
                .drawBehind {
                    drawRect(face)
                    drawInnerOutline(outline, strokeWidth, Radius.lg)
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.padding(strokeWidth + Space.s16),
                horizontalArrangement = Arrangement.spacedBy(Space.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(icon = icon)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    AppText(text = title, style = AppTheme.type.title, color = colors.textPrimary)
                    AppText(text = subtitle, style = AppTheme.type.caption, color = colors.textSecondary)
                }
                OptionCardRadio(selected = selected)
            }
        }
    }
}

/** A stroke of [width] drawn inside the bounds with corner [radius], as `Modifier.border` draws it. */
private fun DrawScope.drawInnerOutline(color: Color, width: Dp, radius: Dp, alpha: Float = 1f) {
    val w = width.toPx()
    val r = (minOf(radius.toPx(), size.minDimension / 2f) - w / 2f).coerceAtLeast(0f)
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(w),
        alpha = alpha,
    )
}

/**
 * The radio mark at the end of the card: an empty ring, or a filled circle with a dot when [selected]. The ring
 * fills over 150 ms and the dot pops with `motion/spring/pop`; both are drawn, never recomposed, while they change.
 * It is only a picture; the card itself tells screen readers whether it is selected.
 */
@Composable
private fun OptionCardRadio(selected: Boolean) {
    val colors = AppTheme.colors
    val fill by fadingAlpha(if (selected) 1f else 0f, label = "radioFill")
    val dot by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motion(Motion.Spring.Pop.spec()),
        label = "radioDot",
    )
    val ringColor = colors.borderSubtle
    val fillColor = colors.brandPrimary
    val dotColor = colors.textOnBrand
    Box(
        modifier = Modifier
            .size(OptionCardRadioSize)
            .drawBehind {
                if (fill > 0f) drawCircle(fillColor, alpha = fill)
                if (fill < 1f) drawInnerOutline(ringColor, OptionCardRadioStroke, OptionCardRadioSize, alpha = 1f - fill)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(OptionCardRadioDotSize)
                .graphicsLayer {
                    // The pop overshoots on the way in; on the way out it must not turn inside out.
                    val scale = dot.coerceAtLeast(0f)
                    scaleX = scale
                    scaleY = scale
                }
                .drawBehind { drawCircle(dotColor) },
        )
    }
}
