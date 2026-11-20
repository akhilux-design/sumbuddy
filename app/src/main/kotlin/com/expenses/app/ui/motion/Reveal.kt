package com.expenses.app.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion

/**
 * A part that appears after the screen is shown: a banner, a helper line, a form section, a row added later
 * (`design/spec/motion.md`, "Reveal"). It opens down from its top edge with `motion/spring/default` as it fades in over
 * 150 ms, and what is below slides down to make room. Hiding is the reverse, a little faster (`motion/spring/fast`,
 * fade out over 100 ms). No haptic.
 *
 * - It does not animate on its first composition: a part that is there when the screen opens just shows.
 * - With Remove animations on it shows and hides at once.
 * - Only for small sections (`docs/motion.md` 3.8): never a whole screen or a long list.
 * - Keep a `liveRegion` on a stable node outside it, not inside.
 *
 * @param visible whether the part is shown.
 */
@Composable
fun Reveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val state = remember { MutableTransitionState(visible) }
    state.targetState = visible
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = if (reduced) EnterTransition.None else RevealEnter,
        exit = if (reduced) ExitTransition.None else RevealExit,
        content = content,
    )
}

private val RevealEnter: EnterTransition =
    expandVertically(Motion.Spring.Default.spec(IntSize.VisibilityThreshold), expandFrom = Alignment.Top) +
        fadeIn(Motion.fade(Motion.RevealFade.InMillis))

private val RevealExit: ExitTransition =
    shrinkVertically(Motion.Spring.Fast.spec(IntSize.VisibilityThreshold), shrinkTowards = Alignment.Top) +
        fadeOut(Motion.fade(Motion.RevealFade.OutMillis))
