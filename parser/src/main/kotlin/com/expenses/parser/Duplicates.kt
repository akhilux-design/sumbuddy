package com.expenses.parser

/** One payment read from one SMS, as handed to [Duplicates.keepBankCopies]. */
data class ReadPayment(val sms: SmsMessage, val payment: ParsedPayment)

/**
 * Removes second reports of the same payment, so each payment is counted once and always from the bank's SMS.
 *
 * Apps, billers and merchants often confirm a payment that the bank also reports ("Paid Rs 450 to Swiggy" from a
 * UPI app, then "Rs 450 debited" from the bank). Two messages are the same payment when they move money the same way
 * (both debits or both credits) by exactly the same amount in the same currency, and arrive within [window] of each
 * other, or when they print the same reference number.
 */
object Duplicates {
    /** Default time between two reports of one payment. */
    const val DEFAULT_WINDOW_MILLIS: Long = 15 * 60 * 1000

    /**
     * Returns [items] without the copies, in their original order.
     *
     * - A copy from a sender that is not a bank is dropped when a bank reports the same payment.
     * - Two bank messages are one payment only when they print the same reference number; otherwise both are kept,
     *   because two real payments of the same amount a few minutes apart (two cups of tea) are common.
     * - When no bank reports the payment, the first non-bank report is kept and later identical ones are dropped.
     */
    fun keepBankCopies(items: List<ReadPayment>, windowMillis: Long = DEFAULT_WINDOW_MILLIS): List<ReadPayment> {
        val isBank = items.map { Banks.fromSender(it.sms.sender) != null }
        val drop = BooleanArray(items.size)
        for (i in items.indices) {
            for (j in i + 1 until items.size) {
                if (drop[i]) break
                if (drop[j] || !samePayment(items[i], items[j], windowMillis)) continue
                when {
                    isBank[i] && isBank[j] -> if (sameReference(items[i], items[j])) drop[j] = true
                    isBank[i] -> drop[j] = true
                    isBank[j] -> drop[i] = true
                    else -> drop[j] = true
                }
            }
        }
        return items.filterIndexed { index, _ -> !drop[index] }
    }

    private fun samePayment(a: ReadPayment, b: ReadPayment, windowMillis: Long): Boolean {
        val p = a.payment
        val q = b.payment
        if (p.direction != q.direction || p.currency != q.currency || p.amountMinor != q.amountMinor) return false
        return sameReference(a, b) || kotlin.math.abs(a.sms.receivedAtMillis - b.sms.receivedAtMillis) <= windowMillis
    }

    private fun sameReference(a: ReadPayment, b: ReadPayment): Boolean {
        val r = a.payment.referenceId
        return r != null && r == b.payment.referenceId
    }
}
