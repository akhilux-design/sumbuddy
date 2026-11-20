package com.expenses.app.data.repository

import androidx.room.Room
import com.expenses.app.data.db.AppDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.robolectric.RuntimeEnvironment

/** The time zone the repository tests use: India, where the app is used. */
val TestZone: ZoneId = ZoneId.of("Asia/Kolkata")

/** A clock fixed at 16 Nov 2026, 12:00 in India. */
val TestClock: Clock = Clock.fixed(Instant.parse("2026-11-16T06:30:00Z"), TestZone)

/** An in-memory database with every repository on top of it, for one test. */
class TestDatabase(val clock: Clock = TestClock) {
    /** The database; close it after the test. */
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val transactions = RoomTransactionRunner(db)

    /** Expenses. */
    val expenses = ExpenseRepository(db.expenseDao(), db.categoryRuleDao(), transactions, clock)

    /** Accounts. */
    val accounts = AccountRepository(db.accountDao(), transactions, clock)

    /** Card bills. */
    val cardBills = CardBillRepository(db.cardBillDao(), transactions)

    /** Review items. */
    val reviews = ReviewRepository(db.reviewDao())

    /** Read SMS ids. */
    val readSms = ReadSmsRepository(db.readSmsDao())

    /** Closes the database. */
    fun close() = db.close()
}

/** The instant of [day] of November 2026 at [hour]:[minute] in India. */
fun nov(day: Int, hour: Int = 12, minute: Int = 0): Instant =
    LocalDateTime.of(2026, 11, day, hour, minute).atZone(TestZone).toInstant()
