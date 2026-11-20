package com.expenses.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.expenses.parser.InstrumentType
import java.time.Instant

/**
 * A bank account, card, wallet or UPI ID the person's payments come from. Accounts are created by the SMS reader
 * the first time a message names them, and the person can switch each one off.
 *
 * @param id row id, 0 before the row is inserted.
 * @param bank bank or issuer display name as the parser gives it ("HDFC Bank"), or null when unknown.
 * @param type what kind of account it is.
 * @param last4 last digits as printed in the SMS ("4521"), or null when the SMS does not print them.
 * @param upiId the person's own UPI ID when the account is a UPI ID ("arjun@okhdfc"), otherwise null.
 * @param displayName the name the person gave it, or null to show the bank name.
 * @param tracked false when the person switched it off: its new payments are not offered as detected payments.
 * @param firstSeenAt when the first SMS about it was read.
 */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["bank", "type", "last4"])],
)
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bank: String?,
    val type: AccountType,
    val last4: String?,
    val upiId: String? = null,
    val displayName: String? = null,
    val tracked: Boolean = true,
    val firstSeenAt: Instant,
)

/** The kind of an [Account]. */
enum class AccountType {
    /** A savings or current account. */
    BANK_ACCOUNT,

    /** A credit card. Its bills are kept as [CardBill]s. */
    CREDIT_CARD,

    /** A debit card. */
    DEBIT_CARD,

    /** A wallet such as Amazon Pay or Paytm. */
    WALLET,

    /** The person's own UPI ID. */
    UPI_ID,

    /** The SMS did not say what kind of account it is. */
    OTHER,
    ;

    /** Helpers for turning parser values into account types. */
    companion object {
        /** The account type for an instrument type read by the parser. */
        fun from(instrument: InstrumentType): AccountType = when (instrument) {
            InstrumentType.BANK_ACCOUNT -> BANK_ACCOUNT
            InstrumentType.CREDIT_CARD -> CREDIT_CARD
            InstrumentType.DEBIT_CARD -> DEBIT_CARD
            InstrumentType.WALLET -> WALLET
            InstrumentType.UNKNOWN -> OTHER
        }
    }
}

/**
 * The result of [com.expenses.app.data.repository.AccountRepository.findOrCreate].
 *
 * @param account the stored account.
 * @param isNew true when this call created it.
 */
data class FoundAccount(val account: Account, val isNew: Boolean)
