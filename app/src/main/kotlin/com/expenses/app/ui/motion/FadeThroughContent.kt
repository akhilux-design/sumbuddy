package com.expenses.app.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion

/**
 * Content that swaps in place, with a fade-through (`design/spec/motion.md`, "Fade-through"): the old content fades out
 * over 90 ms, then the new one fades in over 160 ms, starting after 90 ms. There is no size animation.
 *
 * - The leaving content is hidden from accessibility, so TalkBack reads only the new content.
 * - Key it by a mode or a state class through [contentKey], never by text being typed (`docs/motion.md` 3.8): that
 *   would rebuild the field and close the keyboard.
 * - Never put a `liveRegion` inside it; keep that on a stable node outside.
 * - With Remove animations on the new content replaces the old one at once.
 *
 * - Data that comes quickly replaces a loading state at once ([isLoading]): until `motion/delay/placeholder` (200 ms)
 *   has passed, a loading state shows no placeholders yet, only the page's frame. Fading that frame out and the content
 *   in would leave the screen nearly blank for about 150 ms, mostly while the screen is still arriving (measured on a
 *   push to Review and to a Group page, `docs/motion.md` 3.10). Once the placeholders show, it fades through as usual.
 *
 * @param targetState the state to show.
 * @param contentKey what decides that the content changed; the state itself by default. For example
 * `contentKey = { it::class }` to swap only when the state's class changes.
 * @param isLoading whether a state stands in while the data loads (its placeholders show only after 200 ms).
 */
@Composable
fun <S> FadeThroughContent(
    targetState: S,
    modifier: Modifier = Modifier,
    contentKey: (S) -> Any? = { it },
    contentAlignment: Alignment = Alignment.TopStart,
    isLoading: (S) -> Boolean = { false },
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    // Whether the loading state on show has been there long enough for its placeholders to show.
    val loading = isLoading(targetState)
    var loadingShown by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            loadingShown = false
            awaitFrameMillis(Motion.PlaceholderDelayMillis.toLong())
            loadingShown = true
        }
    }
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            val quickData = isLoading(initialState) && !isLoading(targetState) && !loadingShown
            if (reduced || quickData) noFade() else fadeThrough()
        },
        contentAlignment = contentAlignment,
        label = "fadeThrough",
        contentKey = contentKey,
    ) { state ->
        val leaving = transition.targetState != EnterExitState.Visible
        Box(if (leaving) Modifier.semantics { hideFromAccessibility() } else Modifier, propagateMinConstraints = true) {
            content(state)
        }
    }
}

private fun noFade(): ContentTransform = ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)

private fun fadeThrough(): ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(Motion.fade(Motion.FadeThrough.InMillis, delayMillis = Motion.FadeThrough.InDelayMillis)),
    initialContentExit = fadeOut(Motion.fade(Motion.FadeThrough.OutMillis)),
    sizeTransform = null,
)
