package com.expenses.app.ui.screens.onboarding

import android.content.Context
import androidx.work.WorkInfo
import com.expenses.app.sms.ScanWorker
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * How far the onboarding scan has got.
 *
 * @param read messages read so far.
 * @param total messages to read, or 0 while the inbox is still being counted.
 * @param finished true once the scan ended; what it stored is then in the database.
 * @param failed true when the scan ended early with an error; part of the period was not read.
 */
data class ScanProgress(val read: Int, val total: Int, val finished: Boolean, val failed: Boolean = false)

/** Starts the onboarding scan of the SMS inbox and follows it. */
interface OnboardingScanner {
    /**
     * Starts reading every inbox message received since [from], replacing a scan that is still running, and returns
     * the progress of this scan only.
     */
    suspend fun start(from: Instant): Flow<ScanProgress>
}

/**
 * The [OnboardingScanner] that runs the ingestion feature's [ScanWorker] in WorkManager, so the scan finishes even
 * when the screen closes.
 *
 * @param context any context; only its application context is kept.
 */
class WorkOnboardingScanner(context: Context) : OnboardingScanner {
    private val appContext: Context = context.applicationContext

    override suspend fun start(from: Instant): Flow<ScanProgress> {
        // WorkManager can still report the earlier scan for a moment after the new one is queued, so it is left out.
        val earlier = ScanWorker.observeOnboardingScan(appContext).first()?.id
        ScanWorker.startOnboardingScan(appContext, from)
        return ScanWorker.observeOnboardingScan(appContext)
            .filterNotNull()
            .map { info -> if (info.id == earlier) NotStarted else info.toProgress() }
    }
}

/** The progress before the scan reads its first message. */
private val NotStarted = ScanProgress(read = 0, total = 0, finished = false)

/** The progress a [ScanWorker] reports in this [WorkInfo]. */
private fun WorkInfo.toProgress(): ScanProgress = when {
    state == WorkInfo.State.SUCCEEDED -> {
        val read = outputData.getInt(ScanWorker.OUT_READ, 0)
        ScanProgress(read = read, total = read, finished = true)
    }
    state.isFinished -> ScanProgress(read = 0, total = 0, finished = true, failed = true)
    state == WorkInfo.State.RUNNING -> ScanProgress(
        read = progress.getInt(ScanWorker.PROGRESS_READ, 0),
        total = progress.getInt(ScanWorker.PROGRESS_TOTAL, 0),
        finished = false,
    )
    else -> NotStarted
}
