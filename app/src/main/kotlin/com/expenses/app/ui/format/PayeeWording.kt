package com.expenses.app.ui.format

import androidx.annotation.StringRes
import com.expenses.app.R
import com.expenses.app.data.category.Category
import com.expenses.app.data.category.CategoryGuesser
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.PaidWith
import java.text.Normalizer

/**
 * How a payment's amount and payee read together. The design's merchants read "₹450 at Swiggy"; that reads wrong for
 * a person paid by UPI ("₹350 at Ramesh") and for something typed in ("₹350 at Auto to station"), so those read
 * "₹350 to Ramesh" and "₹350 for Auto to station". Used by Home's payment card, the "Payment detected" notification
 * and Expense added.
 *
 * Owned by the small-fixes area (docs/groups-build-plan.md), which checked the rule against the parser's sample SMS
 * (see [payeeWording]).
 */
enum class PayeeWording(@StringRes val amountAndPayee: Int) {
    /** A shop or service: "₹450 at Swiggy". */
    AT(R.string.format_amount_at),

    /** A person, an account or a UPI ID that money was sent to: "₹350 to Ramesh", "₹5,000 to Vikram Sethi". */
    TO(R.string.format_amount_to),

    /** What it was for, as typed: "₹350 for Auto to station". */
    FOR(R.string.format_amount_for),
}

/**
 * How this payment's amount and payee read together.
 *
 * - Typed in, or someone else's group expense: "for", since the payee is what it was for.
 * - The payee shown is a UPI ID (the SMS gives no name), or a name holding one or a phone or account number: "to".
 * - A card payment: "at" (cards pay shops).
 * - A bank transfer (NEFT, IMPS, RTGS, net banking, cheque, mandate): "to"; it goes to an account or a biller.
 * - UPI, a wallet or a payment of unknown kind: "at" when the name is a shop's (a merchant the category keywords know,
 *   a word such as "Store", "Traders" or "Pharmacy", or a merchant QR code's UPI ID), else "to". Banks print a UPI
 *   payee's registered name, and most names that are not a shop's are people's. "To" also reads right for a shop, so
 *   the doubtful case reads "to".
 */
fun Expense.payeeWording(): PayeeWording = when {
    source == ExpenseSource.MANUAL || source == ExpenseSource.GROUP -> PayeeWording.FOR
    payeeIsAddress() -> PayeeWording.TO
    paidWith == PaidWith.CARD || paidWith == PaidWith.ATM -> PayeeWording.AT
    paidWith == PaidWith.BANK_TRANSFER -> PayeeWording.TO
    looksLikeShop() -> PayeeWording.AT
    else -> PayeeWording.TO
}

/** A run of 6 or more digits: a phone or account number, as in a person's UPI ID. */
private val LONG_DIGITS = Regex("[0-9]{6,}")

/** Words of a shop's or company's name, matched as whole words of [CategoryGuesser.merchantKey]. */
private val SHOP_WORDS = Regex(
    "\\b(?:" + listOf(
        "store", "stores", "shop", "shoppe", "stall", "mart", "kirana", "bazaar", "bazar", "super", "supermarket",
        "market", "general", "provisions", "traders", "trading", "enterprises", "enterprise", "agency", "agencies",
        "limited", "ltd", "pvt", "private", "llp", "company", "corporation", "corp", "industries", "services",
        "solutions", "retail", "india", "sports", "motors", "electronics", "mobiles", "fashion", "garments",
        "collection", "emporium", "depot", "centre", "center", "pharmacy", "chemist", "chemists", "medical",
        "medicals", "medicos", "clinic", "hospital", "diagnostics", "hotel", "hotels", "restaurant", "cafe", "bakery",
        "bakers", "sweets", "foods", "kitchen", "dhaba", "canteen", "tea", "chai", "coffee", "juice", "dairy",
        "fuels", "petroleum", "station", "salon", "studio", "travels", "tours",
    ).joinToString("|") + ")\\b",
)

/** The start of the UPI ID of a shop's QR code or payment gateway, before "@". */
private val SHOP_UPI_ID = Regex("^(?:paytmqr|paytm-|bharatpe|gpay-|q[0-9]{6,}|razorpay|rzp|cashfree|payu|billdesk|mab\\.)")

/** True when the payee shown is a UPI ID or holds a phone or account number: money sent to an address. */
private fun Expense.payeeIsAddress(): Boolean {
    val name = counterparty ?: return counterpartyVpa != null
    return name.contains('@') || LONG_DIGITS.containsMatchIn(name)
}

/** Accents and other combining marks, dropped so "CAFÉ DE FLORE" reads as "cafe de flore". */
private val MARKS = Regex("\\p{M}+")

/** True when the payee's name, or the UPI ID it was paid on, is a shop's. */
private fun Expense.looksLikeShop(): Boolean {
    val name = counterparty?.let { Normalizer.normalize(it, Normalizer.Form.NFD).replace(MARKS, "") } ?: return false
    if (CategoryGuesser.guess(name, counterpartyVpa) != Category.OTHER) return true
    if (SHOP_WORDS.containsMatchIn(CategoryGuesser.merchantKey(name))) return true
    val vpa = counterpartyVpa?.lowercase() ?: return false
    return SHOP_UPI_ID.containsMatchIn(vpa.substringBefore('@')) || vpa.substringAfter('@').startsWith("okbiz")
}
