package com.expenses.app.ui.screens.payment

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.PaidWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// Text the payment screens build from their state. Kept apart from the ViewModels, which hold no resources.

/** Day and short month, "14 Nov". */
private val DayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/** Day, short month and year, "14 Nov 2025", for dates outside the current year. */
private val DayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/** The time of day in the phone's own style, "9:12 am" or "09:12". */
private val ShortTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** The month's full name, "November", in the phone's language. */
internal fun monthName(month: YearMonth): String = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())

/** A date as "14 Nov", with the year added when it is not this year's. */
internal fun shortDate(date: LocalDate, today: LocalDate): String =
    date.format(if (date.year == today.year) DayMonth else DayMonthYear)

/** The day as "Today", "Yesterday" or "14 Nov". */
@Composable
internal fun dayText(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.payment_day_today)
    today.minusDays(1) -> stringResource(R.string.payment_day_yesterday)
    else -> shortDate(date, today)
}

/** When a payment was made: "Today 9:12", "Yesterday 18:40" or "14 Nov 9:12". */
@Composable
internal fun paymentTimeText(time: PaymentTime): String {
    val clockTime = time.at.toLocalTime().format(ShortTime)
    val date = time.at.toLocalDate()
    return when (date) {
        time.today -> stringResource(R.string.payment_when_today, clockTime)
        time.today.minusDays(1) -> stringResource(R.string.payment_when_yesterday, clockTime)
        else -> stringResource(R.string.payment_when_date_time, shortDate(date, time.today), clockTime)
    }
}

/** The account as "HDFC card ••4521", "HDFC ••1234" or "UPI arjun@okhdfc", or null when nothing names it. */
@Composable
internal fun accountText(account: PaymentAccount): String? {
    val isCard = account.type == AccountType.CREDIT_CARD || account.type == AccountType.DEBIT_CARD
    return when {
        account.type == AccountType.UPI_ID && account.upiId != null ->
            stringResource(R.string.payment_account_upi, account.upiId)
        account.last4 == null -> account.name
        account.name == null && isCard -> stringResource(R.string.payment_account_card_no_bank, account.last4)
        account.name == null -> stringResource(R.string.payment_account_no_bank, account.last4)
        isCard -> stringResource(R.string.payment_account_card, account.name, account.last4)
        else -> stringResource(R.string.payment_account_last4, account.name, account.last4)
    }
}

/** Where the money came from: the account when there is one, else how it was paid ("Cash"). */
@Composable
internal fun sourceText(account: PaymentAccount?, paidWith: PaidWith): String =
    account?.let { accountText(it) } ?: stringResource(paidWith.label)

/** The line under the amount: "Today 9:12 · HDFC card ••4521". */
@Composable
internal fun whenAndSourceText(time: PaymentTime, account: PaymentAccount?, paidWith: PaidWith): String =
    stringResource(R.string.payment_when_with_source, paymentTimeText(time), sourceText(account, paidWith))

/** The merchant, or "Unknown merchant" when the SMS did not name one. */
@Composable
internal fun merchantText(merchant: String?): String =
    merchant?.takeIf(String::isNotBlank) ?: stringResource(R.string.payment_unknown_merchant)

/** The name of each way of paying. */
internal val PaidWith.label: Int
    @StringRes get() = when (this) {
        PaidWith.CASH -> R.string.payment_paid_with_cash
        PaidWith.UPI -> R.string.payment_paid_with_upi
        PaidWith.CARD -> R.string.payment_paid_with_card
        PaidWith.BANK_TRANSFER -> R.string.payment_paid_with_bank_transfer
        PaidWith.WALLET -> R.string.payment_paid_with_wallet
        PaidWith.ATM -> R.string.payment_paid_with_atm
        PaidWith.OTHER -> R.string.payment_paid_with_other
    }
