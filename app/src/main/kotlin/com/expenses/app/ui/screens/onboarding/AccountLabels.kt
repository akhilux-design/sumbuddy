package com.expenses.app.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.ui.icons.AppIcon

/**
 * An account as Scanning SMS and Confirm accounts show it.
 *
 * @param id the account's row id.
 * @param bank bank or issuer name read from the SMS, or null when unknown.
 * @param displayName the name the person gave it, or null.
 * @param type what kind of account it is.
 * @param last4 the last digits printed in the SMS, or null.
 * @param upiId the person's own UPI ID for a UPI account, or null.
 * @param tracked false when the person switched it off.
 */
@Immutable
data class AccountItem(
    val id: Long,
    val bank: String?,
    val displayName: String?,
    val type: AccountType,
    val last4: String?,
    val upiId: String?,
    val tracked: Boolean,
) {
    /** Making items from stored accounts. */
    companion object {
        /** The item for a stored [account]. */
        fun from(account: Account): AccountItem = AccountItem(
            id = account.id,
            bank = account.bank,
            displayName = account.displayName,
            type = account.type,
            last4 = account.last4,
            upiId = account.upiId,
            tracked = account.tracked,
        )
    }
}

/** The 3D icon of an account kind: a bank for accounts, a card for cards, a phone for UPI, cash for wallets. */
internal val AccountType.icon: AppIcon
    get() = when (this) {
        AccountType.BANK_ACCOUNT, AccountType.OTHER -> AppIcon.Bank
        AccountType.CREDIT_CARD, AccountType.DEBIT_CARD -> AppIcon.Card
        AccountType.WALLET -> AppIcon.Cash
        AccountType.UPI_ID -> AppIcon.Phone
    }

/** The name of an account kind, for example "Credit card". */
@Composable
internal fun accountKind(type: AccountType): String = stringResource(
    when (type) {
        AccountType.BANK_ACCOUNT -> R.string.onboarding_account_bank_account
        AccountType.CREDIT_CARD -> R.string.onboarding_account_credit_card
        AccountType.DEBIT_CARD -> R.string.onboarding_account_debit_card
        AccountType.WALLET -> R.string.onboarding_account_wallet
        AccountType.UPI_ID -> R.string.onboarding_account_upi
        AccountType.OTHER -> R.string.onboarding_account_other
    },
)

/** The account's name: the person's name for it, else the bank's name, else its kind ("UPI"). */
@Composable
internal fun accountName(item: AccountItem): String =
    item.displayName ?: item.bank ?: accountKind(item.type)

/** The name with the last digits, as on Scanning SMS: "HDFC Bank ••4521". */
@Composable
internal fun accountNameWithDigits(item: AccountItem): String {
    val name = accountName(item)
    return item.last4?.let { stringResource(R.string.onboarding_account_with_digits, name, it) } ?: name
}

/** The second line on Confirm accounts: the UPI ID, or the kind with the last digits ("Credit card ••8890"). */
@Composable
internal fun accountDetail(item: AccountItem): String {
    val kind = accountKind(item.type)
    return when {
        item.type == AccountType.UPI_ID && item.upiId != null -> item.upiId
        item.last4 != null -> stringResource(R.string.onboarding_account_with_digits, kind, item.last4)
        else -> kind
    }
}
