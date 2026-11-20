package com.expenses.app.ui.screens.splitwise

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.expenses.splitwise.SplitwiseCsv
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A file the person picked for Import from Splitwise, read once into memory. It is never written to disk, logged or
 * sent (docs/splitwise-import.md 6), and the Uri is not kept.
 *
 * @param name the file's display name, shown on screen and used for the group name only.
 * @param bytes at most [SplitwiseCsv.MAX_BYTES] bytes of it.
 * @param truncated true when the file is longer than [SplitwiseCsv.MAX_BYTES].
 * @param readable false when the file could not be opened or read at all.
 */
class PickedFile(val name: String, val bytes: ByteArray, val truncated: Boolean, val readable: Boolean = true)

/**
 * Reads the document [uri] the system's picker returned (`ACTION_OPEN_DOCUMENT`): its display name and up to
 * [SplitwiseCsv.MAX_BYTES] + 1 bytes, on [io]. The picker's one-time grant is enough: no storage permission, and no
 * persistable grant is taken.
 */
suspend fun readPicked(resolver: ContentResolver, uri: Uri, io: CoroutineDispatcher = Dispatchers.IO): PickedFile = withContext(io) {
    val name = nameOf(resolver, uri)
    try {
        val stream = resolver.openInputStream(uri) ?: return@withContext PickedFile(name, ByteArray(0), truncated = false, readable = false)
        stream.use { input ->
            val (bytes, truncated) = readUpTo(input, SplitwiseCsv.MAX_BYTES)
            PickedFile(name, bytes, truncated)
        }
    } catch (_: IOException) {
        PickedFile(name, ByteArray(0), truncated = false, readable = false)
    } catch (_: SecurityException) {
        PickedFile(name, ByteArray(0), truncated = false, readable = false)
    } catch (_: IllegalArgumentException) {
        PickedFile(name, ByteArray(0), truncated = false, readable = false)
    }
}

/**
 * The display name of the document [uri] the system's picker returned, asked on [io] without opening the file, so
 * "Reading the file" (135) can name it while [readPicked] still reads. When the provider gives no name it is the last
 * part of the Uri's path, as in [readPicked].
 */
suspend fun pickedName(resolver: ContentResolver, uri: Uri, io: CoroutineDispatcher = Dispatchers.IO): String =
    withContext(io) { nameOf(resolver, uri) }

private fun nameOf(resolver: ContentResolver, uri: Uri): String =
    displayName(resolver, uri) ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()

/**
 * At most [limit] bytes of [input], and whether it holds more: one byte past [limit] is read to tell, never the rest.
 */
internal fun readUpTo(input: InputStream, limit: Int): Pair<ByteArray, Boolean> {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0
    while (total <= limit) {
        val want = minOf(buffer.size, limit + 1 - total)
        val read = input.read(buffer, 0, want)
        if (read < 0) break
        out.write(buffer, 0, read)
        total += read
    }
    val all = out.toByteArray()
    return if (all.size > limit) all.copyOf(limit) to true else all to false
}

/** The picked document's display name, or null when the provider gives none. */
private fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }
} catch (_: SecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private const val BUFFER_BYTES = 64 * 1024
