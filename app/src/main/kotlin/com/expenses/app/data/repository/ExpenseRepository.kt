package com.expenses.app.data.repository

import com.expenses.app.data.category.Category
import com.expenses.app.data.category.CategoryGuesser
import com.expenses.app.data.db.CategoryRuleDao
import com.expenses.app.data.db.ExpenseDao
import com.expenses.app.data.model.CategoryRule
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.data.model.DayExpenses
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.ExpenseWithAccount
import com.expenses.app.data.model.PaidWith
import com.expenses.parser.Direction
import com.expenses.parser.Duplicates
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Expenses and payments: what the person spent, what waits for a decision, and every change they make.
 *
 * Reads are [Flow]s that emit again whenever the data changes; writes are main-safe suspend functions. Months and
 * days are in the time zone of [clock].
 *
 * Month rule: a month's spending is the sum of [Expense.myShareMinor] over expenses that are ADDED, DEBIT and in
 * rupees, whose [Expense.occurredAt] falls in the month. DETECTED and IGNORED payments never count. Credits
 * (refunds, cashback, money received) are listed but never count, and never reduce the total. Both scopes count:
 * a FAMILY expense also counts in the payer's own month.
 *
 * Sharing (docs/family.md 7.4): every write that can change whether a payment is shared in the family month, or what
 * is shared of it, tells [personalChanges] inside its own transaction ([PersonalChangeListener.onChanged]), and
 * [delete] and [deleteSmsBefore] tell it before rows go ([PersonalChangeListener.onDeleting]).
 *
 * @param personalChanges the listener, read on each write (the app's is made after this repository).
 */
class ExpenseRepository(
    private val expenses: ExpenseDao,
    private val rules: CategoryRuleDao,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val personalChanges: () -> PersonalChangeListener = { PersonalChangeListener.None },
) {
    /** What the person spent in [month], in paise (see the month rule above). */
    fun observeMonthTotal(month: YearMonth): Flow<Long> {
        val (from, to) = bounds(month)
        return expenses.observeSpent(from, to, Expense.INR)
    }

    /** Every ADDED expense of [month], debits and credits, newest first. */
    fun observeMonth(month: YearMonth): Flow<List<ExpenseWithAccount>> {
        val (from, to) = bounds(month)
        return expenses.observeAdded(from, to)
    }

    /** The ADDED expenses of [month] grouped by day, newest day first; days with no expense are left out. */
    fun observeByDay(month: YearMonth): Flow<List<DayExpenses>> = observeMonth(month).map { list ->
        list.groupBy { it.expense.occurredAt.atZone(clock.zone).toLocalDate() }
            .map { (date, items) -> DayExpenses(date, items, items.sumOf { spentShare(it.expense) }) }
    }

    /** The counted spending of [month] per category, largest first. Categories with nothing spent are left out. */
    fun observeCategoryTotals(month: YearMonth): Flow<List<CategoryTotal>> {
        val (from, to) = bounds(month)
        return expenses.observeCategoryTotals(from, to, Expense.INR)
    }

    /** The newest [limit] ADDED expenses, debits and credits. */
    fun observeLatest(limit: Int): Flow<List<ExpenseWithAccount>> = expenses.observeLatest(limit)

    /** The newest [limit] payments the person ignored. */
    fun observeIgnored(limit: Int): Flow<List<ExpenseWithAccount>> = expenses.observeIgnored(limit)

    /**
     * Payments read from SMS that wait for Add, Split or Ignore, newest first. Payments from an account the person
     * switched off are left out (and come back if it is switched on again).
     */
    fun observeDetected(): Flow<List<ExpenseWithAccount>> = expenses.observeDetected()

    /**
     * The Review payments stack: debits read from SMS that wait for a decision, oldest first. Like [observeDetected],
     * payments of an account the person switched off are left out; money in is never in it.
     */
    fun observeWaiting(): Flow<List<ExpenseWithAccount>> = expenses.observeWaitingDebits()

    /**
     * Adds the waiting payments [ids] to the person's expenses in one transaction, each with the category it has now
     * (the suggested one, or the one the person chose on the card). Ids that are no longer waiting debits (added or
     * ignored elsewhere meanwhile, deleted, or money in) are left alone.
     *
     * @return the ids that were added; pass them to [backToWaiting] to undo.
     */
    suspend fun addWaiting(ids: Collection<Long>): List<Long> = transactions.run {
        val waiting = ids.distinct().chunked(MAX_IDS_PER_QUERY).flatMap { expenses.waitingDebitsAmong(it) }
        waiting.chunked(MAX_IDS_PER_QUERY).forEach { expenses.setStatusAll(it, ExpenseStatus.ADDED) }
        changed(waiting)
        waiting
    }

    /**
     * Ignores the waiting payments [ids] in one transaction, dropping their SMS text as [ignore] does. Ids that are
     * no longer waiting debits are left alone.
     *
     * @return the ids that were ignored; pass them to [backToWaiting] to undo.
     */
    suspend fun ignoreWaiting(ids: Collection<Long>): List<Long> = transactions.run {
        val waiting = ids.distinct().chunked(MAX_IDS_PER_QUERY).flatMap { expenses.waitingDebitsAmong(it) }
        waiting.chunked(MAX_IDS_PER_QUERY).forEach { expenses.ignoreAll(it) }
        changed(waiting)
        waiting
    }

    /**
     * Undo on Review payments: the payments [ids] read from SMS that were added or ignored are DETECTED again, with
     * the category they had. Their notification does not come back. An ignored payment comes back without its SMS
     * text: it was dropped when it was ignored and is not kept anywhere for an undo.
     *
     * @return the ids that are waiting again.
     */
    suspend fun backToWaiting(ids: Collection<Long>): List<Long> = transactions.run {
        val decided = ids.distinct().chunked(MAX_IDS_PER_QUERY).flatMap { expenses.decidedSmsAmong(it) }
        decided.chunked(MAX_IDS_PER_QUERY).forEach { expenses.setStatusAll(it, ExpenseStatus.DETECTED) }
        changed(decided)
        decided
    }

    /** One expense with its account; emits null once it is deleted or when the id is unknown. */
    fun observe(id: Long): Flow<ExpenseWithAccount?> = expenses.observe(id)

    /** One expense, or null. */
    suspend fun get(id: Long): Expense? = expenses.get(id)

    /**
     * The personal row linked to group expense [groupExpenseId] (the person's own payment, or the mirror of their
     * share when someone else paid), or null when the person has no row for it (they neither paid nor have a share).
     * The Group page opens Expense detail of this row.
     */
    suspend fun findByGroupExpense(groupExpenseId: String): Expense? = expenses.findByGroupExpense(groupExpenseId)

    /**
     * Stores a new expense as given and returns its id. Used by the SMS reader for detected payments. Throws
     * [android.database.sqlite.SQLiteConstraintException] when an expense from the same SMS is already stored.
     */
    suspend fun insert(expense: Expense): Long = expenses.insert(expense)

    /**
     * Adds a detected payment to the person's expenses with their choices from Add to expenses. When
     * [alwaysThisCategory] is true and the payment names a merchant, later payments to that merchant get [category]
     * and wait with [scope] pre-selected ("Swiggy is always Food · Family", docs/family.md 8.5).
     */
    suspend fun add(
        id: Long,
        category: Category,
        scope: ExpenseScope = ExpenseScope.ME,
        note: String? = null,
        alwaysThisCategory: Boolean = false,
    ) {
        transactions.run {
            expenses.add(id, category, scope, note?.takeIf { it.isNotBlank() })
            if (alwaysThisCategory) {
                expenses.get(id)?.counterparty?.let { rememberCategory(it, category, scope) }
            }
            changed(listOf(id))
        }
    }

    /**
     * Marks a payment as not an expense. The row is kept, so the same SMS is not offered again, but its SMS text is
     * dropped: only expenses keep their original message.
     */
    suspend fun ignore(id: Long) = transactions.run {
        expenses.ignore(id)
        changed(listOf(id))
    }

    /** Takes back Add (the Undo on Expense added): the payment is DETECTED again and waits for a decision. */
    suspend fun undoAdd(id: Long) = transactions.run {
        expenses.setStatus(id, ExpenseStatus.DETECTED)
        changed(listOf(id))
    }

    /** Changes the category of one expense. */
    suspend fun setCategory(id: Long, category: Category) = transactions.run {
        expenses.setCategory(id, category)
        changed(listOf(id))
    }

    /** Replaces an expense with an edited copy (same id). */
    suspend fun update(expense: Expense) = transactions.run {
        expenses.update(expense)
        changed(listOf(expense.id))
    }

    /** Deletes an expense. A Family payment shared in the family month leaves it (docs/family.md 7.3). */
    suspend fun delete(id: Long) = transactions.run {
        expenses.get(id)?.let { personalChanges().onDeleting(listOf(it)) }
        expenses.delete(id)
    }

    /**
     * Stores an expense the person typed in: a rupee debit, ADDED straight away, with source MANUAL. Returns its id.
     *
     * @param amountMinor the amount in paise, more than 0.
     * @param counterparty what it was for or who was paid, or null.
     * @param category its category.
     * @param occurredAt when it happened; now when not given.
     * @param paidWith how it was paid.
     * @param note the person's note, or null.
     * @param scope who it counts for.
     */
    suspend fun addManual(
        amountMinor: Long,
        counterparty: String?,
        category: Category,
        occurredAt: Instant = clock.instant(),
        paidWith: PaidWith = PaidWith.CASH,
        note: String? = null,
        scope: ExpenseScope = ExpenseScope.ME,
    ): Long {
        require(amountMinor > 0) { "amountMinor must be more than 0" }
        return transactions.run {
            val id = expenses.insert(
                Expense(
                    amountMinor = amountMinor,
                    direction = Direction.DEBIT,
                    counterparty = counterparty?.trim()?.takeIf { it.isNotEmpty() },
                    category = category,
                    occurredAt = occurredAt,
                    source = ExpenseSource.MANUAL,
                    status = ExpenseStatus.ADDED,
                    paidWith = paidWith,
                    note = note?.takeIf { it.isNotBlank() },
                    scope = scope,
                ),
            )
            changed(listOf(id))
            id
        }
    }

    /**
     * Stored payments that may be the same payment as a newly read SMS: same direction, amount and currency, and
     * either the same [referenceId] or an SMS received within [window] of [receivedAt]. The SMS reader decides with
     * the same rules as [Duplicates.keepBankCopies] which one to keep.
     */
    suspend fun findPossibleCopies(
        direction: Direction,
        amountMinor: Long,
        currency: String,
        receivedAt: Instant,
        referenceId: String?,
        window: Duration = Duration.ofMillis(Duplicates.DEFAULT_WINDOW_MILLIS),
    ): List<Expense> = expenses.findPossibleCopies(
        direction = direction,
        amountMinor = amountMinor,
        currency = currency,
        referenceId = referenceId,
        from = receivedAt.minus(window),
        to = receivedAt.plus(window),
    )

    /**
     * Deletes the payments read from SMS that arrived before [before], whatever the person decided about them. Those
     * shared in the family month leave it (docs/family.md 7.3).
     */
    suspend fun deleteSmsBefore(before: Instant) = transactions.run {
        val shared = expenses.familyIdsOfSmsBefore(before).mapNotNull { expenses.get(it) }
        if (shared.isNotEmpty()) personalChanges().onDeleting(shared)
        expenses.deleteSmsBefore(before)
    }

    /**
     * The category for a payment to [counterparty] (UPI address [vpa]): the person's "always this category" choice
     * for that merchant when there is one, otherwise [CategoryGuesser]'s guess.
     */
    suspend fun guessCategory(counterparty: String?, vpa: String? = null): Category {
        val key = counterparty?.let(CategoryGuesser::merchantKey)?.takeIf { it.isNotEmpty() }
        return key?.let { rules.get(it)?.category } ?: CategoryGuesser.guess(counterparty, vpa)
    }

    /**
     * Remembers that payments to [counterparty] always get [category] and, when [scope] is given, wait with that scope
     * pre-selected ("Do this every time", docs/family.md 8.5). With no [scope], a scope the rule already had stays.
     */
    suspend fun rememberCategory(counterparty: String, category: Category, scope: ExpenseScope? = null) {
        val key = CategoryGuesser.merchantKey(counterparty)
        if (key.isEmpty()) return
        rules.upsert(CategoryRule(key, category, scope ?: rules.get(key)?.scope))
    }

    /**
     * Who a new payment to [counterparty] counts for while it waits: the scope of the person's "Do this every time"
     * rule for that merchant, else [ExpenseScope.ME] (docs/family.md 8.5). It is only pre-selected: a payment is never
     * added without a tap.
     */
    suspend fun guessScope(counterparty: String?): ExpenseScope {
        val key = counterparty?.let(CategoryGuesser::merchantKey)?.takeIf { it.isNotEmpty() } ?: return ExpenseScope.ME
        return rules.get(key)?.scope ?: ExpenseScope.ME
    }

    /** Tells the listener that rows [ids] changed (inside the caller's transaction). */
    private suspend fun changed(ids: Collection<Long>) {
        if (ids.isNotEmpty()) personalChanges().onChanged(ids)
    }

    private fun spentShare(expense: Expense): Long =
        if (expense.status == ExpenseStatus.ADDED && expense.direction == Direction.DEBIT && expense.currency == Expense.INR) {
            expense.myShareMinor
        } else {
            0
        }

    private fun bounds(month: YearMonth): Pair<Instant, Instant> {
        val zone = clock.zone
        return month.atDay(1).atStartOfDay(zone).toInstant() to month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
    }

    private companion object {
        /** SQLite allows 999 bound values per statement; ids are passed in chunks below that. */
        const val MAX_IDS_PER_QUERY = 900
    }
}
