package com.expenses.app.sms

import com.expenses.app.ingest.IngestPipeline
import com.expenses.app.ingest.IngestResult
import com.expenses.app.ingest.SmsTimes
import java.time.Instant

/**
 * Reads a period of the inbox one page at a time and hands each page to the [IngestPipeline], so each page is one
 * transaction and its message texts can be dropped before the next page is read. Duplicates across pages are still
 * found, because the pipeline also compares each payment with the ones already stored.
 *
 * @param inbox the phone's inbox.
 * @param pipeline stores what the messages give.
 */
class SmsScanner(private val inbox: PagedSmsInbox, private val pipeline: IngestPipeline) {
    /**
     * Reads the messages received at or after [from] and before [until] (up to now when null).
     *
     * @param keepFirstRead which of the messages read for the first time to keep in [IngestResult.firstRead]; none
     * by default, so a long scan does not hold the times of every message it read.
     * @param onProgress called after each page with the number of messages read so far.
     */
    suspend fun scan(
        from: Instant,
        until: Instant?,
        keepFirstRead: (SmsTimes) -> Boolean = { false },
        onProgress: suspend (read: Int) -> Unit = {},
    ): ScanSummary {
        var result = IngestResult()
        var read = 0
        inbox.forEachPage(from, until) { page ->
            val stored = pipeline.ingest(page)
            result += stored.copy(firstRead = stored.firstRead.filter(keepFirstRead))
            read += page.size
            onProgress(read)
        }
        return ScanSummary(result, read)
    }
}

/**
 * What one [SmsScanner.scan] did.
 *
 * @param result everything the pages stored, added up. Its [IngestResult.firstRead] holds the times of the messages
 * this scan read for the first time that `keepFirstRead` kept; messages of the period read before are not in it.
 * @param read how many inbox messages the period held, including ones read before.
 */
data class ScanSummary(val result: IngestResult, val read: Int)
