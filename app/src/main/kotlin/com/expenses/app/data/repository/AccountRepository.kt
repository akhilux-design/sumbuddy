package com.expenses.app.data.repository

import com.expenses.app.data.db.AccountDao
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.FoundAccount
import java.time.Clock
import kotlinx.coroutines.flow.Flow

/** The person's accounts, cards, wallets and UPI IDs. Reads are [Flow]s; writes are main-safe suspend functions. */
class AccountRepository(
    private val accounts: AccountDao,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /** Every account, oldest first, tracked or not. */
    fun observeAll(): Flow<List<Account>> = accounts.observeAll()

    /** One account; null once it is gone or when the id is unknown. */
    fun observe(id: Long): Flow<Account?> = accounts.observe(id)

    /** One account, or null. */
    suspend fun get(id: Long): Account? = accounts.get(id)

    /**
     * The stored account for a payment's [bank], [type] and [last4] (or the person's own [upiId] when given), created
     * tracked when there is none yet. Nulls match nulls: an SMS with no last digits matches the account of that bank
     * and type that also has none.
     *
     * When the SMS does not say what kind of account it is ([AccountType.OTHER]), a known account of the same bank is
     * used instead of making a vague new one: the one with the same last digits, or, when no digits are printed (a UPI
     * Lite payment, for example), the bank's only bank account.
     *
     * When the SMS says it is a credit card (a typed spend, a card bill or due reminder, a payment received towards
     * the card) and the same bank and last digits are stored as an account of unknown kind, that account becomes the
     * credit card: its type is upgraded in place, so its payments, bills and label follow. Not when the bank also has a
     * bank account or debit card with those digits: then a new credit card is made. A known type is never changed back
     * to unknown or to another type.
     */
    suspend fun findOrCreate(bank: String?, type: AccountType, last4: String?, upiId: String? = null): FoundAccount =
        transactions.run {
            val existing = when {
                upiId != null -> accounts.findByUpiId(upiId)
                type == AccountType.OTHER -> accounts.find(bank, type, last4) ?: knownAccountFor(bank, last4)
                type == AccountType.CREDIT_CARD -> accounts.find(bank, type, last4) ?: upgradeToCreditCard(bank, last4)
                else -> accounts.find(bank, type, last4)
            }
            if (existing != null) {
                FoundAccount(existing, isNew = false)
            } else {
                val account = Account(bank = bank, type = type, last4 = last4, upiId = upiId, firstSeenAt = clock.instant())
                FoundAccount(account.copy(id = accounts.insert(account)), isNew = true)
            }
        }

    /**
     * Deletes the accounts that no stored payment or card bill refers to. Used when the onboarding scan starts again
     * from a later day and what was read before that day is dropped.
     */
    suspend fun deleteUnused() = accounts.deleteUnused()

    /** The single known account of [bank] that an SMS of unknown account kind belongs to, or null. */
    private suspend fun knownAccountFor(bank: String?, last4: String?): Account? {
        if (bank == null) return null
        val sameBank = accounts.findByBank(bank)
        val matches = if (last4 != null) {
            sameBank.filter { it.last4 == last4 }
        } else {
            sameBank.filter { it.type == AccountType.BANK_ACCOUNT }
        }
        return matches.singleOrNull()
    }

    /**
     * The account of unknown kind with this [bank] and [last4], stored again as a credit card, or null when there is
     * none. Both must be known: digits alone, or a bank alone, could belong to a different card. When the bank also
     * has a bank account or debit card with these digits, the account of unknown kind may hold that account's
     * payments, so it is left as it is and the caller makes a new credit card.
     */
    private suspend fun upgradeToCreditCard(bank: String?, last4: String?): Account? {
        if (bank == null || last4 == null) return null
        val sameDigits = accounts.findByBank(bank).filter { it.last4 == last4 }
        val vague = sameDigits.firstOrNull { it.type == AccountType.OTHER } ?: return null
        if (sameDigits.any { it.type != AccountType.OTHER && it.type != AccountType.CREDIT_CARD }) return null
        return vague.copy(type = AccountType.CREDIT_CARD).also { accounts.update(it) }
    }

    /** Switches tracking of one account on or off. */
    suspend fun setTracked(id: Long, tracked: Boolean) = accounts.setTracked(id, tracked)

    /** Renames one account; a blank or null [name] goes back to the bank name. */
    suspend fun rename(id: Long, name: String?) = accounts.rename(id, name?.trim()?.takeIf { it.isNotEmpty() })
}
