package com.expenses.app.ingest

import kotlin.coroutines.cancellation.CancellationException

/**
 * Something that runs on this phone right after new SMS were read by `IncomingSmsWorker`, before its "Payment
 * detected" notifications are posted. Splitting uses it twice (docs/server.md 9.4, 9.5): a payment or money received
 * that matches a balance with a friend settles it, and a payment to a merchant with an automatic split rule is split
 * at once. Nothing here sends SMS text anywhere; only a settlement or a group expense the rules allow goes to the
 * server.
 *
 * Hooks run one after the other ([runNewPaymentsHooks]). Each sees only the payments no earlier hook handled, so one
 * payment is never handled twice (a repayment matched by the settlement matcher is not also split as spending). Every
 * hook is called even when an earlier one failed.
 */
fun interface NewPaymentsHook {
    /**
     * Looks at what [result] read from new SMS, less what earlier hooks handled.
     *
     * @return ids of payments (debits or credits) this hook handled (split by itself, or matched to a settlement):
     * later hooks do not see them, and they get no "Payment detected" notification. Empty when it handled none.
     */
    suspend fun onNewPayments(result: IngestResult): Set<Long>
}

/**
 * Runs [hooks] in order over [result]. Each hook gets [result] without the payments the hooks before it handled. A
 * hook that fails is skipped (nothing is logged: the payment simply waits for a decision as usual).
 *
 * @return the ids every hook handled together.
 */
suspend fun runNewPaymentsHooks(hooks: List<NewPaymentsHook>, result: IngestResult): Set<Long> {
    val handled = HashSet<Long>()
    var remaining = result
    for (hook in hooks) {
        val claimed = try {
            hook.onNewPayments(remaining)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
        if (claimed.isEmpty()) continue
        handled += claimed
        remaining = remaining.copy(
            detected = remaining.detected.filterNot { it.id in claimed },
            credits = remaining.credits.filterNot { it.id in claimed },
        )
    }
    return handled
}
