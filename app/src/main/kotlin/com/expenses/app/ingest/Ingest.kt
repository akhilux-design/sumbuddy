package com.expenses.app.ingest

import com.expenses.app.data.model.Account
import com.expenses.app.data.model.CardBill
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ReviewItem
import com.expenses.parser.SmsMessage
import java.time.Instant

/**
 * One SMS from the phone's inbox with the id the SMS provider gave it.
 *
 * @param providerId the `_id` of the message in the SMS content provider; unique on this phone. It is the key for
 * exactly-once reading ([com.expenses.app.data.model.ReadSms]).
 * @param message sender, text and time of the message.
 * @param sentAt when the network's service centre stamped the message (the inbox's `date_sent`), or null when the SMS
 * app did not record it. It only tells which inbox message a new SMS broadcast was about ([SmsTimes]).
 */
data class InboxSms(val providerId: Long, val message: SmsMessage, val sentAt: Instant? = null)

/**
 * The two times of one inbox message, and nothing else of it.
 *
 * @param receivedAt when it arrived (the inbox's `date`), as in [SmsMessage.receivedAtMillis].
 * @param sentAt when the service centre stamped it ([InboxSms.sentAt]), or null when not recorded.
 */
data class SmsTimes(val receivedAt: Instant, val sentAt: Instant?)

/**
 * Reads SMS from the phone's inbox.
 *
 * Contract:
 * - Needs the READ_SMS permission; without it returns an empty list and never throws for that reason.
 * - Never logs, uploads or stores message text; it only returns it.
 * - Runs its I/O off the main thread itself, so it is safe to call from any coroutine.
 */
interface SmsInbox {
    /** Every inbox message received at or after [since], oldest first. */
    suspend fun read(since: Instant): List<InboxSms>
}

/**
 * Turns SMS into stored payments, accounts, card bills and review items.
 *
 * Contract for [ingest]:
 * - Exactly once: messages whose [InboxSms.providerId] is already in [com.expenses.app.data.repository.ReadSmsRepository]
 *   are ignored. Every other message is marked read with its outcome in the same transaction
 *   ([com.expenses.app.data.repository.TransactionRunner]) that stores what it gave.
 * - Each message is parsed with [com.expenses.parser.SmsParser]. `Skipped` results (OTPs, promotions, self transfers,
 *   biller receipts, personal messages) are never stored, only counted and marked read as SKIPPED.
 * - Payments are run through [com.expenses.parser.Duplicates.keepBankCopies] over all messages of the call, and
 *   then against payments already stored ([com.expenses.app.data.repository.ExpenseRepository.findPossibleCopies]),
 *   with the same rules: the bank's SMS wins; two bank SMS are one payment only with the same reference. A copy is
 *   marked read as DUPLICATE. When the stored copy came from a sender that is not a bank and a new bank SMS reports
 *   the same payment, the stored row is updated in place with the bank SMS's fields, keeping its id, status,
 *   category, note and scope, and the new message is marked read as PAYMENT. No new row is created.
 * - A DEBIT is stored with status DETECTED, never ADDED: the person decides with Add, Split or Ignore. A CREDIT is
 *   stored ADDED (it is listed but never counts as spending). The category comes from
 *   [com.expenses.app.data.repository.ExpenseRepository.guessCategory]; `occurredAt` is the date printed in the SMS,
 *   else the time it arrived; `smsReceivedAt`, `smsId`, `smsSender` and `originalMessage` are filled.
 * - The account comes from [com.expenses.app.data.repository.AccountRepository.findOrCreate]. Payments from an
 *   account the person switched off are still stored; screens hide them.
 * - `CardBill` results are stored with [com.expenses.app.data.repository.CardBillRepository.upsert].
 * - `Unreadable` results become open [ReviewItem]s with the message text.
 * - Never logs message text.
 */
interface IngestPipeline {
    /** Reads [messages] (in any order) and stores what they give. Returns what was new. */
    suspend fun ingest(messages: List<InboxSms>): IngestResult
}

/**
 * What one [IngestPipeline.ingest] call stored.
 *
 * @param detected new DETECTED debits, oldest first; each needs a "payment detected" notification.
 * @param credits new credits stored as ADDED (refunds, cashback, money received).
 * @param newAccounts accounts seen for the first time.
 * @param cardBills card bills stored or updated.
 * @param reviewItems new Needs review items.
 * @param duplicates messages dropped as a second report of one payment.
 * @param skipped messages that were not about a payment and were not stored.
 * @param alreadyRead messages ignored because they had been read before, by an earlier call or by another reader
 * while this call ran.
 * @param firstRead the times of the messages this call marked read itself: the messages it read for the first time,
 * whether or not they gave anything to store (an OTP counts), oldest first. Empty when it marked none read, so every
 * message was read before or by another reader meanwhile. `IncomingSmsWorker` uses it to tell that its SMS has
 * reached the inbox.
 */
data class IngestResult(
    val detected: List<Expense> = emptyList(),
    val credits: List<Expense> = emptyList(),
    val newAccounts: List<Account> = emptyList(),
    val cardBills: List<CardBill> = emptyList(),
    val reviewItems: List<ReviewItem> = emptyList(),
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val alreadyRead: Int = 0,
    val firstRead: List<SmsTimes> = emptyList(),
)
