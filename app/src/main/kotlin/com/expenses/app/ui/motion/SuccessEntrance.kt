package com.expenses.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * The success arrival of a result screen (`design/spec/motion.md`, "Success"): about 100 ms after the screen arrives,
 * its sticker pops from 50% with `motion/spring/pop` (a slight overshoot) as it fades in over 150 ms, and
 * `haptic/confirm` plays once, at the start of the pop.
 *
 * - It plays once per arrival: a saveable flag keeps it from replaying when the screen is rebuilt, after a
 *   configuration change or a process restore. The flag is set once the confirm has played, so a screen rebuilt
 *   before that still plays it exactly once.
 * - With Remove animations on, the sticker is at rest from the first frame and the confirm plays at once.
 * - Nothing waits for it: the footer buttons can be tapped at once.
 *
 * Use it through [com.expenses.app.ui.components.StickerTile]'s `arrival` parameter, or apply [successPop].
 */
@Stable
class SuccessEntrance internal constructor(
    internal val scale: Animatable<Float, AnimationVector1D>,
    internal val alpha: Animatable<Float, AnimationVector1D>,
)

/** Remembers the arrival and runs it (pop and confirm) once; see [SuccessEntrance]. */
@Composable
fun rememberSuccessEntrance(): SuccessEntrance {
    var played by rememberSaveable { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val atRest = played || reduced
    val entrance = remember {
        SuccessEntrance(
            scale = Animatable(if (atRest) 1f else Motion.PopScale),
            alpha = Animatable(if (atRest) 1f else 0f),
        )
    }
    val haptics by rememberUpdatedState(LocalAppHaptics.current)
    LaunchedEffect(Unit) {
        if (played) return@LaunchedEffect
        if (!reduced) {
            // On the frame clock, so it runs with the animations (and the test clock). Not a callback: nothing waits.
            val start = withFrameMillis { it }
            awaitFrameClock(start + Motion.SuccessDelayMillis)
        }
        haptics.confirm()
        played = true
        if (reduced) {
            entrance.scale.snapTo(1f)
            entrance.alpha.snapTo(1f)
            return@LaunchedEffect
        }
        launch { entrance.alpha.animateTo(1f, Motion.fade(Motion.Duration.Short)) }
        entrance.scale.animateTo(1f, Motion.Spring.Pop.spec())
    }
    return entrance
}

/** Draws the element with [entrance]'s pop: scale and alpha in its layer only, so layout and touch do not move. */
fun Modifier.successPop(entrance: SuccessEntrance): Modifier = graphicsLayer {
    val s = entrance.scale.value
    scaleX = s
    scaleY = s
    alpha = entrance.alpha.value
}
