package com.expenses.app.data.repository

import androidx.room.withTransaction
import com.expenses.app.data.db.AppDatabase

/**
 * Runs several repository writes as one database transaction: either all of them are stored or none is. The SMS
 * reader uses it to store what one message gave and mark the message read together, so a message is handled
 * exactly once even if the app is stopped half way.
 */
interface TransactionRunner {
    /** Runs [block] inside one transaction and returns its result. Calls may be nested. */
    suspend fun <T> run(block: suspend () -> T): T
}

/** A [TransactionRunner] backed by the app's Room database. */
class RoomTransactionRunner(private val database: AppDatabase) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}
