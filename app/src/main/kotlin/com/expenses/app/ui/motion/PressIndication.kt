package com.expenses.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The flat pressed overlay as a Compose `Indication` (`design/spec/motion.md`, "Flat pressed overlay"; the Pressed
 * sections of the component specs in `design/spec/components`). While pressed it draws [color] over the element,
 * inside [shape]: it fades in over 50 ms, stays until 100 ms after the touch ([PressTiming]) and fades out over
 * 150 ms; a cancelled press fades out over 100 ms with no hold. No movement, no scale, no ripple and no haptic.
 *
 * Draw it on the visible shape: the element should clip to its shape before its clickable, or pass the shape here.
 * With Remove animations on, the overlay appears and disappears without a fade, and the 100 ms hold stays.
 *
 * - Rows, cards, tiles, chips and icon buttons: [rememberPressIndication] (`state/pressed`, `color/text/primary` at
 *   10%), provided as `LocalIndication` so every `clickable` gets it.
 * - Ghost button: `PressIndication(AppTheme.colors.brandSoft, RoundedCornerShape(Radius.lg))` on the face
 *   (`state/pressed-ghost`), so the pill appears behind the label.
 *
 * @param color the overlay colour, alpha included.
 * @param shape the overlay's outline inside the element's bounds; a rectangle by default (the element's own clip
 * gives it its shape).
 */
class PressIndication(private val color: Color, private val shape: Shape = RectangleShape) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressOverlayNode(interactionSource, color, shape)

    override fun equals(other: Any?): Boolean = other is PressIndication && other.color == color && other.shape == shape

    override fun hashCode(): Int = 31 * color.hashCode() + shape.hashCode()
}

/** `state/pressed`: the flat pressed overlay of rows, cards, tiles, chips and icon buttons (`color/text/primary` at 10%). */
@Composable
@ReadOnlyComposable
fun rememberPressIndication(shape: Shape = RectangleShape): PressIndication =
    PressIndication(AppTheme.colors.textPrimary.copy(alpha = Motion.PressedOverlayAlpha), shape)

private class PressOverlayNode(
    private val source: InteractionSource,
    private val color: Color,
    private val shape: Shape,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var pressedAt: Long? = null
            source.interactions.collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        pressedAt = null
                        pressedAt = withFrameMillis { it }
                        fadeTo(1f, Motion.PressFadeInMillis)
                    }
                    is PressInteraction.Release -> {
                        val endedAt = withFrameMillis { it }
                        val holdUntil = endedAt + PressTiming.holdLeft(PressTiming.End.Release, pressedAt, endedAt)
                        fadeTo(1f, Motion.PressFadeInMillis)
                        awaitFrameClock(holdUntil)
                        fadeTo(0f, Motion.PressFadeOutMillis)
                    }
                    is PressInteraction.Cancel -> fadeTo(0f, Motion.PressCancelFadeOutMillis)
                }
            }
        }
    }

    private suspend fun fadeTo(target: Float, durationMillis: Int) {
        // The element can leave in the frame that ends the press (an overlay closed by its own button, with Remove
        // animations on), and the frame clock can still resume this collector once: a detached node has nothing to
        // draw and cannot read composition locals.
        if (!isAttached) return
        if (currentValueOf(LocalReducedMotion)) {
            alpha.snapTo(target)
            invalidateDraw()
        } else {
            alpha.animateTo(target, Motion.fade(durationMillis)) { invalidateDraw() }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = alpha.value
        if (a > 0f) {
            drawOutline(shape.createOutline(size, layoutDirection, this), color = color, alpha = a)
        }
    }
}
