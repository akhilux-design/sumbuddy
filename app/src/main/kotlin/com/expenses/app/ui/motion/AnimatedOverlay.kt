package com.expenses.app.ui.motion

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.expenses.app.ui.theme.LocalReducedMotion

/**
 * What an [AnimatedOverlay]'s content gets: the overlay's [transition] and whether it [isLeaving].
 *
 * The content animates its parts as children of [transition], for example
 * `transition.AnimatedVisibility(visible = { it }, enter = ..., exit = ...) { Scrim() }` for the scrim and the same
 * for the card. The overlay stays composed until every child has finished leaving.
 */
@Stable
class OverlayScope internal constructor(
    /** `true` while shown or arriving, `false` while leaving. Children animate on it. */
    val transition: Transition<Boolean>,
    /** Remove animations is on: give the children `EnterTransition.None` (leaving already happens in one frame). */
    val reducedMotion: Boolean,
) {
    /** The overlay is leaving: its taps and dismiss requests must be ignored. */
    val isLeaving: Boolean get() = !transition.targetState

    /** [action], except while the overlay is leaving. Wrap every button and `onDismissRequest` of the overlay in it. */
    fun guard(action: () -> Unit): () -> Unit = { if (!isLeaving) action() }

    /**
     * Ignores every touch on this element and its children while the overlay is leaving, so nothing in it can be
     * pressed during the exit. Put it on the overlay's outermost element (the scrim box, or the dialog's content).
     */
    fun Modifier.ignoreTouchWhileLeaving(): Modifier = pointerInput(this@OverlayScope) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (isLeaving) event.changes.forEach { it.consume() }
            }
        }
    }
}

/**
 * Shows an overlay (a dialog or a bottom sheet) while [value] is not null, and keeps showing its last content while it
 * leaves (`docs/motion.md` 3.5). Call sites change from `x?.let { ChoiceDialog(it) }` to
 * `AnimatedOverlay(x) { ChoiceDialog(it) }`.
 *
 * - The exit is driven by state, never by a delayed callback: clear the caller's state at once, and the overlay plays
 *   its exit with the last value. A Save refused by validation keeps the state, so the overlay simply stays.
 * - While it leaves, [OverlayScope.isLeaving] is true; use [OverlayScope.guard] and
 *   [OverlayScope.ignoreTouchWhileLeaving] so a second tap does nothing.
 * - If [value] comes back while it leaves, the same overlay turns around from where it is.
 * - With Remove animations on ([LocalReducedMotion]), it disappears in the same frame as [value] becomes null.
 * - [DialogCard][com.expenses.app.ui.components.DialogCard] and the bottom sheet stay callable on their own.
 *
 * @param value what the overlay shows, or null for no overlay.
 * @param content the overlay, given the value it shows (the last non-null one while leaving).
 */
@Composable
fun <T : Any> AnimatedOverlay(value: T?, content: @Composable OverlayScope.(T) -> Unit) {
    val reduced = LocalReducedMotion.current
    val last = remember { LastValue<T>() }
    if (value != null) last.value = value
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = value != null
    val transition = rememberTransition(visible, label = "overlay")
    val shown = last.value
    val keep = value != null || (!reduced && (visible.currentState || visible.targetState))
    if (!keep || shown == null) {
        last.value = null
        return
    }
    val scope = remember(transition, reduced) { OverlayScope(transition, reduced) }
    scope.content(shown)
}

/** The last value an overlay showed; a plain holder, written only when the value changes. */
private class LastValue<T : Any> {
    var value: T? = null
}
