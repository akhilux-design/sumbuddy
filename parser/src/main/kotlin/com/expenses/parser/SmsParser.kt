package com.expenses.parser

import java.time.ZoneId

/**
 * Turns a bank or UPI SMS into a [ParseResult]. Pure Kotlin, no Android classes, no I/O, no logging of message text.
 * Holds no mutable state, so one instance can be shared between threads.
 *
 * @param zone time zone used to read dates printed in messages.
 */
class SmsParser(private val zone: ZoneId = ZoneId.of("Asia/Kolkata")) {

    fun parse(sms: SmsMessage): ParseResult {
        if (!Classifier.isBusinessSender(sms.sender)) return ParseResult.Skipped(SkipReason.NOT_FINANCIAL)
        val text = Classifier.normalise(sms.body)
        if (text.isEmpty()) return ParseResult.Skipped(SkipReason.NOT_FINANCIAL)

        // Safety footers ("never share your OTP ...") must not decide what kind of message this is.
        val skipText = Classifier.withoutSafetyFooters(text)
        val completed = Classifier.completedAction(skipText)
        val senderBank = Banks.fromSender(sms.sender)
        Classifier.earlySkip(skipText, completed)?.let { reason ->
            // A card's payment-due reminder may close with an offer ("Pay on time to earn bonus reward points").
            if (reason == SkipReason.PROMOTION) {
                CardBills.readDespiteOffer(text, senderBank ?: Banks.fromText(text), zone)?.let { return ParseResult.CardBill(it) }
            }
            return ParseResult.Skipped(reason)
        }
        if (Classifier.isSelfTransfer(skipText)) return ParseResult.Skipped(SkipReason.SELF_TRANSFER)

        // Biller and merchant receipts duplicate the bank's SMS for the same payment: only the bank's one counts.
        if (senderBank == null && Classifier.isBillerReceipt(skipText)) return ParseResult.Skipped(SkipReason.BILLER_RECEIPT)
        val bank = senderBank ?: Banks.fromText(text)
        CardBills.read(text, bank, zone)?.let { return ParseResult.CardBill(it) }

        if (Classifier.isUpcoming(skipText, completed)) return ParseResult.Skipped(SkipReason.UPCOMING)

        val direction = Classifier.direction(text)
        val read = if (direction != null) {
            readPayment(text, direction, bank)
        } else {
            ParseResult.Skipped(if (Classifier.mentionsBalance(text)) SkipReason.BALANCE_INFO else SkipReason.NOT_FINANCIAL)
        }
        // Points are not money (RealMoney): money in from a sender that is not a bank whose amount is labelled as
        // points, or a message that was not a payment and moves only points. senderBank is the cheap part of isFromBank.
        val points = if (read is ParseResult.Payment) {
            read.payment.direction == Direction.CREDIT && senderBank == null && RealMoney.amountIsPoints(text) && !isFromBank(sms)
        } else {
            RealMoney.onlyPointsMove(skipText)
        }
        return if (points) ParseResult.Skipped(SkipReason.REWARD_POINTS) else read
    }

    /**
     * True when [sms] comes from a bank, card issuer or wallet: its header is in [Banks], its header code reads as a
     * bank's ("VK-COOPBK", "VM-DBSBNK", "VM-EQTSFB"), or the message signs off with a bank's name ("-Equitas SFB").
     * Money in labelled as points is skipped ([SkipReason.REWARD_POINTS]) only for messages from other senders.
     */
    fun isFromBank(sms: SmsMessage): Boolean =
        Banks.fromSender(sms.sender) != null ||
            RealMoney.isUnlistedBank(sms.sender, Classifier.withoutSafetyFooters(Classifier.normalise(sms.body)))

    /**
     * The payment fields [sms] gives when read as a payment with every skip rule left out, or null when it has no
     * debit or credit word or no amount. Only for telling whether a row stored from this SMS by an older version still
     * holds what was read from it (amount, direction, payee); never use it to decide whether a message is a payment.
     */
    fun readIgnoringSkips(sms: SmsMessage): ParsedPayment? {
        val text = Classifier.normalise(sms.body)
        if (text.isEmpty()) return null
        val direction = Classifier.direction(text) ?: return null
        val bank = Banks.fromSender(sms.sender) ?: Banks.fromText(text)
        return (readPayment(text, direction, bank) as? ParseResult.Payment)?.payment
    }

    private fun readPayment(text: String, direction: Direction, bank: String?): ParseResult {
        val amounts = Amounts.all(text)
        // A message with a debit or credit word is never BALANCE_INFO: without a payment amount it needs review.
        val amount = Amounts.payment(text, amounts) ?: return ParseResult.Unreadable("amount")

        val instrument = Instruments.read(text, bank, direction)
        val channel = Channels.read(text, instrument.type)
        val date = Dates.first(text)
        val confidence = if (instrument.last4 != null || bank != null) Confidence.HIGH else Confidence.LOW

        return ParseResult.Payment(
            ParsedPayment(
                direction = direction,
                amountMinor = amount.minor,
                currency = amount.currency,
                counterparty = if (channel == Channel.ATM) null else Parties.counterparty(text, direction),
                vpa = Parties.vpa(text, direction),
                bank = bank,
                instrumentType = instrument.type,
                instrumentLast4 = instrument.last4,
                channel = channel,
                referenceId = Parties.reference(text),
                balanceMinor = Amounts.balance(amounts),
                occurredAtMillis = date?.let { Dates.toMillis(it.dateTime, zone) },
                confidence = confidence,
            ),
        )
    }
}
