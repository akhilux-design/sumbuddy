package com.expenses.app.cardbill

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.expenses.app.ExpensesApplication

/**
 * Posts the reminder of one card bill when its time comes ([CardBillReminders.remind], which checks everything again
 * first). Its input is only the bill's id.
 */
class CardBillReminderWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val given: CardBillReminders?,
) : CoroutineWorker(context, params) {
    /** The constructor WorkManager calls. */
    constructor(context: Context, params: WorkerParameters) : this(context, params, null)

    override suspend fun doWork(): Result {
        val billId = inputData.getLong(KEY_BILL_ID, NO_BILL)
        if (billId == NO_BILL) return Result.success()
        val reminders = given ?: (applicationContext as ExpensesApplication).container.cardBillReminders
        reminders.remind(billId)
        return Result.success()
    }

    /** The worker's input. */
    companion object {
        /** Input: the bill's id (Long). */
        const val KEY_BILL_ID: String = "bill_id"

        private const val NO_BILL = -1L
    }
}
