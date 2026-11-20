package com.expenses.splits

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The shared test vectors (docs/server.md 6): the server runs the same files, so the phone and the server split and
 * balance every case to the same paisa. The folder comes from the build (`vectors.dirs`).
 */
class VectorsTest {
    private val folder: File? = System.getProperty("vectors.dirs").orEmpty()
        .split(File.pathSeparator)
        .map(::File)
        .firstOrNull { File(it, "splits.json").isFile }

    private fun load(name: String): JsonObject {
        assumeTrue("shared vectors not written yet", folder != null)
        return Json.parseToJsonElement(File(folder, name).readText()).jsonObject
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long

    @Test fun splits() {
        val cases = load("splits.json").getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(true, cases.size >= 30)
        for (case in cases) {
            val inputs = case.getValue("inputs").jsonArray.map { it.jsonObject }
                .associate { it.text("memberId") to (it["input"] as? JsonPrimitive)?.longOrNull }
            val expected = case.getValue("expected").jsonArray.map { it.jsonObject }.map { it.text("memberId") to it.number("sharePaise") }
            val shares = SplitMath.shares(SplitMethod.valueOf(case.text("method")), case.number("amountPaise"), inputs)
            assertEquals(case.text("name"), expected, shares.toList())
        }
    }

    @Test fun balances() {
        val cases = load("balances.json").getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(true, cases.size >= 15)
        for (case in cases) {
            val expenses = case.getValue("expenses").jsonArray.map { it.jsonObject }.map { e ->
                BalanceExpense(
                    paidBy = e.text("paidBy"),
                    amountMinor = e.number("amountPaise"),
                    shares = e.getValue("shares").jsonArray.map { it.jsonObject }.associate { it.text("memberId") to it.number("sharePaise") },
                )
            }
            val settlements = (case["settlements"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
                BalanceSettlement(it.text("from"), it.text("to"), it.number("amountPaise"))
            }
            val nets = Balances.nets(expenses, settlements)
            val name = case.text("name")
            assertEquals(name, case.getValue("expectedNets").jsonArray.map { it.jsonObject }.map { it.text("memberId") to it.number("netPaise") }, nets.toList())
            assertEquals(
                name,
                case.getValue("expectedTransfers").jsonArray.map { it.jsonObject }.map { Transfer(it.text("fromMemberId"), it.text("toMemberId"), it.number("amountPaise")) },
                Balances.simplify(nets),
            )
        }
    }

    @Test fun directIds() {
        val file = load("direct_ids.json")
        assertEquals(DirectIds.NAMESPACE.toString(), file.text("namespace"))
        for (case in file.getValue("cases").jsonArray.map { it.jsonObject }) {
            val group = DirectIds.groupId(case.text("userA"), case.text("userB"))
            assertEquals(case.text("groupId"), group)
            assertEquals(group, DirectIds.groupId(case.text("userB"), case.text("userA")))
            assertEquals(case.text("memberIdA"), DirectIds.memberId(group, case.text("userA")))
            assertEquals(case.text("memberIdB"), DirectIds.memberId(group, case.text("userB")))
        }
    }
}
