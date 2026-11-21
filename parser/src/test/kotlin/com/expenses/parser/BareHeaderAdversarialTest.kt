package com.expenses.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime

/**
 * From the review of f8cf0b9. Every corpus and seed message is parsed twice: with its sender as given
 * ("VM-HDFCBK-S") and as some phones give it, without the hyphens ("VMHDFCBK"). Lists every message whose result
 * differs. Also lists 8-letter senders whose bank changed only because of the new rule.
 */
class BareHeaderAdversarialTest {
    private val parser = SmsParser()

    private data class Msg(val id: String, val sender: String, val body: String, val at: Long)

    private fun corpus(): List<Msg> {
        val dir = File(javaClass.classLoader.getResource("samples")!!.toURI())
        val fromSamples = dir.listFiles { f -> f.name.endsWith(".json") }!!.flatMap { file ->
            (Json.parseToJsonElement(file.readText()) as JsonArray).map {
                val o = it.jsonObject
                Msg(
                    o["id"]!!.jsonPrimitive.content,
                    o["sender"]!!.jsonPrimitive.content,
                    o["body"]!!.jsonPrimitive.content,
                    OffsetDateTime.parse(o["receivedAt"]!!.jsonPrimitive.content).toInstant().toEpochMilli(),
                )
            }
        }
        // design/tools/seed_sms.json, bodies with their {d:...} placeholders left in (only the sender matters here).
        val seed = File("../design/tools/seed_sms.json")
        val fromSeed = if (!seed.exists()) emptyList() else (Json.parseToJsonElement(seed.readText()) as JsonArray).map {
            val o = it.jsonObject
            Msg("seed-" + o["id"]!!.jsonPrimitive.content, o["sender"]!!.jsonPrimitive.content, o["body"]!!.jsonPrimitive.content, 1_763_740_000_000L)
        }
        return fromSamples + fromSeed
    }

    private fun bare(sender: String): String = sender.replace(Regex("^([A-Za-z]{2})-"), "$1").replace(Regex("-[STPGstpg]$"), "")

    @Test fun everyMessageReadsTheSameWithoutTheHyphen() {
        val messages = corpus()
        val differ = ArrayList<String>()
        var hyphenated = 0
        for (m in messages) {
            val b = bare(m.sender)
            if (b == m.sender) continue
            hyphenated++
            val a = parser.parse(SmsMessage(m.sender, m.body, m.at))
            val c = parser.parse(SmsMessage(b, m.body, m.at))
            if (a != c) differ += "${m.id}: ${m.sender} -> $b: ${summary(a)} vs ${summary(c)}"
        }
        println("hyphenated senders checked: $hyphenated of ${messages.size}")
        assertEquals("messages that read differently without the hyphen:\n" + differ.joinToString("\n"), 0, differ.size)
    }

    /**
     * Side effect of f8cf0b9, now in the changelog: with a bare header both SMS were "not a bank" before, so
     * Duplicates kept only the first of two same-amount debits 3 minutes apart (a real payment lost). Now both stay.
     */
    @Test fun twoSameAmountDebitsFromABareHeaderAreBothKept() {
        fun read(body: String, atMinute: Long): ReadPayment {
            val sms = SmsMessage("VMHDFCBK", body, 1_763_300_000_000L + atMinute * 60_000L)
            val result = parser.parse(sms) as ParseResult.Payment
            return ReadPayment(sms, result.payment)
        }
        val first = read("Rs.20.00 debited from A/c XX4521 on 16-11-26 to VPA chai@ybl (UPI Ref No 632000000001). -HDFC Bank", 0)
        val second = read("Rs.20.00 debited from A/c XX4521 on 16-11-26 to VPA chai@ybl (UPI Ref No 632000000002). -HDFC Bank", 3)
        assertEquals(listOf(first, second), Duplicates.keepBankCopies(listOf(first, second)))
    }

    private fun summary(r: ParseResult): String = when (r) {
        is ParseResult.Payment -> "Payment(${r.payment.direction}, ${r.payment.amountMinor}, bank=${r.payment.bank})"
        is ParseResult.CardBill -> "CardBill(${r.bill.bank})"
        is ParseResult.Skipped -> "Skipped(${r.reason})"
        is ParseResult.Unreadable -> "Unreadable"
    }
}
