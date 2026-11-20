package com.expenses.app.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion

/**
 * [Reveal] for a part shown while [value] is not null, such as a result banner: it opens when a value arrives after the
 * screen is shown and closes when it goes back to null, still drawing the last value while it closes. A value that is
 * there when the screen opens just shows. A change from one value to another swaps in place.
 *
 * @param value what the part shows, or null to hide it.
 * @param content the part, given the value (the last non-null one while it hides).
 */
@Composable
fun <T : Any> RevealValue(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val last = remember { LastShown<T>() }
    if (value != null) last.value = value
    Reveal(visible = value != null, modifier = modifier) { last.value?.let { content(it) } }
}

/**
 * Keys of a list built on a Column (not lazy) as it was when first composed. A row whose key is not in it was added
 * later and opens with [AddedRow]; the rows there at first just show (`docs/motion.md` 2.5).
 */
@Composable
fun <K> rememberFirstKeys(keys: Collection<K>): Set<K> = remember { keys.toSet() }

/**
 * One row of a list built on a Column. A row [added] after the list was first shown opens down from its top edge
 * (`motion/spring/default`) as it fades in over 150 ms, as [Reveal] does, and the rows below slide down to make room;
 * any other row just shows. Call it inside `key(row.id) { }`. Nothing moves with Remove animations on.
 *
 * @param added whether the row was not there when the list was first shown (see [rememberFirstKeys]).
 */
@Composable
fun AddedRow(added: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(!added) }
    state.targetState = true
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = if (LocalReducedMotion.current) EnterTransition.None else RowEnter,
        exit = ExitTransition.None,
    ) { content() }
}

private val RowEnter: EnterTransition =
    expandVertically(Motion.Spring.Default.spec(IntSize.VisibilityThreshold), expandFrom = Alignment.Top) +
        fadeIn(Motion.fade(Motion.RevealFade.InMillis))

/** The last value a part showed; a plain holder, written only during composition. */
private class LastShown<T : Any> {
    var value: T? = null
}
