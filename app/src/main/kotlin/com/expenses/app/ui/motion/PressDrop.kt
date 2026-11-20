package com.expenses.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.SpringToken
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

/**
 * How long a press stays visible after it ends (`motion/hold/press`): a press shows for at least
 * [Motion.PressHoldMillis] from its start, so even a quick tap shows the full press. Pure, so it is tested on the JVM.
 *
 * - A tap whose Press and Release arrive in the same frame (a quick tap inside a scroll body, where foundation sends
 *   both on the finger's lift) holds the whole 100 ms.
 * - A long press holds nothing extra.
 * - A cancelled press (the finger slid off, or a scroll took over) never holds.
 *
 * The tap's action is never delayed by any of this: the hold is drawing only.
 */
object PressTiming {
    /** How a press ended. */
    enum class End { Release, Cancel }

    /**
     * Milliseconds the press must still show after it ended at [endedAtMillis].
     *
     * @param pressedAtMillis the frame time the press was first drawn, or null when it ended before its first frame (a
     * same-frame tap): it then counts as starting at [endedAtMillis].
     */
    fun holdLeft(end: End, pressedAtMillis: Long?, endedAtMillis: Long, holdMillis: Long = Motion.PressHoldMillis): Long =
        when (end) {
            End.Cancel -> 0L
            End.Release -> ((pressedAtMillis ?: endedAtMillis) + holdMillis - endedAtMillis).coerceAtLeast(0L)
        }
}

/**
 * Drives the 3D press of a raised face from [source]: returns 0 (up) to 1 (down onto its edge). Multiply by
 * [Motion.PressDistance] and read it only inside a `graphicsLayer {}` or `offset {}` lambda, never in composition.
 *
 * - Press: plays `haptic/press` (when [haptic] is true), then springs down with `motion/spring/press`.
 * - Release: finishes the drop, holds until 100 ms after the press ([PressTiming]), then springs back up with
 *   `motion/spring/press-release` (a slight overshoot).
 * - Cancel: back up with `motion/spring/fast`, no hold and no bounce.
 * - Remove animations ([LocalReducedMotion]): the face jumps down and up with no movement in between, but the 100 ms
 *   hold stays (it is state, not motion). The haptic plays either way.
 * - Not [enabled]: a new press is ignored. A press already under way still finishes (hold, then back up), so a face
 *   its own tap disables (Save while saving) shows the whole press and never jumps; a press cancelled because the
 *   face was disabled ends the same way.
 *
 * Each animation starts from the current value and speed, so a new press during the release continues smoothly. The
 * hold waits on the frame clock (`withFrameMillis`), so it runs on the same clock as the animations, also under the
 * Compose test `mainClock`. TalkBack's double-tap sends no Press, so it plays no motion and no haptic.
 *
 * @param source the interactions to follow: the clickable's own source, or one shared with the clickable that sits
 * outside the face (the bottom-bar pill).
 * @param enabled whether the face can press at all.
 * @param haptic whether a press plays `haptic/press`; false for the bottom-bar pill and buttons inside the swipe card.
 */
@Composable
fun rememberPressDrop(source: InteractionSource, enabled: Boolean, haptic: Boolean = true): State<Float> {
    val drop = remember { Animatable(0f) }
    val haptics = LocalAppHaptics.current
    val playHaptic by rememberUpdatedState(haptic)
    val reduced by rememberUpdatedState(LocalReducedMotion.current)
    val canPress by rememberUpdatedState(enabled)
    // Keyed on the source only: turning disabled must not restart this and drop a press that is under way.
    LaunchedEffect(source) {
        suspend fun moveTo(target: Float, spring: SpringToken) {
            if (reduced) drop.snapTo(target) else drop.animateTo(target, spring.spec())
        }
        var pressedAt: Long? = null
        suspend fun release() {
            val endedAt = withFrameMillis { it }
            val holdUntil = endedAt + PressTiming.holdLeft(PressTiming.End.Release, pressedAt, endedAt)
            moveTo(1f, Motion.Spring.Press)
            awaitFrameClock(holdUntil)
            moveTo(0f, Motion.Spring.PressRelease)
        }
        // Only a press that starts while enabled, and its end. Filtered before collectLatest, so an ignored press (or a
        // hover or focus on the same source) does not cut the release under way.
        var held = false
        source.interactions
            .filter { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> canPress.also { held = it }
                    is PressInteraction.Release, is PressInteraction.Cancel -> held.also { held = false }
                    else -> false
                }
            }
            .collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        pressedAt = null
                        if (playHaptic) haptics.press()
                        pressedAt = withFrameMillis { it }
                        moveTo(1f, Motion.Spring.Press)
                    }
                    is PressInteraction.Release -> release()
                    // Disabled under the finger (its own tap disabled it): it ends as a press does, not as a slide-off.
                    is PressInteraction.Cancel -> if (canPress) moveTo(0f, Motion.Spring.Fast) else release()
                }
            }
    }
    return drop.asState()
}

/**
 * Waits on the frame clock (`withFrameMillis`), at least one frame, until a frame at or after [frameMillis]. Used for
 * the press hold so it runs on the animations' clock, also under the Compose test `mainClock`.
 */
internal suspend fun awaitFrameClock(frameMillis: Long) {
    var now = withFrameMillis { it }
    while (now < frameMillis) now = withFrameMillis { it }
}
