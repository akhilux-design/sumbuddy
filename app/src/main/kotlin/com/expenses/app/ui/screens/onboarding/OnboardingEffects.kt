package com.expenses.app.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One-shot messages from an onboarding ViewModel to its screen, such as "go to the next step". Each message is
 * delivered once; messages sent while no screen listens wait until one does.
 */
internal class Effects<T> {
    private val channel = Channel<T>(Channel.BUFFERED)

    /** The messages, for [CollectEffects]. */
    val flow: Flow<T> = channel.receiveAsFlow()

    /** Sends [effect] to the screen. */
    fun send(effect: T) {
        channel.trySend(effect)
    }
}

/**
 * Hands each message of [effects] to [onEffect] while this composable is shown.
 *
 * @param effects the ViewModel's one-shot messages.
 * @param onEffect what to do with each, usually a navigation.
 */
@Composable
internal fun <T> CollectEffects(effects: Flow<T>, onEffect: (T) -> Unit) {
    val current by rememberUpdatedState(onEffect)
    LaunchedEffect(effects) {
        effects.collect { current(it) }
    }
}
