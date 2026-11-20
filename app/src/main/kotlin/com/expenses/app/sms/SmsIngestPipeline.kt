package com.expenses.app.sms

import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.CardBill
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.model.ReadOutcome
import com.expenses.app.data.model.ReadSms
import com.expenses.app.data.model.ReviewItem
import com.expenses.app.data.model.SmsKey
import com.expenses.app.data.repository.AccountRepository
import com.expenses.app.data.repository.CardBillRepository
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.data.repository.ReadSmsRepository
import com.expenses.app.data.repository.ReviewRepository
import com.expenses.app.data.repository.TransactionRunner
import com.expenses.app.ingest.InboxSms
import com.expenses.app.ingest.IngestPipeline
import com.expenses.app.ingest.IngestResult
import com.expenses.parser.Banks
import com.expenses.parser.Confidence
import com.expenses.parser.Direction
import com.expenses.parser.Duplicates
import com.expenses.parser.ParseResult
import com.expenses.parser.ParsedCardBill
import com.expenses.parser.ParsedPayment
import com.expenses.parser.ReadPayment
import com.expenses.parser.SmsMessage
import com.expenses.parser.SmsParser
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The [IngestPipeline] of the app: parses SMS with [SmsParser] and stores what they give, following the contract on
 * [IngestPipeline] and in `docs/architecture.md`.
 *
 * One [ingest] call is one Room transaction: every write of the batch and the [ReadSms] row of every message are
 * stored together or not at all, so a message is handled exactly once even when the app is stopped half way or two
 * readers run at the same time (the already-read check is repeated inside the transaction).
 *
 * Decisions this class makes:
 * - A debit is stored DETECTED and waits for Add, Split or Ignore. A credit (refund, cashback, salary, money
 *   received) is stored ADDED so it is listed for the record; the month rule never counts credits as spending.
 * - Payments of an account the person switched off are stored DETECTED like any other, but without their SMS text,
 *   and they are left out of [IngestResult.detected], so no notification is posted for them. Switching the account
 *   back on shows them again.
 * - A message is named by its provider id, arrival time and sender together ([SmsKey]), because the SMS provider
 *   reuses the id of a deleted newest message and renumbers messages after a restore.
 * - An account is made only when the SMS names a bank or prints last digits; an SMS that names neither gives a
 *   payment with no account.
 * - Only payments read from SMS are compared for duplicates; an expense typed in by hand is never merged.
 *
 * @param parser the SMS parser.
 * @param transactions runs one batch as one transaction.
 * @param expenses where payments are stored.
 * @param accounts where accounts are found or made.
 * @param cardBills where card bills are stored.
 * @param reviews where Needs review items are stored.
 * @param readSms which messages were read.
 * @param clock "now", for the time each message was read.
 * @param parsing the dispatcher that parsing runs on, never the main thread.
 * @param parserZone the time zone [parser] reads printed dates in, used to turn a due date back into a date.
 */
class SmsIngestPipeline(
    private val parser: SmsParser,
    private val transactions: TransactionRunner,
    private val expenses: ExpenseRepository,
    private val accounts: AccountRepository,
    private val cardBills: CardBillRepository,
    private val reviews: ReviewRepository,
    private val readSms: ReadSmsRepository,
    private val clock: Clock,
    private val parsing: CoroutineDispatcher,
    private val parserZone: ZoneId = PARSER_ZONE,
) : IngestPipeline {

    /**
     * Stores what [messages] give and returns what was new. [IngestResult.detected] holds the new DETECTED debits of
     * tracked accounts (and of payments with no account), oldest first: each needs a "payment detected"
     * notification.
     */
    override suspend fun ingest(messages: List<InboxSms>): IngestResult {
        if (messages.isEmpty()) return IngestResult()
        val unique = messages.distinctBy { it.key }
        val readBefore = readSms.alreadyRead(unique.map { it.key })
        val fresh = unique.filterNot { it.key in readBefore }
            .sortedWith(compareBy<InboxSms>({ it.message.receivedAtMillis }, { it.providerId }))
        val parsed = withContext(parsing) { fresh.map { Parsed(it, parser.parse(it.message)) } }
        return transactions.run { store(parsed, alreadyRead = messages.size - fresh.size) }
    }

    private suspend fun store(parsed: List<Parsed>, alreadyRead: Int): IngestResult {
        // Another reader may have stored some of these between the check above and this transaction.
        val readMeanwhile = readSms.alreadyRead(parsed.map { it.sms.key })
        val todo = parsed.filterNot { it.sms.key in readMeanwhile }
        val batch = Batch(clock.instant(), alreadyRead = alreadyRead + readMeanwhile.size)

        val readPayments = IdentityHashMap<Parsed, ReadPayment>()
        for (item in todo) {
            val result = item.result
            if (result is ParseResult.Payment) readPayments[item] = ReadPayment(item.sms.message, result.payment)
        }
        val kept: MutableSet<ReadPayment> = Collections.newSetFromMap(IdentityHashMap())
        kept += Duplicates.keepBankCopies(todo.mapNotNull { readPayments[it] })

        for (item in todo) {
            when (val result = item.result) {
                is ParseResult.Payment ->
                    if (readPayments.getValue(item) in kept) {
                        storePayment(item.sms, result.payment, batch)
                    } else {
                        batch.duplicate(item.sms)
                    }
                is ParseResult.CardBill -> storeCardBill(item.sms, result.bill, batch)
                is ParseResult.Unreadable -> storeReviewItem(item.sms, result.hint, batch)
                is ParseResult.Skipped -> batch.skipped(item.sms)
            }
        }
        readSms.markRead(batch.read)
        return batch.result()
    }

    private suspend fun storePayment(sms: InboxSms, payment: ParsedPayment, batch: Batch) {
        val message = sms.message
        val receivedAt = Instant.ofEpochMilli(message.receivedAtMillis)
        val fromBank = isBank(message.sender)
        val copies = expenses.findPossibleCopies(
            direction = payment.direction,
            amountMinor = payment.amountMinor,
            currency = payment.currency,
            receivedAt = receivedAt,
            referenceId = payment.referenceId,
        ).filter { it.source == ExpenseSource.SMS }

        // The same rules as Duplicates.keepBankCopies: the bank's SMS wins, and two bank SMS are one payment only
        // when they print the same reference. A stored row from the same sender at the same moment is this very
        // message read again under a new provider id (after an SMS restore or a new default SMS app).
        val isCopy = copies.any { stored ->
            !fromBank || (isBank(stored.smsSender) && sameReference(payment, stored)) ||
                (stored.smsSender == message.sender && stored.smsReceivedAt == receivedAt)
        }
        if (isCopy) {
            batch.duplicate(sms)
            return
        }

        val account = accountFor(payment.bank, AccountType.from(payment.instrumentType), payment.instrumentLast4, batch)
        // The person switched this account off: the row is kept so the message is not offered again, without its text.
        val keptText = message.body.takeIf { account?.tracked != false }
        val replaced = copies.firstOrNull { !isBank(it.smsSender) }
        if (replaced != null) {
            expenses.update(bankVersionOf(replaced, sms, payment, receivedAt, account))
            batch.stored(sms, ReadOutcome.PAYMENT)
            return
        }

        val expense = Expense(
            amountMinor = payment.amountMinor,
            currency = payment.currency,
            direction = payment.direction,
            counterparty = payment.counterparty,
            counterpartyVpa = payment.vpa,
            category = expenses.guessCategory(payment.counterparty, payment.vpa),
            occurredAt = occurredAt(payment, message),
            // "Do this every time" with Family: the payment waits with Family pre-selected; Add still needs a tap.
            scope = if (payment.direction == Direction.DEBIT) expenses.guessScope(payment.counterparty) else ExpenseScope.ME,
            source = ExpenseSource.SMS,
            status = if (payment.direction == Direction.DEBIT) ExpenseStatus.DETECTED else ExpenseStatus.ADDED,
            paidWith = PaidWith.from(payment.channel),
            accountId = account?.id,
            referenceId = payment.referenceId,
            smsId = sms.providerId,
            smsSender = message.sender,
            smsReceivedAt = receivedAt,
            originalMessage = keptText,
            lowConfidence = payment.confidence == Confidence.LOW,
        )
        val stored = expense.copy(id = expenses.insert(expense))
        batch.stored(sms, ReadOutcome.PAYMENT)
        when {
            stored.direction == Direction.CREDIT -> batch.credits += stored
            account?.tracked != false -> batch.detected += stored
        }
    }

    /**
     * The stored copy [stored] (from a sender that is not a bank) rewritten with the fields of the bank's SMS. The id,
     * the person's decision (status), category, note, scope and share stay as they were.
     */
    private fun bankVersionOf(
        stored: Expense,
        sms: InboxSms,
        payment: ParsedPayment,
        receivedAt: Instant,
        account: Account?,
    ): Expense = stored.copy(
        counterparty = payment.counterparty ?: stored.counterparty,
        counterpartyVpa = payment.vpa ?: stored.counterpartyVpa,
        occurredAt = occurredAt(payment, sms.message),
        paidWith = PaidWith.from(payment.channel),
        accountId = account?.id ?: stored.accountId,
        referenceId = payment.referenceId ?: stored.referenceId,
        smsId = sms.providerId,
        smsSender = sms.message.sender,
        smsReceivedAt = receivedAt,
        originalMessage = sms.message.body.takeIf { stored.status != ExpenseStatus.IGNORED && account?.tracked != false },
        lowConfidence = payment.confidence == Confidence.LOW,
    )

    private suspend fun storeCardBill(sms: InboxSms, bill: ParsedCardBill, batch: Batch) {
        val account = accountFor(bill.bank, AccountType.CREDIT_CARD, bill.cardLast4, batch)
        val cardBill = CardBill(
            accountId = account?.id,
            bank = bill.bank,
            cardLast4 = bill.cardLast4,
            totalDueMinor = bill.totalDueMinor,
            minimumDueMinor = bill.minimumDueMinor,
            dueDate = bill.dueDateMillis?.let { Instant.ofEpochMilli(it).atZone(parserZone).toLocalDate() },
            receivedAt = Instant.ofEpochMilli(sms.message.receivedAtMillis),
            smsId = sms.providerId,
        )
        batch.cardBills += cardBill.copy(id = cardBills.upsert(cardBill))
        batch.stored(sms, ReadOutcome.CARD_BILL)
    }

    private suspend fun storeReviewItem(sms: InboxSms, hint: String?, batch: Batch) {
        val item = ReviewItem(
            smsId = sms.providerId,
            sender = sms.message.sender,
            body = sms.message.body,
            receivedAt = Instant.ofEpochMilli(sms.message.receivedAtMillis),
            hint = hint,
        )
        batch.reviewItems += item.copy(id = reviews.add(item))
        batch.stored(sms, ReadOutcome.REVIEW)
    }

    /** The account for a payment or bill, made when it is new. Null when the SMS names neither a bank nor digits. */
    private suspend fun accountFor(bank: String?, type: AccountType, last4: String?, batch: Batch): Account? {
        if (bank == null && last4 == null) return null
        val found = accounts.findOrCreate(bank, type, last4)
        if (found.isNew) batch.newAccounts += found.account
        return found.account
    }

    /**
     * When the payment happened: the date and time the SMS prints, else the time it arrived. Most bank SMS print only
     * a date, which the parser reads as midnight; on the day the SMS arrived its arrival time is the better guess.
     */
    private fun occurredAt(payment: ParsedPayment, message: SmsMessage): Instant {
        val received = Instant.ofEpochMilli(message.receivedAtMillis)
        val printed = payment.occurredAtMillis?.let(Instant::ofEpochMilli) ?: return received
        val printedLocal = printed.atZone(parserZone)
        val dateOnly = printedLocal.toLocalTime() == LocalTime.MIDNIGHT
        return if (dateOnly && printedLocal.toLocalDate() == received.atZone(parserZone).toLocalDate()) received else printed
    }

    private fun isBank(sender: String?): Boolean = sender != null && Banks.fromSender(sender) != null

    private fun sameReference(payment: ParsedPayment, stored: Expense): Boolean =
        payment.referenceId != null && payment.referenceId == stored.referenceId

    /** One message and what the parser made of it. */
    private class Parsed(val sms: InboxSms, val result: ParseResult)

    /** What one batch stored, gathered while it runs. */
    private class Batch(private val readAt: Instant, private val alreadyRead: Int) {
        val detected = ArrayList<Expense>()
        val credits = ArrayList<Expense>()
        val newAccounts = ArrayList<Account>()
        val cardBills = ArrayList<CardBill>()
        val reviewItems = ArrayList<ReviewItem>()
        val read = ArrayList<ReadSms>()
        private var duplicates = 0
        private var skipped = 0

        fun stored(sms: InboxSms, outcome: ReadOutcome) {
            read += ReadSms(
                smsId = sms.providerId,
                receivedAt = Instant.ofEpochMilli(sms.message.receivedAtMillis),
                sender = sms.message.sender,
                readAt = readAt,
                outcome = outcome,
            )
        }

        fun duplicate(sms: InboxSms) {
            duplicates++
            stored(sms, ReadOutcome.DUPLICATE)
        }

        fun skipped(sms: InboxSms) {
            skipped++
            stored(sms, ReadOutcome.SKIPPED)
        }

        fun result(): IngestResult = IngestResult(
            detected = detected.toList(),
            credits = credits.toList(),
            newAccounts = newAccounts.toList(),
            cardBills = cardBills.toList(),
            reviewItems = reviewItems.toList(),
            duplicates = duplicates,
            skipped = skipped,
            alreadyRead = alreadyRead,
        )
    }

    /** Defaults of the pipeline. */
    companion object {
        /** The time zone `SmsParser()` reads printed dates in by default: India. */
        val PARSER_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    }
}

/** The key that names [InboxSms] among every message ever read (see [SmsKey]). */
private val InboxSms.key: SmsKey
    get() = SmsKey(providerId, Instant.ofEpochMilli(message.receivedAtMillis), message.sender)

/** The sum of two results, for a scan that ingests the inbox one page at a time. Lists keep their order. */
operator fun IngestResult.plus(other: IngestResult): IngestResult = IngestResult(
    detected = detected + other.detected,
    credits = credits + other.credits,
    newAccounts = newAccounts + other.newAccounts,
    cardBills = cardBills + other.cardBills,
    reviewItems = reviewItems + other.reviewItems,
    duplicates = duplicates + other.duplicates,
    skipped = skipped + other.skipped,
    alreadyRead = alreadyRead + other.alreadyRead,
)
