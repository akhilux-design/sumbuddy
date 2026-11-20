package com.expenses.app.data.repository

import com.expenses.app.data.db.CardBillDao
import com.expenses.app.data.model.CardBill
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Credit card bills read from SMS. Never spending; used for the card bill view and due dates. */
class CardBillRepository(
    private val bills: CardBillDao,
    private val transactions: TransactionRunner,
) {
    /**
     * Stores a bill and returns its id. A bill of the same card (bank and last digits) with the same due date is
     * replaced, so a reminder after the statement updates it instead of adding a second bill. A bill that prints no
     * due date, or names neither the bank nor the card, is always stored as a new bill, so no statement is lost.
     */
    suspend fun upsert(bill: CardBill): Long = transactions.run {
        val dueDate = bill.dueDate
        val identified = bill.bank != null || bill.cardLast4 != null
        val existing = if (dueDate != null && identified) bills.find(bill.bank, bill.cardLast4, dueDate) else null
        val toStore = if (existing != null) bill.copy(id = existing.id) else bill
        val id = bills.upsert(toStore)
        if (toStore.id != 0L) toStore.id else id
    }

    /** Every bill, the latest due date first. */
    fun observeAll(): Flow<List<CardBill>> = bills.observeAll()

    /** Bills due on or after [from], soonest first. */
    fun observeDueFrom(from: LocalDate): Flow<List<CardBill>> = bills.observeDueFrom(from)

    /** Bills of one card account, the latest due date first. */
    fun observeForAccount(accountId: Long): Flow<List<CardBill>> = bills.observeForAccount(accountId)

    /** Deletes the bills whose SMS arrived before [before]. */
    suspend fun deleteBefore(before: Instant) = bills.deleteBefore(before)
}
