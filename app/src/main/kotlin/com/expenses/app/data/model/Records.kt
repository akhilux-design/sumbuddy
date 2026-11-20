package com.expenses.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.expenses.app.data.category.Category
import java.time.Instant
import java.time.LocalDate

/**
 * A credit card bill read from a statement or payment-due SMS. Never spending: it is shown in the card bill view and
 * used for due date reminders.
 *
 * @param id row id, 0 before the row is inserted.
 * @param accountId the card's [Account], or null when the SMS does not identify the card.
 * @param bank issuer display name, or null.
 * @param cardLast4 last digits of the card, or null.
 * @param totalDueMinor total amount due, in paise.
 * @param minimumDueMinor minimum amount due, in paise, when printed.
 * @param dueDate the due date, when printed.
 * @param receivedAt when the SMS arrived.
 * @param smsId the SMS provider's id of the message. With [receivedAt] it names one message: the provider reuses the
 * id of a deleted newest message.
 */
@Entity(
    tableName = "card_bills",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["accountId", "dueDate"]),
        Index(value = ["dueDate"]),
        Index(value = ["smsId", "receivedAt"], unique = true),
        Index(value = ["receivedAt"]),
    ],
)
data class CardBill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long?,
    val bank: String?,
    val cardLast4: String?,
    val totalDueMinor: Long,
    val minimumDueMinor: Long?,
    val dueDate: LocalDate?,
    val receivedAt: Instant,
    val smsId: Long?,
)

/**
 * A message that looks like a payment but could not be read for sure. Listed under "Needs review".
 *
 * The SMS text is kept only while the item is open, and only on this phone. Resolving the item clears it.
 *
 * @param id row id, 0 before the row is inserted.
 * @param smsId the SMS provider's id of the message. With [receivedAt] it names one message.
 * @param sender the sender header.
 * @param body the SMS text; empty once resolved.
 * @param receivedAt when the SMS arrived.
 * @param hint what the parser could not read ("amount"), or null.
 * @param resolved true once the person fixed it or said it is not a spend.
 */
@Entity(
    tableName = "review_items",
    indices = [
        Index(value = ["smsId", "receivedAt"], unique = true),
        Index(value = ["resolved", "receivedAt"]),
    ],
)
data class ReviewItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val smsId: Long,
    val sender: String,
    val body: String,
    val receivedAt: Instant,
    val hint: String?,
    val resolved: Boolean = false,
)

/**
 * Names one SMS of the phone's inbox. The provider's id alone is not enough: the SMS provider gives the id of a
 * deleted newest message to the next one, and gives new ids after a restore or a switch of the default SMS app. A
 * message is the same only when its id, the time it arrived and its sender all match.
 *
 * @param smsId the SMS provider's id of the message.
 * @param receivedAt when it arrived, as the provider reports it.
 * @param sender the sender header.
 */
data class SmsKey(
    @ColumnInfo(name = "smsId") val smsId: Long,
    @ColumnInfo(name = "receivedAt") val receivedAt: Instant,
    @ColumnInfo(name = "sender") val sender: String,
)

/**
 * Marks one SMS as read, so each message is handled exactly once. Holds no message text.
 *
 * @param smsId the SMS provider's id of the message.
 * @param receivedAt when the message arrived; with [smsId] and [sender] it names the message (see [SmsKey]).
 * @param sender the sender header.
 * @param readAt when it was read.
 * @param outcome what reading it gave.
 */
@Entity(
    tableName = "read_sms",
    primaryKeys = ["smsId", "receivedAt", "sender"],
    indices = [Index(value = ["outcome"]), Index(value = ["receivedAt"])],
)
data class ReadSms(
    val smsId: Long,
    val receivedAt: Instant,
    val sender: String,
    val readAt: Instant,
    val outcome: ReadOutcome,
)

/** What reading one SMS gave. Counted on "What we've read". */
enum class ReadOutcome {
    /** A payment, stored as an [Expense]. */
    PAYMENT,

    /** A card bill, stored as a [CardBill]. */
    CARD_BILL,

    /** Could not be read for sure, stored as a [ReviewItem]. */
    REVIEW,

    /** A second report of a payment already stored or read in the same scan. Not stored. */
    DUPLICATE,

    /** OTPs, promotions, personal messages and everything else that is not a payment. Not stored. */
    SKIPPED,
}

/**
 * How many messages were read, by outcome, for "What we've read".
 *
 * @param byOutcome the count of each outcome; outcomes never seen are missing.
 */
data class ReadCounts(val byOutcome: Map<ReadOutcome, Int>) {
    /** Every message read. */
    val total: Int get() = byOutcome.values.sum()

    /** Messages that gave a payment, a card bill or a review item. */
    val used: Int
        get() = (byOutcome[ReadOutcome.PAYMENT] ?: 0) + (byOutcome[ReadOutcome.CARD_BILL] ?: 0) +
            (byOutcome[ReadOutcome.REVIEW] ?: 0)

    /** Messages that were not stored at all. */
    val notStored: Int get() = total - used
}

/** One row of the count query behind [ReadCounts]. */
data class OutcomeCount(
    @ColumnInfo(name = "outcome") val outcome: ReadOutcome,
    @ColumnInfo(name = "count") val count: Int,
)

/**
 * The person's choice that a merchant always gets one category ("Swiggy is always Food"), and, since version 6, who
 * it counts for ("Swiggy is always Food · Family", docs/family.md 8.5).
 *
 * @param merchantKey the merchant name as [com.expenses.app.data.category.CategoryGuesser.merchantKey] gives it.
 * @param category the category it always gets.
 * @param scope the scope a new payment to the merchant waits with (pre-selected on Add; never added without a tap),
 * or null when the rule says nothing about Just me or Family (rules made before version 6).
 */
@Entity(tableName = "category_rules")
data class CategoryRule(
    @PrimaryKey val merchantKey: String,
    val category: Category,
    val scope: ExpenseScope? = null,
)
