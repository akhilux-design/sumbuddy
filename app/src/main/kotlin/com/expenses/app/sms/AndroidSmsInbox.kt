package com.expenses.app.sms

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.expenses.app.ingest.InboxSms
import com.expenses.app.ingest.SmsInbox
import com.expenses.parser.SmsMessage
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * An [SmsInbox] that can also hand out the inbox a page at a time, so a long scan never holds every message text in
 * memory at once.
 */
interface PagedSmsInbox : SmsInbox {
    /** True when the app may read SMS. Without it every read is empty. */
    fun canRead(): Boolean

    /** How many inbox messages were received at or after [from] and before [until] (no upper end when null). */
    suspend fun count(from: Instant, until: Instant?): Int

    /**
     * Calls [onPage] with the inbox messages received at or after [from] and before [until] (no upper end when
     * null), oldest first, at most [pageSize] at a time. The next page is read only after [onPage] returns, and a
     * page is not kept once [onPage] is done with it.
     */
    suspend fun forEachPage(
        from: Instant,
        until: Instant?,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        onPage: suspend (List<InboxSms>) -> Unit,
    )

    override suspend fun read(since: Instant): List<InboxSms> = buildList {
        forEachPage(since, until = null) { page -> addAll(page) }
    }

    /** Defaults for paged reading. */
    companion object {
        /** Messages per page: small enough to keep memory low, large enough for duplicates to meet in one page. */
        const val DEFAULT_PAGE_SIZE: Int = 200
    }
}

/**
 * Reads `content://sms/inbox` with the READ_SMS permission. Only the columns `_id`, `address`, `body` and `date` are
 * read. Pages are read with a keyset on (`date`, `_id`), so messages that arrive during a scan neither shift nor
 * repeat a page. Nothing is logged or kept: the text only passes through to the caller.
 *
 * @param context any context; only its application context is kept.
 * @param io the dispatcher for the content provider queries.
 */
class AndroidSmsInbox(context: Context, private val io: CoroutineDispatcher) : PagedSmsInbox {
    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    override fun canRead(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    override suspend fun count(from: Instant, until: Instant?): Int {
        if (!canRead()) return 0
        return withContext(io) {
            val (selection, args) = range(from, until)
            queryOrNull(arrayOf(Telephony.Sms._ID), selection, args, sortOrder = null)?.use { it.count } ?: 0
        }
    }

    override suspend fun forEachPage(
        from: Instant,
        until: Instant?,
        pageSize: Int,
        onPage: suspend (List<InboxSms>) -> Unit,
    ) {
        require(pageSize > 0) { "pageSize must be more than 0" }
        if (!canRead()) return
        var after: Pair<Long, Long>? = null
        while (true) {
            val page = withContext(io) { readPage(from, until, after, pageSize) } ?: return
            if (page.rows.isNotEmpty()) onPage(page.rows)
            if (page.scanned < pageSize) return
            after = page.last
        }
    }

    /**
     * One page after [after] (date and id of the last row of the previous page). Null when the provider could not be
     * read, for example because the permission was taken back during the scan.
     */
    private fun readPage(from: Instant, until: Instant?, after: Pair<Long, Long>?, pageSize: Int): Page? {
        val (rangeSelection, rangeArgs) = range(from, until)
        val selection: String
        val args: Array<String>
        if (after == null) {
            selection = rangeSelection
            args = rangeArgs
        } else {
            val (date, id) = after
            val dateColumn = Telephony.Sms.DATE
            selection = "$rangeSelection AND ($dateColumn > ? OR ($dateColumn = ? AND ${Telephony.Sms._ID} > ?))"
            args = rangeArgs + arrayOf(date.toString(), date.toString(), id.toString())
        }
        val sortOrder = "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC LIMIT $pageSize"
        return queryOrNull(COLUMNS, selection, args, sortOrder)?.use { cursor -> readRows(cursor) }
    }

    private fun readRows(cursor: Cursor): Page {
        val idColumn = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
        val addressColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        val rows = ArrayList<InboxSms>(cursor.count)
        var last: Pair<Long, Long>? = null
        var scanned = 0
        while (cursor.moveToNext()) {
            scanned++
            val id = cursor.getLong(idColumn)
            val date = cursor.getLong(dateColumn)
            last = date to id
            // A message with no sender cannot be from a bank; it is left out but still moves the page on.
            val address = cursor.getString(addressColumn) ?: continue
            val body = cursor.getString(bodyColumn).orEmpty()
            rows += InboxSms(providerId = id, message = SmsMessage(sender = address, body = body, receivedAtMillis = date))
        }
        return Page(rows, scanned, last)
    }

    private fun range(from: Instant, until: Instant?): Pair<String, Array<String>> =
        if (until == null) {
            "${Telephony.Sms.DATE} >= ?" to arrayOf(from.toEpochMilli().toString())
        } else {
            "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?" to
                arrayOf(from.toEpochMilli().toString(), until.toEpochMilli().toString())
        }

    private fun queryOrNull(
        projection: Array<String>,
        selection: String,
        args: Array<String>,
        sortOrder: String?,
    ): Cursor? =
        try {
            resolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, selection, args, sortOrder)
        } catch (_: SecurityException) {
            null
        }

    /**
     * One page of rows.
     *
     * @param rows the messages that can be read.
     * @param scanned how many provider rows the page covered, including rows left out.
     * @param last date and id of the last row covered, where the next page starts.
     */
    private class Page(val rows: List<InboxSms>, val scanned: Int, val last: Pair<Long, Long>?)

    private companion object {
        val COLUMNS = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
    }
}
