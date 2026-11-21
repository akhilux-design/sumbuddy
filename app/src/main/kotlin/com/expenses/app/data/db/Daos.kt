package com.expenses.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.CardBill
import com.expenses.app.data.model.CategoryRule
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.ExpenseWithAccount
import com.expenses.app.data.model.FamilyLink
import com.expenses.app.data.model.OutcomeCount
import com.expenses.app.data.model.ReadOutcome
import com.expenses.app.data.model.ReadSms
import com.expenses.app.data.model.ReviewItem
import com.expenses.app.data.model.SmsKey
import com.expenses.parser.Direction
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Database access for [Account]. Use [com.expenses.app.data.repository.AccountRepository] instead. */
@Dao
interface AccountDao {
    /** Inserts an account and returns its id. */
    @Insert
    suspend fun insert(account: Account): Long

    /** Replaces an account. */
    @Update
    suspend fun update(account: Account)

    /** The account with this id, or null. */
    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: Long): Account?

    /** The account matching bank, type and last digits, comparing nulls as equal. */
    @Query("SELECT * FROM accounts WHERE bank IS :bank AND type = :type AND last4 IS :last4 LIMIT 1")
    suspend fun find(bank: String?, type: AccountType, last4: String?): Account?

    /** Every account of this bank (nulls compare as equal), oldest first. */
    @Query("SELECT * FROM accounts WHERE bank IS :bank ORDER BY firstSeenAt, id")
    suspend fun findByBank(bank: String?): List<Account>

    /** The account with this UPI ID, or null. */
    @Query("SELECT * FROM accounts WHERE upiId = :upiId LIMIT 1")
    suspend fun findByUpiId(upiId: String): Account?

    /** Every account, oldest first. */
    @Query("SELECT * FROM accounts ORDER BY firstSeenAt, id")
    fun observeAll(): Flow<List<Account>>

    /** The account with this id, or null. */
    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observe(id: Long): Flow<Account?>

    /** Switches tracking on or off. */
    @Query("UPDATE accounts SET tracked = :tracked WHERE id = :id")
    suspend fun setTracked(id: Long, tracked: Boolean)

    /** Sets the person's name for the account; null shows the bank name. */
    @Query("UPDATE accounts SET displayName = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String?)

    /** Deletes the accounts that no payment and no card bill refers to. */
    @Query(
        "DELETE FROM accounts WHERE id NOT IN (SELECT accountId FROM expenses WHERE accountId IS NOT NULL) " +
            "AND id NOT IN (SELECT accountId FROM card_bills WHERE accountId IS NOT NULL)",
    )
    suspend fun deleteUnused()

    /**
     * Deletes account [id] when no payment and no card bill refers to it and the person never renamed it, switched it
     * off or gave it a UPI ID.
     */
    @Query(
        "DELETE FROM accounts WHERE id = :id AND displayName IS NULL AND upiId IS NULL AND tracked = 1 " +
            "AND id NOT IN (SELECT accountId FROM expenses WHERE accountId IS NOT NULL) " +
            "AND id NOT IN (SELECT accountId FROM card_bills WHERE accountId IS NOT NULL)",
    )
    suspend fun deleteIfUnusedAndUntouched(id: Long)
}

/** Database access for [Expense]. Use [com.expenses.app.data.repository.ExpenseRepository] instead. */
@Dao
interface ExpenseDao {
    /** Inserts an expense and returns its id. Fails when its smsId is already stored. */
    @Insert
    suspend fun insert(expense: Expense): Long

    /** Replaces an expense. */
    @Update
    suspend fun update(expense: Expense)

    /** Deletes the expense with this id. */
    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: Long)

    /** The expense with this id, or null. */
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun get(id: Long): Expense?

    /** The expense with this id and its account, or null. */
    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    fun observe(id: Long): Flow<ExpenseWithAccount?>

    /** Sets the status of one expense. */
    @Query("UPDATE expenses SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: ExpenseStatus)

    /** Marks one expense IGNORED and drops the SMS text it was read from: a payment that is not an expense keeps none. */
    @Query("UPDATE expenses SET status = 'IGNORED', originalMessage = NULL WHERE id = :id")
    suspend fun ignore(id: Long)

    /** Sets the category of one expense. */
    @Query("UPDATE expenses SET category = :category WHERE id = :id")
    suspend fun setCategory(id: Long, category: Category)

    /** Adds an expense: sets its status to ADDED with the person's choices. */
    @Query("UPDATE expenses SET status = 'ADDED', category = :category, scope = :scope, note = :note WHERE id = :id")
    suspend fun add(id: Long, category: Category, scope: ExpenseScope, note: String?)

    /** The person's share of ADDED debits in [currency] between [from] (included) and [to] (excluded). */
    @Query(
        "SELECT COALESCE(SUM(myShareMinor), 0) FROM expenses WHERE status = 'ADDED' AND direction = 'DEBIT' " +
            "AND currency = :currency AND occurredAt >= :from AND occurredAt < :to",
    )
    fun observeSpent(from: Instant, to: Instant, currency: String): Flow<Long>

    /**
     * ADDED expenses, debits and credits, between [from] and [to], newest first. Credits of an account the person
     * switched off are left out; debits they added stay, as they count in the month.
     */
    @Transaction
    @Query(
        "SELECT e.* FROM expenses e LEFT JOIN accounts a ON a.id = e.accountId " +
            "WHERE e.status = 'ADDED' AND e.occurredAt >= :from AND e.occurredAt < :to " +
            "AND (e.direction = 'DEBIT' OR a.id IS NULL OR a.tracked = 1) ORDER BY e.occurredAt DESC, e.id DESC",
    )
    fun observeAdded(from: Instant, to: Instant): Flow<List<ExpenseWithAccount>>

    /** The newest ADDED expenses, debits and credits, leaving out credits of accounts the person switched off. */
    @Transaction
    @Query(
        "SELECT e.* FROM expenses e LEFT JOIN accounts a ON a.id = e.accountId " +
            "WHERE e.status = 'ADDED' AND (e.direction = 'DEBIT' OR a.id IS NULL OR a.tracked = 1) " +
            "ORDER BY e.occurredAt DESC, e.id DESC LIMIT :limit",
    )
    fun observeLatest(limit: Int): Flow<List<ExpenseWithAccount>>

    /**
     * Every expense of account [accountId] with [occurredAt][Expense.occurredAt] from [from] (included) to [to]
     * (excluded), oldest first: every direction and status (the card bill view, docs/card-bills.md 2.1).
     */
    @Query(
        "SELECT * FROM expenses WHERE accountId = :accountId AND occurredAt >= :from AND occurredAt < :to " +
            "ORDER BY occurredAt, id",
    )
    fun observeOnAccount(accountId: Long, from: Instant, to: Instant): Flow<List<Expense>>

    /** The newest IGNORED payments. */
    @Transaction
    @Query("SELECT * FROM expenses WHERE status = 'IGNORED' ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    fun observeIgnored(limit: Int): Flow<List<ExpenseWithAccount>>

    /** Per category, the count and the person's share of ADDED debits in [currency] between [from] and [to]. */
    @Query(
        "SELECT category, COUNT(*) AS count, SUM(myShareMinor) AS totalMinor FROM expenses " +
            "WHERE status = 'ADDED' AND direction = 'DEBIT' AND currency = :currency " +
            "AND occurredAt >= :from AND occurredAt < :to GROUP BY category ORDER BY totalMinor DESC, category",
    )
    fun observeCategoryTotals(from: Instant, to: Instant, currency: String): Flow<List<CategoryTotal>>

    /** DETECTED expenses whose account is tracked (or unknown), newest first. */
    @Transaction
    @Query(
        "SELECT e.* FROM expenses e LEFT JOIN accounts a ON a.id = e.accountId " +
            "WHERE e.status = 'DETECTED' AND (a.id IS NULL OR a.tracked = 1) ORDER BY e.occurredAt DESC, e.id DESC",
    )
    fun observeDetected(): Flow<List<ExpenseWithAccount>>

    /** DETECTED debits whose account is tracked (or unknown), oldest first: the Review payments stack. */
    @Transaction
    @Query(
        "SELECT e.* FROM expenses e LEFT JOIN accounts a ON a.id = e.accountId " +
            "WHERE e.status = 'DETECTED' AND e.direction = 'DEBIT' AND (a.id IS NULL OR a.tracked = 1) " +
            "ORDER BY e.occurredAt ASC, e.id ASC",
    )
    fun observeWaitingDebits(): Flow<List<ExpenseWithAccount>>

    /** Of the expenses [ids], those that are DETECTED debits. Pass at most 900 ids. */
    @Query("SELECT id FROM expenses WHERE id IN (:ids) AND status = 'DETECTED' AND direction = 'DEBIT'")
    suspend fun waitingDebitsAmong(ids: List<Long>): List<Long>

    /** Of the expenses [ids], those read from SMS that are ADDED or IGNORED. Pass at most 900 ids. */
    @Query("SELECT id FROM expenses WHERE id IN (:ids) AND source = 'SMS' AND status IN ('ADDED', 'IGNORED')")
    suspend fun decidedSmsAmong(ids: List<Long>): List<Long>

    /** Sets the status of the expenses [ids]. Pass at most 900 ids. */
    @Query("UPDATE expenses SET status = :status WHERE id IN (:ids)")
    suspend fun setStatusAll(ids: List<Long>, status: ExpenseStatus)

    /** Marks the expenses [ids] IGNORED and drops their SMS text. Pass at most 900 ids. */
    @Query("UPDATE expenses SET status = 'IGNORED', originalMessage = NULL WHERE id IN (:ids)")
    suspend fun ignoreAll(ids: List<Long>)

    /**
     * Stored payments that may be the same payment as a new SMS: same direction, amount and currency, and either the
     * same reference or an SMS received between [from] and [to].
     */
    @Query(
        "SELECT * FROM expenses WHERE direction = :direction AND amountMinor = :amountMinor AND currency = :currency " +
            "AND ((referenceId IS NOT NULL AND referenceId = :referenceId) OR (smsReceivedAt >= :from AND smsReceivedAt <= :to))",
    )
    suspend fun findPossibleCopies(
        direction: Direction,
        amountMinor: Long,
        currency: String,
        referenceId: String?,
        from: Instant,
        to: Instant,
    ): List<Expense>

    /** Deletes the payments read from SMS that arrived before [before]. */
    @Query("DELETE FROM expenses WHERE source = 'SMS' AND smsReceivedAt < :before")
    suspend fun deleteSmsBefore(before: Instant)

    /**
     * Money in read from SMS, with its SMS text, as it was stored (CREDIT and ADDED: listed, never counted) and not acted
     * on: no note, not split, not in Family or a group. Only these rows can be removed by
     * [com.expenses.app.sms.ParserRulesCleanup].
     */
    @Query(
        "SELECT * FROM expenses WHERE source = 'SMS' AND originalMessage IS NOT NULL AND smsSender IS NOT NULL " +
            "AND groupExpenseId IS NULL AND scope = 'ME' AND note IS NULL AND myShareMinor = amountMinor " +
            "AND direction = 'CREDIT' AND status = 'ADDED'",
    )
    suspend fun untouchedSmsMoneyIn(): List<Expense>

    /** The personal row linked to a group expense, or null. */
    @Query("SELECT * FROM expenses WHERE groupExpenseId = :groupExpenseId")
    suspend fun findByGroupExpense(groupExpenseId: String): Expense?

    /**
     * Payments that may be the person's own payment of a group or family expense restored from the server: SMS or
     * typed rupee debits of [amountMinor] between [from] and [to], waiting or added, and linked to neither a group
     * expense nor a family expense.
     */
    @Query(
        "SELECT * FROM expenses WHERE source IN ('SMS', 'MANUAL') AND direction = 'DEBIT' AND currency = 'INR' " +
            "AND status IN ('DETECTED', 'ADDED') AND groupExpenseId IS NULL AND familyExpenseId IS NULL " +
            "AND amountMinor = :amountMinor AND occurredAt >= :from AND occurredAt < :to",
    )
    suspend fun findUnlinkedPayments(amountMinor: Long, from: Instant, to: Instant): List<Expense>

    // ---- Family (version 6, docs/family.md 6.2) ----

    /** The personal row shared as family expense [familyExpenseId], or null. */
    @Query("SELECT * FROM expenses WHERE familyExpenseId = :familyExpenseId")
    suspend fun findByFamilyExpense(familyExpenseId: String): Expense?

    /** Every row tagged Family or shared as a family expense: what [com.expenses.app.groups.data.FamilyLinker] looks at. */
    @Query("SELECT * FROM expenses WHERE scope = 'FAMILY' OR familyExpenseId IS NOT NULL ORDER BY occurredAt, id")
    suspend fun familyCandidates(): List<Expense>

    /** Unlinks the rows shared as the family expenses [familyExpenseIds]. Pass at most 900 ids. */
    @Query("UPDATE expenses SET familyExpenseId = NULL WHERE familyExpenseId IN (:familyExpenseIds)")
    suspend fun unlinkFamily(familyExpenseIds: List<String>)

    /** Unlinks every row from its family expense (a different account signed in). Scopes stay as they are. */
    @Query("UPDATE expenses SET familyExpenseId = NULL WHERE familyExpenseId IS NOT NULL")
    suspend fun unlinkAllFamily()

    /** The ids of the rows read from SMS that arrived before [before] and are shared as a family expense. */
    @Query("SELECT id FROM expenses WHERE source = 'SMS' AND smsReceivedAt < :before AND familyExpenseId IS NOT NULL")
    suspend fun familyIdsOfSmsBefore(before: Instant): List<Long>

    /**
     * How many rows would be shared once a family exists and are not shared yet (docs/family.md 7.1): FAMILY, ADDED,
     * rupee debits, not split, not linked, not a mirror of group data, of 1 to [maxMinor] paise, between [from]
     * (included) and [to] (excluded). Emits again on every change.
     */
    @Query(
        "SELECT COUNT(*) FROM expenses WHERE scope = 'FAMILY' AND status = 'ADDED' AND direction = 'DEBIT' " +
            "AND currency = 'INR' AND groupExpenseId IS NULL AND familyExpenseId IS NULL AND source != 'GROUP' " +
            "AND amountMinor >= 1 AND amountMinor <= :maxMinor AND occurredAt >= :from AND occurredAt < :to",
    )
    fun observeShareableFamilyCount(maxMinor: Long, from: Instant, to: Instant): Flow<Int>

    /** Every row shared as a family expense, as (row id, family expense id); emits again on every change. */
    @Query("SELECT id, familyExpenseId FROM expenses WHERE familyExpenseId IS NOT NULL")
    fun observeFamilyLinks(): Flow<List<FamilyLink>>

    /** Deletes every mirror of a group expense someone else paid (a different account signed in). */
    @Query("DELETE FROM expenses WHERE source = 'GROUP'")
    suspend fun deleteGroupMirrors()

    /** Unlinks every personal payment from its group expense. */
    @Query("UPDATE expenses SET groupExpenseId = NULL WHERE groupExpenseId IS NOT NULL")
    suspend fun unlinkAllGroupExpenses()
}

/** Database access for [CardBill]. Use [com.expenses.app.data.repository.CardBillRepository] instead. */
@Dao
interface CardBillDao {
    /** Inserts or replaces a bill by id and returns its id. */
    @Upsert
    suspend fun upsert(bill: CardBill): Long

    /** The bill of this card (bank and last digits, nulls equal) with this due date, or null. */
    @Query("SELECT * FROM card_bills WHERE bank IS :bank AND cardLast4 IS :cardLast4 AND dueDate = :dueDate LIMIT 1")
    suspend fun find(bank: String?, cardLast4: String?, dueDate: LocalDate): CardBill?

    /** Deletes the bills whose SMS arrived before [before]. */
    @Query("DELETE FROM card_bills WHERE receivedAt < :before")
    suspend fun deleteBefore(before: Instant)

    /** Every bill, the latest due date first. */
    @Query("SELECT * FROM card_bills ORDER BY dueDate DESC, receivedAt DESC")
    fun observeAll(): Flow<List<CardBill>>

    /** Bills due on or after [from], soonest first. */
    @Query("SELECT * FROM card_bills WHERE dueDate >= :from ORDER BY dueDate, id")
    fun observeDueFrom(from: LocalDate): Flow<List<CardBill>>

    /** Bills of one card account, the latest due date first. */
    @Query("SELECT * FROM card_bills WHERE accountId = :accountId ORDER BY dueDate DESC, receivedAt DESC")
    fun observeForAccount(accountId: Long): Flow<List<CardBill>>

    /** The bill with this id, or null. */
    @Query("SELECT * FROM card_bills WHERE id = :id")
    suspend fun get(id: Long): CardBill?

    /** The bill with this id, or null once it is gone; emits again on every change of the table. */
    @Query("SELECT * FROM card_bills WHERE id = :id")
    fun observe(id: Long): Flow<CardBill?>

    /** Every bill, the latest due date first. */
    @Query("SELECT * FROM card_bills ORDER BY dueDate DESC, receivedAt DESC")
    suspend fun all(): List<CardBill>

    /** Records when the automatic reminder of bill [id] was posted. */
    @Query("UPDATE card_bills SET remindedAt = :at WHERE id = :id")
    suspend fun setRemindedAt(id: Long, at: Instant?)
}

/** Database access for [ReviewItem]. Use [com.expenses.app.data.repository.ReviewRepository] instead. */
@Dao
interface ReviewDao {
    /** Inserts an item and returns its id. */
    @Insert
    suspend fun insert(item: ReviewItem): Long

    /** The item with this id, or null. */
    @Query("SELECT * FROM review_items WHERE id = :id")
    suspend fun get(id: Long): ReviewItem?

    /** Open items, newest first. */
    @Query("SELECT * FROM review_items WHERE resolved = 0 ORDER BY receivedAt DESC, id DESC")
    fun observeOpen(): Flow<List<ReviewItem>>

    /** How many items are open. */
    @Query("SELECT COUNT(*) FROM review_items WHERE resolved = 0")
    fun observeOpenCount(): Flow<Int>

    /** Marks an item resolved and clears its SMS text. */
    @Query("UPDATE review_items SET resolved = 1, body = '' WHERE id = :id")
    suspend fun resolve(id: Long)

    /** Deletes the items whose SMS arrived before [before]. */
    @Query("DELETE FROM review_items WHERE receivedAt < :before")
    suspend fun deleteBefore(before: Instant)
}

/** Database access for [ReadSms]. Use [com.expenses.app.data.repository.ReadSmsRepository] instead. */
@Dao
interface ReadSmsDao {
    /** Records messages as read. A message already recorded keeps its first outcome. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<ReadSms>)

    /** The messages already read among those with the provider ids [ids]. Pass at most 900 ids. */
    @Query("SELECT smsId, receivedAt, sender FROM read_sms WHERE smsId IN (:ids)")
    suspend fun readAmong(ids: List<Long>): List<SmsKey>

    /** Forgets the messages that arrived before [before], so a later scan of that period reads them again. */
    @Query("DELETE FROM read_sms WHERE receivedAt < :before")
    suspend fun deleteBefore(before: Instant)

    /** How many messages were read, per outcome. */
    @Query("SELECT outcome, COUNT(*) AS count FROM read_sms GROUP BY outcome")
    fun observeCounts(): Flow<List<OutcomeCount>>

    /** Changes the recorded outcome of one message already read. */
    @Query("UPDATE read_sms SET outcome = :outcome WHERE smsId = :smsId AND receivedAt = :receivedAt AND sender = :sender")
    suspend fun setOutcome(smsId: Long, receivedAt: Instant, sender: String, outcome: ReadOutcome)
}

/** Database access for [CategoryRule]. Use [com.expenses.app.data.repository.ExpenseRepository] instead. */
@Dao
interface CategoryRuleDao {
    /** Stores or replaces the rule for a merchant. */
    @Upsert
    suspend fun upsert(rule: CategoryRule)

    /** The rule for this merchant key, or null. */
    @Query("SELECT * FROM category_rules WHERE merchantKey = :merchantKey")
    suspend fun get(merchantKey: String): CategoryRule?
}
