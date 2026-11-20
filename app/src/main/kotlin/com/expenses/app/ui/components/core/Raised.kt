package com.expenses.app.ui.components.core

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.motion.rememberPressDrop
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.Radius

/** Height of the darker band under raised elements. In Figma this is the 0-blur shadow `DROP_SHADOW(0,5,0)`. */
val EdgeHeight: Dp = 5.dp

/**
 * The "chunky" raised surface used by buttons, cards, the active tab and tiles: a face with a darker band under it.
 *
 * The composable always reserves [EdgeHeight] below the face, exactly like the Figma components (wrapper with
 * 5 bottom padding), so raised and flat elements line up.
 *
 * **3D press** (`design/spec/motion.md`, "3D press and release"; `components/Button.md`, Pressed). A face with an
 * edge presses when it is [enabled] and has an [onClick] or a [pressSource]: on touch it springs down
 * [Motion.PressDistance] (4 dp) onto its edge, leaving a 1 dp lip, stays down at least 100 ms, and springs back up
 * with a slight overshoot ([rememberPressDrop]). The press only moves the face's layer, so the measured size never
 * changes, and [onClick] is never delayed by it.
 *
 * @param faceColor fill of the face.
 * @param edgeColor colour of the band under the face, or null for a flat element that still keeps the space.
 * @param cornerRadius radius of the face. Use [Radius.full] for pills.
 * @param border optional border drawn inside the face bounds.
 * @param interactionSource the face's clickable reports to it (when [onClick] is set).
 * @param pressSource press the face from these interactions instead of the face's own clickable: for a face whose
 * clickable sits outside it (the bottom-bar pill, whose `selectable` is the whole tab). Pass the same
 * `MutableInteractionSource` to that clickable and here.
 * @param pressHaptic whether a press plays `haptic/press`. False for the bottom-bar pill and buttons inside the
 * draggable swipe card.
 */
@Composable
fun RaisedSurface(
    faceColor: Color,
    edgeColor: Color?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Radius.lg,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    pressSource: InteractionSource? = null,
    pressHaptic: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val canPress = enabled && edgeColor != null && (onClick != null || pressSource != null)
    val drop = rememberPressDrop(source = pressSource ?: source, enabled = canPress, haptic = pressHaptic)
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .padding(bottom = EdgeHeight)
            .drawBehind {
                if (edgeColor != null) {
                    val r = minOf(cornerRadius.toPx(), size.minDimension / 2f)
                    translate(top = EdgeHeight.toPx()) {
                        drawRoundRect(color = edgeColor, cornerRadius = CornerRadius(r, r))
                    }
                }
            },
        propagateMinConstraints = true,
    ) {
        var face = Modifier
            .graphicsLayer {
                translationY = drop.value * Motion.PressDistance.toPx()
                this.shape = shape
                clip = true
            }
            .background(faceColor)
        if (border != null) face = face.border(border, shape)
        if (onClick != null) {
            face = face.clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = role,
                onClick = onClick,
            )
        }
        Box(modifier = face, contentAlignment = contentAlignment, content = content)
    }
}
