package com.expenses.app.sms

import android.annotation.SuppressLint
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import java.time.Duration

/**
 * How a run of [IncomingSmsWorker] ended, for the worker tests. Every run ends with success; a read tries again by
 * queuing its next try as new work ([IncomingSmsWorker.nextTry]), which the tests' stand-in parts keep.
 */
internal enum class ReadEnd { DONE, TRIES_AGAIN }

/** What WorkManager will give the worker that runs [this]. */
@get:SuppressLint("RestrictedApi")
internal val OneTimeWorkRequest.input: Data get() = workSpec.input

/** How long WorkManager waits before running [this]. */
@get:SuppressLint("RestrictedApi")
internal val OneTimeWorkRequest.delay: Duration get() = Duration.ofMillis(workSpec.initialDelay)

/** Whether [this] runs as expedited work. */
@get:SuppressLint("RestrictedApi")
internal val OneTimeWorkRequest.expedited: Boolean get() = workSpec.expedited
