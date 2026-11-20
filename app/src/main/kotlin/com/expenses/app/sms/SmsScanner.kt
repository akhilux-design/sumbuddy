package com.expenses.app.sms

import com.expenses.app.ingest.IngestPipeline
import com.expenses.app.ingest.IngestResult
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
     * @param onProgress called after each page with the number of messages read so far.
     */
    suspend fun scan(from: Instant, until: Instant?, onProgress: suspend (read: Int) -> Unit = {}): ScanSummary {
        var result = IngestResult()
        var read = 0
        var newest: Instant? = null
        inbox.forEachPage(from, until) { page ->
            result += pipeline.ingest(page)
            read += page.size
            val pageNewest = Instant.ofEpochMilli(page.maxOf { it.message.receivedAtMillis })
            newest = newest?.let { maxOf(it, pageNewest) } ?: pageNewest
            onProgress(read)
        }
        return ScanSummary(result, read, newest)
    }
}

/**
 * What one [SmsScanner.scan] did.
 *
 * @param result everything the pages stored, added up.
 * @param read how many inbox messages the period held, including ones read before.
 * @param newest when the newest of them arrived, or null when the period held none.
 */
data class ScanSummary(val result: IngestResult, val read: Int, val newest: Instant?)
