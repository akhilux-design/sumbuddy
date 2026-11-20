package com.expenses.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * The error shake (`design/spec/motion.md`, "Error shake"): each time [count] goes up, the element is kicked
 * sideways ([Motion.ShakeKickDpPerSecond]) and a loose spring (`motion/spring/shake`) swings it about 8, 4 and 2 dp
 * before it settles, and `haptic/reject` plays. Do not also call `reject()` for the same refusal.
 *
 * - [count] is a counter of refused submits kept by the ViewModel (`refusedSubmits`), not a Boolean: a second refused
 *   tap does not change the error, but it must shake again.
 * - Nothing happens on the first composition, so a screen rebuilt with a count above 0 does not shake.
 * - With Remove animations on there is no shake; the haptic still plays (and the error message stays the caller's).
 * - A new kick during a shake continues from where the element is, adding to its speed.
 * - The shake moves only the drawn layer: layout and touch targets do not move.
 */
fun Modifier.shakeOn(count: Int): Modifier = this then ShakeElement(count)

private data class ShakeElement(val count: Int) : ModifierNodeElement<ShakeNode>() {
    override fun create(): ShakeNode = ShakeNode(count)

    override fun update(node: ShakeNode) = node.onCount(count)

    override fun InspectorInfo.inspectableProperties() {
        name = "shakeOn"
        properties["count"] = count
    }
}

private class ShakeNode(private var count: Int) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {
    private val offset = Animatable(0f)

    fun onCount(new: Int) {
        val grew = new > count
        count = new
        if (!grew || !isAttached) return
        currentValueOf(LocalAppHaptics).reject()
        if (currentValueOf(LocalReducedMotion)) return
        coroutineScope.launch {
            offset.animateTo(0f, Motion.Spring.Shake.spec(), initialVelocity = offset.velocity + Motion.ShakeKickDpPerSecond)
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) { translationX = offset.value.dp.toPx() }
        }
    }
}
