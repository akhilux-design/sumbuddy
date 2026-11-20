package com.expenses.app.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.motion.FadingColorText
import com.expenses.app.ui.motion.fadingAlpha
import com.expenses.app.ui.motion.fadingColor
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/** Width of the outline on a chip that is not selected. It sits inside the pill and does not change its size. */
private val UnselectedStrokeWidth = 1.5.dp

/** How far the touch area reaches past the pill above and below: 6 + the 36dp selected pill + 6 = 48. */
private val ChipTouchOverhang = 6.dp

/**
 * A small pill with a text label that is either selected or not, used for short sets of choices such as
 * "Equally / Amounts / % / Shares" and for list filters.
 *
 * Selected is a solid green pill with no outline; not selected is a grey pill with a `color/border/subtle` outline,
 * as in the design, so the two differ by shape as well as colour. The owner chose the design's light outline over a
 * darker one on 2026-11-17. The outline is drawn inside the pill and does not add to its size, so both states are
 * the same size (36dp high), as in the design.
 * The chip is as wide as its label plus padding and grows with the phone's font size.
 *
 * The pill is lower than 48dp. Its touch area reaches 6dp past it above and below without moving anything, so
 * keep about 6dp free of other tappable things above and below a row of chips.
 *
 * Motion (`design/spec/motion.md`, "Chip and toggle"): fill, label and outline change over 150 ms; while pressed the
 * flat overlay (`state/pressed`) covers the pill; nothing moves or scales. Choosing a chip that was not chosen plays
 * `haptic/selection` once; a repeat tap on the chosen chip and a [Role.Button] chip play nothing.
 *
 * @param label text on the chip, for example "Equally". One line.
 * @param selected true when this chip is the chosen one.
 * @param onClick called when the chip is tapped. The caller changes [selected] in answer.
 * @param modifier layout changes from the caller, applied to the pill.
 * @param role what the chip is for screen readers. Keep [Role.RadioButton] for one choice out of a set (put
 * `Modifier.selectableGroup()` on the row), use [Role.Tab] for a filter that switches the list below, and
 * [Role.Button] for a chip that only starts an action, such as "+ New"; a button chip is not announced as
 * selected or not selected.
 */
@Composable
fun AppChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val shape = RoundedCornerShape(Radius.full)
    val interactionSource = remember { MutableInteractionSource() }
    val fill by fadingColor(if (selected) colors.brandPrimary else colors.bgSurfaceRaised, label = "chipFill")
    val outline by fadingAlpha(if (selected) 0f else 1f, label = "chipOutline")
    val labelColor = fadingColor(if (selected) colors.textOnBrand else colors.textPrimary, label = "chipLabel")
    val outlineColor = colors.borderSubtle
    Box(
        modifier = modifier
            .touchOverhang(horizontal = Space.s0, vertical = ChipTouchOverhang)
            .then(
                if (role == Role.Button) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = role,
                        onClick = onClick,
                    )
                } else {
                    Modifier.selectable(
                        selected = selected,
                        interactionSource = interactionSource,
                        indication = null,
                        role = role,
                        onClick = {
                            if (!selected) haptics.selection()
                            onClick()
                        },
                    )
                },
            )
            .padding(vertical = ChipTouchOverhang)
            .clip(shape)
            // The overlay is drawn over the fill and the outline: one layer on top of everything else.
            .indication(interactionSource, LocalIndication.current)
            .drawBehind {
                drawRect(fill)
                if (outline > 0f) {
                    // The outline sits inside the pill, as `Modifier.border` draws it.
                    val w = UnselectedStrokeWidth.toPx()
                    val r = (size.minDimension - w) / 2f
                    drawRoundRect(
                        color = outlineColor,
                        topLeft = Offset(w / 2f, w / 2f),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(r, r),
                        style = Stroke(w),
                        alpha = outline,
                    )
                }
            }
            .padding(horizontal = Space.s16, vertical = Space.s8),
        contentAlignment = Alignment.Center,
    ) {
        FadingColorText(
            text = label,
            style = AppTheme.type.bodyMStrong,
            color = labelColor,
            maxLines = 1,
        )
    }
}
