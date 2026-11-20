package com.expenses.parser

/** One SMS as read from the phone. Nothing here is ever sent off the device. */
data class SmsMessage(
    /** Sender header exactly as the phone shows it, for example "VM-HDFCBK" or "JD-SBIINB-S". */
    val sender: String,
    val body: String,
    val receivedAtMillis: Long,
)

enum class Direction { DEBIT, CREDIT }

enum class Channel { UPI, CARD, NET_BANKING, IMPS, NEFT, RTGS, ATM, WALLET, AUTOPAY, CHEQUE, UNKNOWN }

enum class InstrumentType { BANK_ACCOUNT, CREDIT_CARD, DEBIT_CARD, WALLET, UNKNOWN }

/** HIGH: amount, direction and instrument were read cleanly. LOW: something was guessed, so the app asks the user to check. */
enum class Confidence { HIGH, LOW }

/** Why a message is not treated as a payment. */
enum class SkipReason {
    /** One-time passwords and verification codes. Never stored. */
    OTP,
    /** Offers, loans, marketing. */
    PROMOTION,
    /** A payment that failed, was declined or was reversed in the same message. */
    FAILED,
    /** A collect request or mandate request that has not been paid. */
    REQUEST,
    /**
     * Money moved between the user's own places: a UPI Lite top-up, money added to the user's own wallet, or a
     * UPI Lite balance moved back to the bank. Not spending; the spends made from UPI Lite or the wallet are counted.
     */
    SELF_TRANSFER,
    /**
     * A biller, merchant or insurer confirming it received the user's payment (a bill, recharge, premium or order).
     * The bank's own SMS for the same payment is the one counted.
     */
    BILLER_RECEIPT,
    /**
     * Not money: points, coins, miles or a store voucher. Closed rule ([RealMoney]): only money in, from a sender that
     * is not a bank ([SmsParser.isFromBank]), whose amount is labelled as points in the words right after it: "Rs.200
     * loyalty points have been credited", "Rs 100 credited as NeuCoins". Debits and everything a bank sends are read as
     * payments, whatever they say about points. Also a message that prints no money amount and moves only points ("500
     * SuperCoins credited"), which would otherwise be unreadable.
     */
    REWARD_POINTS,
    /** Balance or statement information with no single payment. */
    BALANCE_INFO,
    /** A payment that will happen later (for example "will be debited on"). */
    UPCOMING,
    /** Sender is a person or an unknown header, or the text is not about money. */
    NOT_FINANCIAL,
}

data class ParsedPayment(
    val direction: Direction,
    /** Amount in paise (1 rupee = 100). Always positive. */
    val amountMinor: Long,
    val currency: String = "INR",
    /** Merchant or person, cleaned for display ("Swiggy", "Rahul Sharma"). Null when the message does not say. */
    val counterparty: String?,
    /** UPI address of the other side when present ("swiggy@icici"). */
    val vpa: String?,
    /** Bank or card issuer for display ("HDFC Bank"). Null if it cannot be worked out. */
    val bank: String?,
    val instrumentType: InstrumentType,
    /** Last digits of the account or card as printed (usually 4). */
    val instrumentLast4: String?,
    val channel: Channel,
    /** UPI reference, transaction id or similar. Used to avoid adding the same payment twice. */
    val referenceId: String?,
    /** Balance after the payment, in paise, when the message states it. */
    val balanceMinor: Long?,
    /** Time of the payment if the message states a date; otherwise null and the app uses the SMS time. */
    val occurredAtMillis: Long?,
    val confidence: Confidence,
)

/** A credit card bill or statement notice. Shown in the card bill view and used for reminders. */
data class ParsedCardBill(
    val bank: String?,
    val cardLast4: String?,
    val totalDueMinor: Long,
    val minimumDueMinor: Long?,
    /** Due date at local midnight, epoch millis. */
    val dueDateMillis: Long?,
)

sealed interface ParseResult {
    data class Payment(val payment: ParsedPayment) : ParseResult
    data class CardBill(val bill: ParsedCardBill) : ParseResult
    data class Skipped(val reason: SkipReason) : ParseResult
    /** Looks like a payment but could not be read. The app lists it under "Needs review". */
    data class Unreadable(val hint: String?) : ParseResult
}
