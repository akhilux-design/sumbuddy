package com.expenses.app.ui.screens.payment

import androidx.compose.runtime.Immutable
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The account a payment came from, as the payment screens name it ("HDFC card ••4521").
 *
 * @param name the name the person gave the account, else the bank's name, or null when neither is known.
 * @param type what kind of account it is.
 * @param last4 the last digits as printed in the SMS, or null.
 * @param upiId the person's own UPI ID for a UPI ID account, or null.
 */
@Immutable
data class PaymentAccount(
    val name: String?,
    val type: AccountType,
    val last4: String?,
    val upiId: String?,
) {
    /** Making one from a stored account. */
    companion object {
        /** The account as the payment screens show it, or null for a payment without an account. */
        fun from(account: Account?): PaymentAccount? = account?.let {
            PaymentAccount(
                name = it.displayName?.takeIf(String::isNotBlank) ?: it.bank?.let(::shortBankName),
                type = it.type,
                last4 = it.last4,
                upiId = it.upiId,
            )
        }

        /** "HDFC Bank" becomes "HDFC", as the design writes it; other names stay as they are. */
        private fun shortBankName(bank: String): String = bank.removeSuffix(BANK_SUFFIX).trim().ifEmpty { bank }

        private const val BANK_SUFFIX = " Bank"
    }
}

/**
 * When a payment was made, in the phone's time zone, with today's date so the screen can say "Today" or
 * "Yesterday".
 *
 * @param at the date and time of the payment.
 * @param today today's date.
 */
@Immutable
data class PaymentTime(val at: LocalDateTime, val today: LocalDate) {
    /** Making one from an instant. */
    companion object {
        /** [instant] in the time zone of [clock], with today's date from [clock]. */
        fun of(instant: Instant, clock: Clock): PaymentTime =
            PaymentTime(at = LocalDateTime.ofInstant(instant, clock.zone), today = LocalDate.now(clock))
    }
}

/** The most a typed amount may be: ₹10 crore, far above any payment the app should see. */
private const val MAX_AMOUNT_MINOR = 1_000_000_000L

/** Paise in a rupee. */
private val MINOR_PER_RUPEE = BigDecimal(100)

/** Digits with at most two after the point, as typed into an amount field once the rupee sign and commas are gone. */
private val AmountPattern = Regex("""\d{1,10}(\.\d{0,2})?|\.\d{1,2}""")

/**
 * The amount the person typed, in paise, or null when it is empty, zero, too large or not a number. The rupee sign,
 * "Rs", commas and spaces are allowed, so "₹1,200.50" gives 120050.
 */
fun parseAmountMinor(text: String): Long? {
    val cleaned = text.trim()
        .removePrefix("₹")
        .removePrefix("Rs.")
        .removePrefix("Rs")
        .replace(",", "")
        .replace(" ", "")
    if (!AmountPattern.matches(cleaned)) return null
    val minor = BigDecimal(cleaned).multiply(MINOR_PER_RUPEE).toLong()
    return minor.takeIf { it in 1..MAX_AMOUNT_MINOR }
}

/** The amount in paise as plain text for an amount field: 120000 is "1200" and 120050 is "1200.50". */
fun amountFieldText(amountMinor: Long): String {
    val rupees = amountMinor / 100
    val paise = amountMinor % 100
    return if (paise == 0L) rupees.toString() else "$rupees.${paise.toString().padStart(2, '0')}"
}

/** A rupee amount written in an SMS: "Rs 1200.00", "Rs.1,200", "INR 450" or "₹450". */
private val SmsAmountPattern = Regex("""(?:Rs\.?|INR|₹)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)

/**
 * The first rupee amount written in an SMS the parser could not read, to fill in the amount on Needs review, or null
 * when there is none. The person still checks it before saving.
 */
fun amountInMessage(body: String): Long? =
    SmsAmountPattern.find(body)?.groupValues?.get(1)?.let(::parseAmountMinor)

/**
 * Whether a bank message that could not be read reports money in: it says "credited" (or "received") and never
 * "debited", "spent", "paid" or "sent". Anything else is taken as money out.
 */
fun isMoneyInMessage(body: String): Boolean {
    val text = body.lowercase()
    return MoneyInWords.any { it in text } && MoneyOutWords.none { it in text }
}

private val MoneyInWords = listOf("credited", "received")
private val MoneyOutWords = listOf("debited", "spent", "paid", "sent", "withdrawn")
