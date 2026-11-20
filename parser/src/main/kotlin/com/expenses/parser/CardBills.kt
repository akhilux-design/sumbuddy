package com.expenses.parser

import java.time.ZoneId

/**
 * Credit card statement notices and payment-due reminders that print the amount due. They are never spending:
 * the app reads them only for the card bill view and due-date reminders.
 */
internal object CardBills {
    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private const val AMOUNT = "(?:Rs|INR)\\.?\\s?:?\\s?${Amounts.NUMBER}"
    private val CARD_OR_STATEMENT = rx("\\bcard\\b|\\bstatement\\b|\\bstmt\\b|\\bOneCard\\b")
    private val DUE = rx("\\bdue\\b|\\bpayable\\b")
    private val TOTAL = rx("\\btot(?:al|\\.)?(?:\\s(?:amt|amount))?(?:\\s(?:due|outstanding))?(?:\\sof)?\\s?[:\\-]?\\s?$AMOUNT")
    private val MINIMUM = rx("\\bmin(?:imum|\\.)?(?:\\s(?:amt|amount))?(?:\\sdue)?(?:\\sof)?\\s?[:\\-]?\\s?$AMOUNT")
    private val DUE_DATE = rx("\\b(?:due\\s(?:date|by|on)|payable\\sby|pay\\sby)\\b")
    private const val DATE_REACH = 20
    private const val LOOSE_DATE_REACH = 60

    /**
     * A payment-due reminder without the word "total": "Payment of Rs 18,240 for your SBI Credit Card ending 6789 is
     * due on 04-Dec-26", "Rs.9,875 is due on your card XX2468", "Amount due: Rs 6,312 on OneCard".
     */
    private val REMINDER_AMOUNT = rx(
        "\\b(?:payment|amount|bill|dues?|outstanding)\\s+(?:amount\\s+)?(?:of\\s+)?$AMOUNT(?=(?:[^.\\n]|\\.(?=\\S)){0,80}\\b(?:due|payable)\\b)" +
            "|$AMOUNT\\s+(?:is\\s+|will\\s+be\\s+)?(?:due|payable)\\b" +
            "|\\b(?:amount\\s+)?due\\s?(?:amount\\s?)?(?:of\\s+)?[:\\-]?\\s?$AMOUNT",
    )
    private val CREDIT_CARD = rx("\\bcredit\\s?card\\b|\\bcc\\b|\\bOneCard\\b|\\bcard\\s+(?:no\\.?\\s+)?(?:ending|xx|x\\d|\\*)")
    /** Loans and EMIs are not card bills; a confirmation of a payment already made is not a reminder. */
    private val NOT_A_REMINDER = rx("\\bemi\\b|\\bloan\\b|\\breceived\\b|\\bcredited\\b|\\bthank(?:s| you) for (?:your |the )?payment\\b")
    private val LOAN_OR_EMI = rx("\\bemis?\\b|\\bloans?\\b")

    /**
     * A card bill in a message that also reads as an offer ("... is due on 04-Dec-26. Pay on time to earn bonus reward
     * points."), which is otherwise skipped as a promotion: only one with a due date, and never a loan or EMI offer.
     */
    fun readDespiteOffer(text: String, bank: String?, zone: ZoneId): ParsedCardBill? {
        if (LOAN_OR_EMI.containsMatchIn(text)) return null
        return read(text, bank, zone)?.takeIf { it.dueDateMillis != null }
    }

    fun read(text: String, bank: String?, zone: ZoneId): ParsedCardBill? {
        if (!CARD_OR_STATEMENT.containsMatchIn(text) || !DUE.containsMatchIn(text)) return null
        val total = TOTAL.find(text)?.let { Amounts.toMinor(it.groupValues[1], "INR") }?.takeIf { it > 0 }
            ?: reminderAmount(text)
            ?: return null
        val minimum = MINIMUM.find(text)?.let { Amounts.toMinor(it.groupValues[1], "INR") }
        val dates = Dates.all(text)
        val dueDate = DUE_DATE.findAll(text)
            .mapNotNull { keyword ->
                Dates.firstAfter(dates, keyword.range.last + 1)?.takeIf { it.start - keyword.range.last <= DATE_REACH }
            }
            .firstOrNull()
            // A reminder may put the card between the word and the date: "is due on your card XX4521 on 05-12-2026".
            ?: DUE.findAll(text)
                .mapNotNull { word ->
                    Dates.firstAfter(dates, word.range.last + 1)?.takeIf { it.start - word.range.last <= LOOSE_DATE_REACH }
                }
                .firstOrNull()
        return ParsedCardBill(
            bank = bank,
            cardLast4 = Instruments.last4(text),
            totalDueMinor = total,
            minimumDueMinor = minimum,
            dueDateMillis = dueDate?.let { Dates.midnightMillis(it.dateTime.toLocalDate(), zone) },
        )
    }

    private fun reminderAmount(text: String): Long? {
        if (!CREDIT_CARD.containsMatchIn(text) || isNotAReminder(text)) return null
        val match = REMINDER_AMOUNT.find(text) ?: return null
        val number = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
        return Amounts.toMinor(number, "INR")?.takeIf { it > 0 }
    }

    /**
     * True for a loan, an EMI or a payment already made ([NOT_A_REMINDER]). A count of points credited ("250 reward
     * points credited this cycle") does not count when no money moved in the message: no debit or credit word is tied to
     * a rupee amount. "Rs 9,875 due on your SBI Card has been paid. 300 reward points credited." is still a payment.
     */
    private fun isNotAReminder(text: String): Boolean =
        NOT_A_REMINDER.containsMatchIn(text) &&
            (Classifier.completedAction(text) != null || NOT_A_REMINDER.containsMatchIn(RealMoney.withoutPointsPhrases(text)))
}
