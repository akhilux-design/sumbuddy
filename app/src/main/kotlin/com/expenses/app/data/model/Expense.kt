package com.expenses.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.expenses.app.data.category.Category
import com.expenses.parser.Channel
import com.expenses.parser.Direction
import java.time.Instant
import java.time.LocalDate

/**
 * One payment: read from a bank SMS or typed in by hand.
 *
 * A debit read from SMS starts as [ExpenseStatus.DETECTED] and waits for the person to tap Add, Split or Ignore.
 * Only [ExpenseStatus.ADDED] debits in rupees count in the month, by [myShareMinor].
 *
 * @param id row id, 0 before the row is inserted.
 * @param amountMinor the whole payment in minor units (paise for rupees). Always positive.
 * @param currency ISO 4217 code. Only "INR" counts in month totals.
 * @param direction DEBIT is money out, CREDIT is money in (refunds, cashback, money received). Credits never count
 * as spending.
 * @param counterparty merchant or person for display ("Swiggy"), or null when unknown.
 * @param counterpartyVpa the other side's UPI address when the SMS prints it.
 * @param category the person's category for it; guessed when read, changeable later.
 * @param occurredAt when the payment happened: the date printed in the SMS, else the time the SMS arrived, else the
 * time the person entered.
 * @param source where it came from.
 * @param status whether the person has decided about it yet.
 * @param paidWith how it was paid.
 * @param accountId the [Account] it was paid from, or null (manual expenses, unknown account).
 * @param referenceId UPI reference, RRN or transaction id as printed; used to find second reports of one payment.
 * @param smsId the SMS provider's id of the message it was read from. With [smsReceivedAt] it is unique, so a message
 * gives at most one expense (the provider reuses the id of a deleted newest message).
 * @param smsSender the sender header of that message ("VM-HDFCBK").
 * @param smsReceivedAt when that message arrived; used for the 15 minute duplicate window.
 * @param originalMessage the SMS text, shown as "original message" on Expense detail. Stays on this phone only.
 * @param note the person's note.
 * @param myShareMinor the person's own share, in the same units as [amountMinor]. Equal to the amount unless the
 * payment is split.
 * @param scope whether it counts for the person only or also for their family.
 * @param lowConfidence true when the parser had to guess something, so the screens can ask the person to check it.
 * @param groupExpenseId the group expense this payment is linked to (docs/server.md 9.4): the split of the person's
 * own payment, or, for [ExpenseSource.GROUP], the expense someone else paid that the person has a share of. A group
 * expense is linked to at most one personal row.
 * @param familyExpenseId the family expense this payment is shared as (docs/family.md 7; version 6); only the payer's
 * own row. Set while the row is shared in the family month: FAMILY, ADDED, a rupee debit, not split. A family expense
 * is linked to at most one personal row. Null for everything else.
 */
@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["status", "occurredAt"]),
        Index(value = ["occurredAt"]),
        Index(value = ["accountId"]),
        Index(value = ["direction", "amountMinor", "smsReceivedAt"]),
        Index(value = ["referenceId"]),
        Index(value = ["smsId", "smsReceivedAt"], unique = true),
        Index(value = ["smsReceivedAt"]),
        Index(value = ["groupExpenseId"], unique = true),
        Index(value = ["familyExpenseId"], unique = true),
    ],
)
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long,
    val currency: String = INR,
    val direction: Direction,
    val counterparty: String?,
    val counterpartyVpa: String? = null,
    val category: Category,
    val occurredAt: Instant,
    val source: ExpenseSource,
    val status: ExpenseStatus,
    val paidWith: PaidWith = PaidWith.OTHER,
    val accountId: Long? = null,
    val referenceId: String? = null,
    val smsId: Long? = null,
    val smsSender: String? = null,
    val smsReceivedAt: Instant? = null,
    val originalMessage: String? = null,
    val note: String? = null,
    val myShareMinor: Long = amountMinor,
    val scope: ExpenseScope = ExpenseScope.ME,
    val lowConfidence: Boolean = false,
    val groupExpenseId: String? = null,
    val familyExpenseId: String? = null,
) {
    /** Constants for expenses. */
    companion object {
        /** The currency code of Indian rupees, the only currency counted in month totals. */
        const val INR: String = "INR"
    }
}

/** Where an [Expense] came from. */
enum class ExpenseSource {
    /** Read from a bank SMS on this phone. */
    SMS,

    /** Typed in by the person. */
    MANUAL,

    /**
     * A group expense someone else paid that the person has a share of: a mirror of synced group data, so the month
     * counts their share. Never sent to the server (docs/server.md 9.4).
     */
    GROUP,
}

/** Whether the person has decided about an [Expense]. */
enum class ExpenseStatus {
    /** Read from SMS and waiting for Add, Split or Ignore. Never counted. */
    DETECTED,

    /** In the person's expenses (or, for a credit, in their list). Debits count in the month. */
    ADDED,

    /** The person said it is not an expense. Kept so the same SMS is not offered again. */
    IGNORED,
}

/** Who an [Expense] counts for. */
enum class ExpenseScope {
    /** Only the person. */
    ME,

    /** The person and their family's month. Still counts in the person's own month. */
    FAMILY,
}

/** How an [Expense] was paid, as shown on Add expense and Expense detail. */
enum class PaidWith {
    /** Cash. Only for manual expenses. */
    CASH,

    /** UPI, including UPI Lite. */
    UPI,

    /** A credit or debit card. */
    CARD,

    /** Net banking, IMPS, NEFT, RTGS, cheque or autopay from a bank account. */
    BANK_TRANSFER,

    /** A wallet such as Amazon Pay or Paytm. */
    WALLET,

    /** An ATM withdrawal. */
    ATM,

    /** Not known. */
    OTHER,
    ;

    /** Helpers for turning parser values into payment methods. */
    companion object {
        /** How a payment was made, from the channel the parser read. */
        fun from(channel: Channel): PaidWith = when (channel) {
            Channel.UPI -> UPI
            Channel.CARD -> CARD
            Channel.NET_BANKING, Channel.IMPS, Channel.NEFT, Channel.RTGS, Channel.AUTOPAY, Channel.CHEQUE ->
                BANK_TRANSFER
            Channel.WALLET -> WALLET
            Channel.ATM -> ATM
            Channel.UNKNOWN -> OTHER
        }
    }
}

/**
 * An [Expense] with the [Account] it was paid from, for screens that show "HDFC card ••4521".
 *
 * @param expense the expense.
 * @param account its account, or null when it has none.
 */
data class ExpenseWithAccount(
    @Embedded val expense: Expense,
    @Relation(parentColumn = "accountId", entityColumn = "id") val account: Account?,
)

/**
 * The expenses of one day of a month, newest first.
 *
 * @param date the day in the phone's time zone.
 * @param expenses every ADDED expense of that day, debits and credits.
 * @param spentMinor what counts as spent that day: the person's share of ADDED rupee debits, in paise.
 */
data class DayExpenses(
    val date: LocalDate,
    val expenses: List<ExpenseWithAccount>,
    val spentMinor: Long,
)

/**
 * What one category adds up to in a month.
 *
 * @param category the category.
 * @param count how many counted expenses it has.
 * @param totalMinor the person's share of them, in paise.
 */
data class CategoryTotal(
    val category: Category,
    val count: Int,
    val totalMinor: Long,
)

/**
 * Who was paid, or who paid in, as the app shows it: the name the SMS prints, else the UPI ID it prints. Null when
 * the SMS names neither.
 */
val Expense.payee: String?
    get() = counterparty ?: counterpartyVpa

/**
 * A personal row shared as a family expense ([com.expenses.app.data.db.ExpenseDao.observeFamilyLinks]).
 *
 * @param id the personal row.
 * @param familyExpenseId the family expense it is shared as.
 */
data class FamilyLink(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "familyExpenseId") val familyExpenseId: String,
)
