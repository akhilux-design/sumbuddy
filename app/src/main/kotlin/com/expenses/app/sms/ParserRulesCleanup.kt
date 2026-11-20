package com.expenses.app.sms

import com.expenses.app.data.category.CategoryGuesser
import com.expenses.app.data.db.AccountDao
import com.expenses.app.data.db.ExpenseDao
import com.expenses.app.data.db.ReadSmsDao
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.ReadOutcome
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.data.repository.TransactionRunner
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.parser.Direction
import com.expenses.parser.ParseResult
import com.expenses.parser.SkipReason
import com.expenses.parser.SmsMessage
import com.expenses.parser.SmsParser
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Once per [RULES_VERSION], after an update: reads the stored money in rows again with the current parser and removes
 * the ones it now skips as points, as if they had never been read. Removal is permanent and a real payment lost is
 * worse than a store credit left in the list, so a row is removed only when every one of these holds (lead decisions
 * 2026-11-19 and 2026-11-20, the closed rule of round 6):
 *
 * 1. It is money in (CREDIT and ADDED, as stored: listed, never counted). Never a debit, and never a Needs review item.
 * 2. No bank, card issuer or wallet sent it, by any of the parser's tests ([SmsParser.isFromBank]: a header the parser
 *    knows, a header code that reads as a bank's, or a bank's sign-off).
 * 3. The parser now skips it as [SkipReason.REWARD_POINTS]: its amount is labelled as points, coins, miles or a store
 *    voucher right after it ("Rs.200 loyalty points have been credited"). Store credits, store wallets and points
 *    "worth" rupees that are not labelled that way stay money in and are never removed.
 * 4. The person has not acted on it: no note, not split, only for Me, not in a group; and its amount, currency,
 *    direction, payee and UPI address are exactly what the SMS reads as ([SmsParser.readIgnoringSkips]), so a payee the
 *    person typed keeps the row even when the SMS prints it.
 * 5. Its category is the one the keyword guess gives the payee, leaving out the person's "always this category"
 *    rules, and no such rule gives that payee another category (a category the person picked keeps the row, also when
 *    they later made a rule for the same category).
 *
 * A row of an account the person switched off keeps no SMS text and cannot be read again, so it stays too. Each removed
 * message stays marked read, now as SKIPPED, so it is never offered again; an account only removed rows referred to
 * goes with them unless the person renamed it or switched it off.
 *
 * Idempotent: the version is stored only after the removal committed, so a run stopped half way runs again on the next
 * start and finds the same rows. Message text is never logged.
 *
 * @param parser the current SMS parser.
 * @param transactions runs the removal as one transaction.
 * @param expenseDao stored payments.
 * @param expenses for the person's "always this category" rules, to tell whether they chose a row's category.
 * @param accountDao accounts.
 * @param readSmsDao the record of messages read.
 * @param settings where the last cleaned version is kept.
 * @param parsing the dispatcher parsing runs on, never the main thread.
 */
class ParserRulesCleanup(
    private val parser: SmsParser,
    private val transactions: TransactionRunner,
    private val expenseDao: ExpenseDao,
    private val expenses: ExpenseRepository,
    private val accountDao: AccountDao,
    private val readSmsDao: ReadSmsDao,
    private val settings: SettingsRepository,
    private val parsing: CoroutineDispatcher,
) {
    /** Runs the clean-up when this version's has not run yet. Returns how many money in rows it removed. */
    suspend fun runIfNeeded(): Int {
        if (settings.cleanedParserRules() >= RULES_VERSION) return 0
        val removed = removeNotMoney()
        settings.setCleanedParserRules(RULES_VERSION)
        return removed
    }

    private suspend fun removeNotMoney(): Int {
        val rows = expenseDao.untouchedSmsMoneyIn()
        val notMoneyRows = withContext(parsing) {
            rows.filter { isUntouchedMoneyIn(it) && isNotMoney(it.smsSender!!, it.originalMessage!!, it.receivedAt()) && readsAsStored(it) }
        }
        val unedited = notMoneyRows.filter { hasGuessedCategory(it) }
        if (unedited.isEmpty()) return 0
        return transactions.run { unedited.sumOf { removeRow(it) } }
    }

    /**
     * Tests 2 and 3: no bank, card issuer or wallet sent the message, and the parser now skips it as one of
     * [REMOVING_REASONS]. Parses, so it runs on [parsing].
     */
    private fun isNotMoney(sender: String, body: String, receivedAt: Instant): Boolean {
        val sms = SmsMessage(sender, body, receivedAt.toEpochMilli())
        if (parser.isFromBank(sms)) return false
        val result = parser.parse(sms)
        return result is ParseResult.Skipped && result.reason in REMOVING_REASONS
    }

    /**
     * Test 1 and the part of test 4 the query of [ExpenseDao.untouchedSmsMoneyIn] already holds, checked again here so
     * the removal never rests on that query alone: read from SMS with its text, money in as stored (never a debit), no
     * note, not split, only for Me, not in a group.
     */
    private fun isUntouchedMoneyIn(row: Expense): Boolean =
        row.source == ExpenseSource.SMS && row.originalMessage != null && row.smsSender != null &&
            row.note == null && row.groupExpenseId == null && row.scope == ExpenseScope.ME &&
            row.myShareMinor == row.amountMinor &&
            row.direction == Direction.CREDIT && row.status == ExpenseStatus.ADDED

    /**
     * Test 4: the row still holds exactly what the SMS reads as: amount, currency, direction, payee and UPI address.
     * Anything else means the person changed it (or an older version read it differently), so the row stays. Parses,
     * so it runs on [parsing].
     */
    private fun readsAsStored(row: Expense): Boolean {
        val read = parser.readIgnoringSkips(SmsMessage(row.smsSender!!, row.originalMessage!!, row.receivedAt().toEpochMilli()))
            ?: return false
        return read.amountMinor == row.amountMinor && read.currency == row.currency && read.direction == row.direction &&
            read.counterparty == row.counterparty && read.vpa == row.counterpartyVpa
    }

    /**
     * Test 5: the category is the keyword guess for the payee without the person's rules, and no rule of theirs gives
     * that payee another category.
     */
    private suspend fun hasGuessedCategory(row: Expense): Boolean {
        val guess = CategoryGuesser.guess(row.counterparty, row.counterpartyVpa)
        return row.category == guess && expenses.guessCategory(row.counterparty, row.counterpartyVpa) == guess
    }

    private fun Expense.receivedAt(): Instant = smsReceivedAt ?: occurredAt

    /** Removes [row] when it is still exactly as read (the person may have acted on it meanwhile). Returns 1 or 0. */
    private suspend fun removeRow(row: Expense): Int {
        if (expenseDao.get(row.id) != row) return 0
        expenseDao.delete(row.id)
        markSkipped(row.smsId, row.smsReceivedAt, row.smsSender)
        row.accountId?.let { accountDao.deleteIfUnusedAndUntouched(it) }
        return 1
    }

    private suspend fun markSkipped(smsId: Long?, receivedAt: Instant?, sender: String?) {
        if (smsId != null && receivedAt != null && sender != null) {
            readSmsDao.setOutcome(smsId, receivedAt, sender, ReadOutcome.SKIPPED)
        }
    }

    /** The version of the parser rules and what a run removes. */
    companion object {
        /**
         * Raise this when a parser change means stored rows should be read again. 1: reward points, coins, miles and
         * store balances (2026-11-18; not shipped yet, so its rules were changed in place on 2026-11-19 and 2026-11-20).
         */
        const val RULES_VERSION: Int = 1

        /** The skips that remove a stored money in row that no bank sent. */
        val REMOVING_REASONS: Set<SkipReason> = setOf(SkipReason.REWARD_POINTS)
    }
}
