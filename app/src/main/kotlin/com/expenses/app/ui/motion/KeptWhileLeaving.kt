package com.expenses.app.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * What a screen draws as it leaves: while [leaving], the last [state] that [shows] something, so the screen keeps its
 * content until its exit is over instead of drawing an empty page under it (one screen never shows empty canvas between
 * two screens). Otherwise [state] itself. A screen that never showed anything draws [state].
 */
@Composable
fun <S> keptWhileLeaving(state: S, leaving: Boolean, shows: (S) -> Boolean): S {
    val last = remember { LastState<S>() }
    if (shows(state)) last.state = state
    return if (leaving) last.state ?: state else state
}

/** The last state a screen showed; written as it composes, like a remembered cache. */
private class LastState<S> {
    var state: S? = null
}
