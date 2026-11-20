package com.expenses.app.ui.motion

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion

/**
 * A keyed item of a lazy list that moves when the list changes (`design/spec/motion.md`, "List insert and remove"): a
 * new item fades in over 150 ms where it belongs and the items below slide down with `motion/spring/default`; a removed
 * one fades out over 150 ms and the rest slide up. Items already there when the list is first shown do not animate, and
 * nothing moves with Remove animations on. No haptic.
 *
 * Use it in an `item(key = ...)` or `items(..., key = ...)` lambda, around the item's content. The item must have a
 * stable key for its placement to animate.
 */
@Composable
fun LazyItemScope.AnimatedItem(content: @Composable () -> Unit) {
    val modifier = if (LocalReducedMotion.current) {
        Modifier
    } else {
        Modifier.animateItem(
            fadeInSpec = Motion.fade(Motion.Duration.Short),
            placementSpec = Motion.Spring.Default.spec(IntOffset.VisibilityThreshold),
            fadeOutSpec = Motion.fade(Motion.Duration.Short),
        )
    }
    Box(modifier = modifier) { content() }
}
