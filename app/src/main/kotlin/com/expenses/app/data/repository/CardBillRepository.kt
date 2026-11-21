package com.expenses.app.data.repository

import com.expenses.app.data.db.CardBillDao
import com.expenses.app.data.model.CardBill
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Credit card bills read from SMS. Never spending; used for the card bill view and due date reminders. */
class CardBillRepository(
    private val bills: CardBillDao,
    private val transactions: TransactionRunner,
) {
    /**
     * Stores a bill and returns its id. A bill of the same card (bank and last digits) with the same due date is
     * replaced, so a reminder after the statement updates it instead of adding a second bill. A bill that prints no
     * due date, or names neither the bank nor the card, is always stored as a new bill, so no statement is lost.
     *
     * The bill keeps the earlier SMS's arrival and id ([CardBill.receivedAt], [CardBill.smsId]) and takes the amounts
     * and the account from the later one, whichever order they are stored in: "add older payments" reads SMS from
     * before the tracking start after the newer ones were stored. It keeps whether it was reminded
     * ([CardBill.remindedAt]). The bill's cycle is counted from its first SMS, and a bank's own reminder must not
     * reset the app's (docs/card-bills.md D7).
     */
    suspend fun upsert(bill: CardBill): Long = transactions.run {
        val dueDate = bill.dueDate
        val identified = bill.bank != null || bill.cardLast4 != null
        val existing = if (dueDate != null && identified) bills.find(bill.bank, bill.cardLast4, dueDate) else null
        val toStore = if (existing != null) {
            // The stored row's receivedAt is its first SMS's; its amounts are from an SMS at or after that one.
            val older = bill.receivedAt.isBefore(existing.receivedAt)
            val first = if (older) bill else existing
            val latest = if (older) existing else bill
            latest.copy(
                id = existing.id,
                receivedAt = first.receivedAt,
                smsId = first.smsId,
                remindedAt = existing.remindedAt,
            )
        } else {
            bill
        }
        val id = bills.upsert(toStore)
        if (toStore.id != 0L) toStore.id else id
    }

    /** The bill with this id, or null. */
    suspend fun get(id: Long): CardBill? = bills.get(id)

    /** The bill with this id; null once it is gone. */
    fun observe(id: Long): Flow<CardBill?> = bills.observe(id)

    /** Every bill now, the latest due date first. */
    suspend fun all(): List<CardBill> = bills.all()

    /** Records when the automatic reminder of bill [id] was posted. */
    suspend fun setRemindedAt(id: Long, at: Instant) = bills.setRemindedAt(id, at)

    /** Every bill, the latest due date first. */
    fun observeAll(): Flow<List<CardBill>> = bills.observeAll()

    /** Bills due on or after [from], soonest first. */
    fun observeDueFrom(from: LocalDate): Flow<List<CardBill>> = bills.observeDueFrom(from)

    /** Bills of one card account, the latest due date first. */
    fun observeForAccount(accountId: Long): Flow<List<CardBill>> = bills.observeForAccount(accountId)

    /** Deletes the bills whose SMS arrived before [before]. */
    suspend fun deleteBefore(before: Instant) = bills.deleteBefore(before)
}
