package com.expenses.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Throughput, pathological inputs and thread-safety. Message text is never printed: failures name inputs by label only. */
class RobustnessTest {
    private val parser = SmsParser(ZoneId.of("Asia/Kolkata"))
    private val t0 = 1_794_800_000_000L

    private val templates = listOf(
        "VM-HDFCBK" to "Rs.%A debited from a/c **%L on 16-11-26 to VPA shop%N@icici (UPI Ref No 6320%R). Not you? Call 18002586161",
        "AD-ICICIB" to "INR %A spent using ICICI Bank Card XX%L on 16-Nov-26 on AMAZON. Avl Limit: INR 1,48,750.00.",
        "BZ-SBIINB" to "Dear UPI user A/C X%L debited by %A on date 16Nov26 trf to RAHUL KUMAR Refno 6320%R. If not u? call 1800111109. -SBI",
        "JD-AXISBK-S" to "INR %A credited to A/c no. XX%L on 16-11-26 by a/c linked to VPA friend%N@okaxis (UPI Ref no 6320%R).",
        "VM-KOTAKB" to "Sent Rs.%A from Kotak Bank AC X%L to merchant%N@ybl on 16-11-26.UPI Ref 6320%R. Not you, https://kotak.com/KBANKT/Fraud",
        "AX-SBICRD" to "Your SBI Card statement: Total Amt Due Rs.%A, Min Amt Due Rs.500.00, payable by 05-Dec-26. Card ending %L.",
        "VM-HDFCBK" to "Your OTP for txn of Rs.%A at AMAZON is 4%L. Do not share it with anyone.",
        "VK-PAYTM" to "Get flat 10% cashback up to Rs %A on your next recharge. T&C apply.",
        "VM-PHONPE" to "₹%A paid to RAMESH KIRANA from your HDFC Bank a/c XX%L. Txn ID T26111610%R",
        "AD-PNBSMS" to "आपके खाते XX%L में ₹%A जमा किए गए दिनांक 16-11-26. -PNB",
        "VM-STORLY" to "Rs.%A loyalty points have been credited to your Store Loyalty account %L. Valid until 30th November. T&C apply.",
        "VM-HDFCBK" to "Rs %A credited to A/c XX%L by NEFT. You have earned 25 reward points. -HDFC Bank",
        "VM-SHPCLB" to "An update has been made to your ShopClub account XXXXXX%L. Rs.%A has been credited. Pay using UPI at any store.",
        "VM-EQUTAS" to "Rs %A spent on your card ending %L at WOOHOO GIFT CARD on 16-11-26. -Equitas SFB",
        "+919000000005" to "Rs %A debited from your account, call me back",
        "VM-HDFCBK" to "",
    )

    private fun corpus(size: Int): List<SmsMessage> = List(size) { i ->
        val (sender, template) = templates[i % templates.size]
        val amount = "%,d.%02d".format(1 + (i * 37) % 99_999, i % 100)
        val body = template
            .replace("%A", amount)
            .replace("%L", (1000 + i % 9000).toString())
            .replace("%N", (i % 997).toString())
            .replace("%R", (10_000_000 + i).toString())
        SmsMessage(sender, body, t0 + i)
    }

    // The time limits in this file guard against pathological slowness (a regex that backtracks, work that grows with
    // the square of the text), not against a slow machine. Normal runs take a fraction of them on a laptop, but the
    // earlier 2,000 ms and 200 ms limits failed when the build ran other work at the same time (app compilation in
    // parallel, a loaded CI runner). 8,000 ms and 1,000 ms still catch a real slowdown, which is many times larger.
    private val corpusLimitMs = 8_000
    private val oneMessageLimitMs = 1_000

    @Test
    fun twentyThousandMessagesWellUnderTheLimit() {
        val messages = corpus(20_000)
        repeat(3) { messages.forEach(parser::parse) }
        val start = System.nanoTime()
        messages.forEach(parser::parse)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("20,000 parses took $ms ms (limit $corpusLimitMs ms)", ms < corpusLimitMs)
    }

    /**
     * Inputs aimed at the points rule ([RealMoney]). They are also sent from a sender that is not a bank, for which the
     * words after a money in amount are read for a points label.
     */
    private val moneyRulePathological: List<Pair<String, String>> = listOf(
        "points names x5000" to "Rs 450 " + "loyalty ".repeat(5_000) + "points credited",
        "point counts x5000" to "credited " + "1,250 ".repeat(5_000) + "points",
        "points worth x5000" to "points worth ".repeat(5_000) + "Rs 125 credited",
        "store wallet words x5000" to "Rs 50 credited to your " + "Brand ".repeat(5_000) + "wallet",
        "sentence ends x5000" to "Rs 50 credited. " + "No. Bal. ".repeat(5_000) + "to your wallet",
        "one long word x20000" to "Rs 50 credited to " + "a".repeat(20_000) + "wallet",
        "vpa run x20000" to "Rs 50 credited to " + "a.".repeat(10_000) + "@",
        "points count actions x5000" to "Rs 50 " + "500 points credited ".repeat(5_000),
        "points phrases only x5000" to "500 points credited ".repeat(5_000) + "debited",
        "sign-off x5000" to "Rs 50 credited -" + "Bank ".repeat(5_000),
        "to your x5000" to "Rs 50 credited " + "to your club ".repeat(5_000) + "wallet",
        "to your long word" to "Rs 50 credited to your " + "a".repeat(20_000) + "wallet",
    )

    private val pathological: List<Pair<String, String>> = listOf(
        "Rs x5000" to "Rs ".repeat(5_000),
        "digits x5000" to "9".repeat(5_000),
        "digit groups x5000" to "1234 ".repeat(5_000),
        "debited x5000" to "debited ".repeat(5_000),
        "Rs 1 debited to x5000" to "Rs 1 debited to ".repeat(5_000),
        "to x5000 after debit" to "Rs 450 debited to " + "to ".repeat(5_000),
        "dates x5000" to "Rs 450 debited on " + "16-11-26 ".repeat(5_000),
        "XX masks x5000" to "Rs 450 debited from A/c " + "XX4521 ".repeat(5_000),
        "otp then newlines" to "otp" + "\n".repeat(20_000) + "x",
        "newlines x20000" to "Rs 450 debited" + "\n".repeat(20_000),
        "card bill with many due-by" to "Credit card statement: Total due Rs 100. " + "due by soon ".repeat(5_000),
        "card bill with many due-by and dates" to "Credit card statement: Total due Rs 100. " +
            "due by 01 01 ".repeat(2_500) + "16-11-26 ".repeat(2_500),
        "names x5000" to "Rs 450 debited at " + "SHOP ".repeat(5_000),
        "commas x5000" to "Rs 1" + ",".repeat(5_000) + " debited",
        "vpa-ish x5000" to "Rs 450 debited to " + "a.".repeat(5_000) + "@",
        "nbsp x5000" to "Rs" + " ".repeat(5_000) + "450 debited",
        "emoji x5000" to "Rs 450 debited to " + "🍕".repeat(5_000),
        "devanagari digits x5000" to "रु " + "५".repeat(5_000) + " डेबिट",
        "mandate window" to "mandate ".repeat(5_000),
        "slash narration" to "UPI/" + "123456/".repeat(5_000),
    ) + moneyRulePathological

    @Test
    fun pathologicalInputsReturnQuickly() {
        repeat(3) { corpus(500).forEach(parser::parse) }
        val slow = mutableListOf<String>()
        val runs = pathological.map { (label, body) -> Triple(label, "VM-HDFCBK", body) } +
            moneyRulePathological.map { (label, body) -> Triple(label, "VM-SHPCLB", body) }
        for ((label, sender, body) in runs) {
            val sms = SmsMessage(sender, body, t0)
            runCatching { parser.parse(sms) } // warm this shape once
            val start = System.nanoTime()
            val outcome = runCatching { parser.parse(sms) }
            val ms = (System.nanoTime() - start) / 1_000_000
            if (outcome.isFailure) slow += "$label from $sender threw ${outcome.exceptionOrNull()!!::class.simpleName}"
            else if (ms >= oneMessageLimitMs) slow += "$label from $sender took $ms ms"
        }
        assertTrue("Pathological inputs: $slow", slow.isEmpty())
    }

    @Test
    fun eightThreadsMatchSingleThread() {
        val messages = corpus(4_000) + pathological.take(6).map { SmsMessage("VM-HDFCBK", it.second, t0) }
        val expected = messages.map(parser::parse)
        val shared = SmsParser(ZoneId.of("Asia/Kolkata"))
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 8).map { offset ->
                pool.submit(Callable {
                    // each thread walks the list from a different start so threads hit different shapes at once
                    List(messages.size) { k -> (k + offset * 500) % messages.size }
                        .map { index -> index to shared.parse(messages[index]) }
                })
            }
            for (future in futures) {
                for ((index, result) in future.get(60, TimeUnit.SECONDS)) {
                    assertEquals("message #$index differs across threads", expected[index], result)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
