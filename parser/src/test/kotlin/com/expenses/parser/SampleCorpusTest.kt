package com.expenses.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Runs every case in src/test/resources/samples/\*.json through the parser (format: parser/README.md).
 * A malformed file fails the test. All mismatches are collected and reported together.
 */
class SampleCorpusTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val parser = SmsParser(zone)

    private data class Case(val file: String, val id: String, val sms: SmsMessage, val expect: JsonObject)

    @Test
    fun samplesMatchExpectations() {
        val formatErrors = mutableListOf<String>()
        val cases = loadCases(formatErrors)
        if (formatErrors.isNotEmpty()) {
            fail("Malformed sample files (${formatErrors.size} problems):\n" + formatErrors.joinToString("\n"))
        }
        assertTrue("No sample cases found", cases.isNotEmpty())

        val failures = mutableListOf<String>()
        var failedCases = 0
        for (case in cases) {
            val result = runCatching { parser.parse(case.sms) }
            val problems = result.fold({ compare(case.expect, it) }, { listOf("threw ${it::class.simpleName}: ${it.message}") })
            if (problems.isNotEmpty()) {
                failedCases++
                problems.forEach { failures += "  ${case.id} (${case.file}): $it" }
            }
        }
        if (failures.isNotEmpty()) {
            fail("$failedCases failed of ${cases.size}\n" + failures.joinToString("\n"))
        }
    }

    // ---- loading and format checks ----

    private fun samplesDir(): File {
        val url = javaClass.classLoader.getResource("samples") ?: error("samples folder is not on the test classpath")
        return File(url.toURI())
    }

    private fun loadCases(errors: MutableList<String>): List<Case> {
        val files = samplesDir().listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        val seen = mutableMapOf<String, String>()
        val cases = mutableListOf<Case>()
        for (file in files) {
            val root = runCatching { Json.parseToJsonElement(file.readText()) }.getOrElse {
                errors += "${file.name}: not valid JSON (${it.message?.lineSequence()?.firstOrNull()})"
                null
            } ?: continue
            if (root !is JsonArray) {
                errors += "${file.name}: top level must be an array"
                continue
            }
            root.forEachIndexed { index, element ->
                val where = "${file.name}[$index]"
                val case = readCase(file.name, where, element, errors) ?: return@forEachIndexed
                val previous = seen.put(case.id, file.name)
                if (previous != null) errors += "$where: duplicate id '${case.id}' (also in $previous)"
                cases += case
            }
        }
        return cases
    }

    private fun readCase(file: String, where: String, element: JsonElement, errors: MutableList<String>): Case? {
        val obj = element as? JsonObject ?: run { errors += "$where: case must be an object"; return null }
        val unknown = obj.keys - CASE_KEYS
        if (unknown.isNotEmpty()) errors += "$where: unknown keys $unknown"
        val id = obj.string("id") ?: run { errors += "$where: missing string 'id'"; return null }
        val at = "$where '$id'"
        val sender = obj.string("sender") ?: run { errors += "$at: missing string 'sender'"; return null }
        val body = obj.string("body") ?: run { errors += "$at: missing string 'body'"; return null }
        val receivedAt = obj.string("receivedAt")?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?: run { errors += "$at: 'receivedAt' must be an ISO date-time with offset"; return null }
        val expect = obj["expect"] as? JsonObject ?: run { errors += "$at: missing object 'expect'"; return null }
        checkExpect(at, expect, errors)
        return Case(file, id, SmsMessage(sender, body, receivedAt.toInstant().toEpochMilli()), expect)
    }

    private fun checkExpect(at: String, expect: JsonObject, errors: MutableList<String>) {
        val type = expect.string("type")
        val allowed = when (type) {
            "payment" -> PAYMENT_KEYS
            "cardBill" -> CARD_BILL_KEYS
            "skipped" -> setOf("reason")
            "unreadable" -> emptySet()
            else -> { errors += "$at: unknown expect.type '$type'"; return }
        }
        val unknown = expect.keys - allowed - "type"
        if (unknown.isNotEmpty()) errors += "$at: unknown expect keys for $type: $unknown"
        fun checkEnum(key: String, values: Set<String>, nullable: Boolean = false) {
            val value = expect[key] ?: return
            if (value is JsonNull && nullable) return
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text !in values) errors += "$at: '$key' has unknown value $value"
        }
        checkEnum("direction", Direction.entries.names())
        checkEnum("instrumentType", InstrumentType.entries.names())
        checkEnum("channel", Channel.entries.names())
        checkEnum("confidence", Confidence.entries.names())
        checkEnum("reason", SkipReason.entries.names())
        for (key in listOf("amountMinor", "totalDueMinor")) {
            expect[key]?.let { if ((it as? JsonPrimitive)?.longOrNull == null) errors += "$at: '$key' must be a number" }
        }
        for (key in listOf("balanceMinor", "minimumDueMinor")) {
            expect[key]?.let { if (it !is JsonNull && (it as? JsonPrimitive)?.longOrNull == null) errors += "$at: '$key' must be a number or null" }
        }
        expect["occurredAt"]?.let { v ->
            if (v !is JsonNull && runCatching { OffsetDateTime.parse(v.text()) }.isFailure) errors += "$at: bad occurredAt $v"
        }
        expect["dueDate"]?.let { v ->
            if (v !is JsonNull && runCatching { LocalDate.parse(v.text()) }.isFailure) errors += "$at: bad dueDate $v"
        }
    }

    // ---- comparison ----

    private fun compare(expect: JsonObject, result: ParseResult): List<String> {
        val type = expect.string("type")
        val actualType = when (result) {
            is ParseResult.Payment -> "payment"
            is ParseResult.CardBill -> "cardBill"
            is ParseResult.Skipped -> "skipped"
            is ParseResult.Unreadable -> "unreadable"
        }
        if (type != actualType) return listOf("type: expected $type, actual ${describe(result)}")
        val actual: Map<String, Any?> = when (result) {
            is ParseResult.Payment -> paymentFields(result.payment)
            is ParseResult.CardBill -> billFields(result.bill)
            is ParseResult.Skipped -> mapOf("reason" to result.reason.name)
            is ParseResult.Unreadable -> emptyMap()
        }
        return expect.filterKeys { it != "type" }.mapNotNull { (key, expected) ->
            val value = actual[key]
            if (matches(key, expected, value)) null else "$key: expected $expected, actual ${value ?: "null"}"
        }
    }

    private fun matches(key: String, expected: JsonElement, actual: Any?): Boolean {
        if (expected is JsonNull) return actual == null
        if (actual == null) return false
        val primitive = expected as? JsonPrimitive ?: return false
        return when (key) {
            "counterparty", "bank" -> loose(primitive.content) == loose(actual.toString())
            "occurredAt" -> runCatching { OffsetDateTime.parse(primitive.content).toInstant().toEpochMilli() }.getOrNull() == actual
            "amountMinor", "balanceMinor", "totalDueMinor", "minimumDueMinor" -> primitive.longOrNull == actual
            else -> primitive.content == actual.toString()
        }
    }

    private fun paymentFields(p: ParsedPayment): Map<String, Any?> = mapOf(
        "direction" to p.direction.name,
        "amountMinor" to p.amountMinor,
        "currency" to p.currency,
        "counterparty" to p.counterparty,
        "vpa" to p.vpa,
        "bank" to p.bank,
        "instrumentType" to p.instrumentType.name,
        "instrumentLast4" to p.instrumentLast4,
        "channel" to p.channel.name,
        "referenceId" to p.referenceId,
        "balanceMinor" to p.balanceMinor,
        "occurredAt" to p.occurredAtMillis,
        "confidence" to p.confidence.name,
    )

    private fun billFields(b: ParsedCardBill): Map<String, Any?> = mapOf(
        "bank" to b.bank,
        "cardLast4" to b.cardLast4,
        "totalDueMinor" to b.totalDueMinor,
        "minimumDueMinor" to b.minimumDueMinor,
        "dueDate" to b.dueDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() },
    )

    private fun describe(result: ParseResult): String = when (result) {
        is ParseResult.Payment -> "payment(${result.payment.direction} ${result.payment.amountMinor})"
        is ParseResult.CardBill -> "cardBill(${result.bill.totalDueMinor})"
        is ParseResult.Skipped -> "skipped(${result.reason})"
        is ParseResult.Unreadable -> "unreadable(${result.hint})"
    }

    private fun loose(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.text(): String = (this as? JsonPrimitive)?.content ?: toString()

    private fun <E : Enum<E>> List<E>.names(): Set<String> = mapTo(HashSet()) { it.name }

    private companion object {
        val CASE_KEYS = setOf("id", "sender", "body", "receivedAt", "expect")
        val PAYMENT_KEYS = setOf(
            "direction", "amountMinor", "currency", "counterparty", "vpa", "bank", "instrumentType",
            "instrumentLast4", "channel", "referenceId", "balanceMinor", "occurredAt", "confidence",
        )
        val CARD_BILL_KEYS = setOf("bank", "cardLast4", "totalDueMinor", "minimumDueMinor", "dueDate")
    }
}
