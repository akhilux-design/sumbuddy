package com.expenses.app.ui.screens.you

import android.content.Context
import androidx.work.WorkInfo
import com.expenses.app.sms.ScanWorker
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile

/**
 * Reads older payments with [ScanWorker], so reading goes on when the screen is closed. Starting the same period
 * twice follows the scan already running.
 *
 * @param context any context; only its application context is kept.
 */
class WorkOlderPaymentsScanner(context: Context) : OlderPaymentsScanner {
    private val appContext: Context = context.applicationContext

    override fun scan(from: Instant, until: Instant): Flow<OlderScanStatus> {
        val name = ScanWorker.startOlderScan(appContext, newStart = from, oldStart = until)
        return ScanWorker.observeOlderScan(appContext, name)
            .filterNotNull()
            .map { it.toStatus() }
            .transformWhile { status ->
                emit(status)
                status is OlderScanStatus.Running
            }
    }

    private fun WorkInfo.toStatus(): OlderScanStatus = when (state) {
        WorkInfo.State.SUCCEEDED -> OlderScanStatus.Done(found = outputData.getInt(ScanWorker.OUT_DETECTED, 0))
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> OlderScanStatus.Failed
        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> OlderScanStatus.Running(
            read = progress.getInt(ScanWorker.PROGRESS_READ, 0),
            total = progress.getInt(ScanWorker.PROGRESS_TOTAL, 0),
        )
    }
}
