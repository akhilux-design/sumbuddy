package com.expenses.app.data.repository

import com.expenses.app.data.db.ReadSmsDao
import com.expenses.app.data.model.ReadCounts
import com.expenses.app.data.model.ReadSms
import com.expenses.app.data.model.SmsKey
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Which SMS were already read, so each message is handled exactly once. Holds ids and outcomes, never text. */
class ReadSmsRepository(private val readSms: ReadSmsDao) {
    /**
     * Which of [messages] were already read. A message counts as read only when its provider id, the time it arrived
     * and its sender all match a stored row (see [SmsKey]).
     */
    suspend fun alreadyRead(messages: Collection<SmsKey>): Set<SmsKey> {
        val stored = messages.map { it.smsId }.distinct().chunked(MAX_IDS_PER_QUERY)
            .flatMapTo(HashSet()) { readSms.readAmong(it) }
        return messages.filterTo(HashSet()) { it in stored }
    }

    /** Records messages as read. A message recorded before keeps its first outcome. */
    suspend fun markRead(entries: List<ReadSms>) = readSms.insertAll(entries)

    /** Forgets the messages that arrived before [before], so a later scan of that period reads them again. */
    suspend fun forgetBefore(before: Instant) = readSms.deleteBefore(before)

    /** How many messages were read, by outcome. */
    fun observeCounts(): Flow<ReadCounts> =
        readSms.observeCounts().map { rows -> ReadCounts(rows.associate { it.outcome to it.count }) }

    private companion object {
        /** SQLite allows 999 bound values per statement; stay below it. */
        const val MAX_IDS_PER_QUERY = 900
    }
}
