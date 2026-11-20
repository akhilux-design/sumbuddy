package com.expenses.app.ui.screens.settle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Shared by the settle-up screen tests. Owned by the settle-up area.

private const val WAIT_STEP_MILLIS = 10L
private const val WAIT_STEPS = 500

/**
 * Waits in real time (Room answers on its own threads) until [check] is true; fails after 5 seconds. For effects
 * that no flow reports, such as a pending UPI payment being forgotten.
 */
internal suspend fun awaitTrue(message: String, check: suspend () -> Boolean) {
    repeat(WAIT_STEPS) {
        if (check()) return
        withContext(Dispatchers.Default) { delay(WAIT_STEP_MILLIS) }
    }
    throw AssertionError("timed out: $message")
}
