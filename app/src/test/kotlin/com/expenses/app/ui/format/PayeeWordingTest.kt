package com.expenses.app.ui.format

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.parser.Direction
import com.expenses.parser.ParseResult
import com.expenses.parser.SmsMessage
import com.expenses.parser.SmsParser
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** "₹450 at Swiggy", but "₹350 to Ramesh" for a person paid by UPI and "₹350 for Auto to station" when typed in. */
class PayeeWordingTest {
    private fun payment(
        counterparty: String?,
        vpa: String? = null,
        paidWith: PaidWith = PaidWith.UPI,
        source: ExpenseSource = ExpenseSource.SMS,
    ) = Expense(
        amountMinor = 35_000,
        direction = Direction.DEBIT,
        counterparty = counterparty,
        counterpartyVpa = vpa,
        category = Category.TRAVEL,
        occurredAt = Instant.EPOCH,
        source = source,
        status = ExpenseStatus.DETECTED,
        paidWith = paidWith,
    )

    @Test fun shopsReadAt() {
        assertEquals(PayeeWording.AT, payment("Swiggy", "swiggy@icici").payeeWording())
        assertEquals(PayeeWording.AT, payment("Amazon", paidWith = PaidWith.CARD).payeeWording())
        // A merchant's QR code (PhonePe, Paytm) with the shop's name.
        assertEquals(PayeeWording.AT, payment("Sri Balaji", "q904512873@ybl").payeeWording())
        assertEquals(PayeeWording.AT, payment("Lakshmi Tiffins", "paytmqr5kd9x2@ptys").payeeWording())
        // A shop's name with accents, on a payment whose kind the SMS does not say.
        assertEquals(PayeeWording.AT, payment("CAFÉ DE FLORE PARIS", paidWith = PaidWith.OTHER).payeeWording())
    }

    @Test fun bankTransfersReadTo() {
        assertEquals(PayeeWording.TO, payment("Vikram Sethi", paidWith = PaidWith.BANK_TRANSFER).payeeWording())
        assertEquals(PayeeWording.TO, payment("Bajaj Finance", paidWith = PaidWith.BANK_TRANSFER).payeeWording())
    }

    @Test fun peoplePaidByUpiReadTo() {
        assertEquals(PayeeWording.TO, payment(null, "9876543210@ybl").payeeWording())
        assertEquals(PayeeWording.TO, payment("RAMESH KUMAR", "9876543210@ybl").payeeWording())
        assertEquals(PayeeWording.TO, payment("ramesh.k@okaxis", "ramesh.k@okaxis").payeeWording())
        assertEquals(PayeeWording.TO, payment("Ramesh").payeeWording())
    }

    @Test fun typedInReadsFor() {
        assertEquals(PayeeWording.FOR, payment("Auto to station", source = ExpenseSource.MANUAL, paidWith = PaidWith.CASH).payeeWording())
        assertEquals(PayeeWording.FOR, payment("Dinner", source = ExpenseSource.GROUP, paidWith = PaidWith.OTHER).payeeWording())
    }

    /**
     * The parser's own sample SMS (parser/src/test/resources/samples), read by the real parser and stored as the
     * ingest pipeline stores them, by sample id.
     */
    @Test fun theParsersSamplesReadRight() {
        val expected = mapOf(
            // People paid by UPI: by name, or by a UPI ID when the SMS gives no name.
            "bd-axis-upi-person-13" to PayeeWording.TO,
            "bd-indian-bank-upi-person-31" to PayeeWording.TO,
            "cu-icici-upi-debit-person-01" to PayeeWording.TO,
            "cu-yes-upi-debit-name-vpa-01" to PayeeWording.TO,
            "bd-hdfc-upi-vpa-02" to PayeeWording.TO,
            "core-upi-debit-vpa" to PayeeWording.TO,
            "rv-accuracy-sent-after-amount" to PayeeWording.TO,
            // A shop paid by UPI on its UPI ID alone reads like any address.
            "bd-kotak-upi-vpa-16" to PayeeWording.TO,
            "fe-kotak-ax-operator-prefix-numeric-vpa" to PayeeWording.TO,
            // Shops paid by UPI, by name.
            "core-upi-debit-merchant" to PayeeWording.AT,
            "bd-icici-upi-merchant-05" to PayeeWording.AT,
            "bd-sbi-upi-merchant-09" to PayeeWording.AT,
            "bd-canara-upi-merchant-22" to PayeeWording.AT,
            "bd-boi-upi-merchant-32" to PayeeWording.AT,
            "core-sbi-upi-bare-amount" to PayeeWording.AT,
            "cu-canara-upi-debit-merchant-01" to PayeeWording.AT,
            "cu-au-upi-debit-merchant-vpa-01" to PayeeWording.AT,
            "cu-hdfc-upilite-payment-01" to PayeeWording.AT,
            "fe-sbi-upi-amount-without-currency" to PayeeWording.AT,
            "fe-hdfc-upi-multiline-star-mask" to PayeeWording.AT,
            "fe-icici-suffix-t-three-digit-mask" to PayeeWording.AT,
            "fe-indian-bank-star-mask-short-code-trap" to PayeeWording.AT,
            "rv-accuracy-upi-dr-narration" to PayeeWording.AT,
            // Cards and wallets pay shops.
            "bd-hdfc-debit-card-shop-04" to PayeeWording.AT,
            "core-debit-card-spend-time" to PayeeWording.AT,
            "core-credit-card-spend-limit-not-balance" to PayeeWording.AT,
            "cc-amex-spend-01" to PayeeWording.AT,
            "cc-icici-intl-usd-01" to PayeeWording.AT,
            "cu-paytm-wallet-paid-01" to PayeeWording.AT,
            "cu-amazonpay-wallet-paid-01" to PayeeWording.AT,
            "fe-paytm-wallet-mixed-case-header" to PayeeWording.AT,
            // A payment whose kind the SMS does not say: by the name.
            "fe-uco-all-caps-body" to PayeeWording.AT,
            "rv-accuracy-initials-in-name" to PayeeWording.AT,
            "rv-robust-accented-foreign-merchant" to PayeeWording.AT,
            "bd-axis-upi-autopay-executed-15" to PayeeWording.AT,
            "rv-accuracy-balance-far-from-keyword" to PayeeWording.TO,
            // Bank transfers go to an account or a biller.
            "bd-uco-neft-28" to PayeeWording.TO,
            "bd-indusind-rtgs-27" to PayeeWording.TO,
            "core-unknown-header-bank-from-text" to PayeeWording.TO,
            "rv-accuracy-honorific-with-dot" to PayeeWording.TO,
            "bd-sib-internet-banking-37" to PayeeWording.TO,
            "bd-idfc-nach-mandate-25" to PayeeWording.TO,
        )
        val samples = loadSamples()
        val parser = SmsParser(ZoneId.of("Asia/Kolkata"))
        val wrong = expected.mapNotNull { (id, wording) ->
            val sms = samples[id] ?: return@mapNotNull "$id: no such sample"
            val payment = (parser.parse(sms) as? ParseResult.Payment)?.payment ?: return@mapNotNull "$id: not a payment"
            // As SmsIngestPipeline stores a payment read from SMS.
            val expense = Expense(
                amountMinor = payment.amountMinor,
                direction = payment.direction,
                counterparty = payment.counterparty,
                counterpartyVpa = payment.vpa,
                category = Category.OTHER,
                occurredAt = Instant.ofEpochMilli(sms.receivedAtMillis),
                source = ExpenseSource.SMS,
                status = ExpenseStatus.DETECTED,
                paidWith = PaidWith.from(payment.channel),
            )
            val got = expense.payeeWording()
            if (got == wording) null else "$id: $got, expected $wording (${expense.paidWith}, ${expense.counterparty}, ${expense.counterpartyVpa})"
        }
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))
    }

    /** Every sample SMS by id. The tests run in the app module's folder; the samples are the parser module's. */
    private fun loadSamples(): Map<String, SmsMessage> {
        val folder = listOf(File("../parser/src/test/resources/samples"), File("parser/src/test/resources/samples"))
            .firstOrNull { it.isDirectory } ?: error("parser samples not found")
        return folder.listFiles { f -> f.name.endsWith(".json") }.orEmpty().flatMap { file ->
            Json.parseToJsonElement(file.readText()).jsonArray.map { element ->
                val case = element.jsonObject
                case.getValue("id").jsonPrimitive.content to SmsMessage(
                    sender = case.getValue("sender").jsonPrimitive.content,
                    body = case.getValue("body").jsonPrimitive.content,
                    receivedAtMillis = OffsetDateTime.parse(case.getValue("receivedAt").jsonPrimitive.content).toInstant().toEpochMilli(),
                )
            }
        }.toMap()
    }
}
