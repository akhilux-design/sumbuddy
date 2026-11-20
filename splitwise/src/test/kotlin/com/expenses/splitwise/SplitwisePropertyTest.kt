package com.expenses.splitwise

import com.expenses.splits.BalanceExpense
import com.expenses.splits.BalanceSettlement
import com.expenses.splits.Balances
import com.expenses.splitwise.TestFiles.money
import com.expenses.splitwise.TestFiles.ok
import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/splitwise-import.md 4: for seeded random valid files, the expenses and payments made from the file give back
 * each column's balance exactly (`Balances.nets` of `:splits`), every part's shares add up to its amount, and each
 * share is above 0.
 */
class SplitwisePropertyTest {
    @Test fun netsOfPartsAndPaymentsEqualTheFile() {
        val random = Random(20261119)
        repeat(400) { case ->
            val generated = generate(random)
            val file = ok(SplitwiseCsv.parse(generated.text.toByteArray(Charsets.UTF_8)))
            val message = "case $case"

            assertEquals(message, generated.balances.toList(), file.balancesPaise)
            assertEquals(message, generated.leftOut, file.leftOutByCurrency)
            assertEquals(message, generated.zeroRows, file.noEffectRows)
            assertEquals(message, generated.expenseRows, file.expenseCount)
            assertEquals(message, generated.paymentRows, file.payments.size)

            val parts = file.expenses.flatMap { it.parts }
            for (part in parts) {
                assertEquals(message, part.amountPaise, part.sharesPaise.values.sum())
                assertTrue(message, part.sharesPaise.values.all { it > 0 })
                assertTrue(message, part.amountPaise in 1..1_000_000_000L)
                assertEquals(message, part.sharesPaise.keys.sorted(), part.sharesPaise.keys.toList())
            }
            for (payment in file.payments) assertTrue(message, payment.from != payment.to && payment.amountPaise > 0)

            val nets = Balances.nets(
                parts.map { part -> BalanceExpense("m${part.payer}", part.amountPaise, part.sharesPaise.mapKeys { "m${it.key}" }) },
                file.payments.map { BalanceSettlement("m${it.from}", "m${it.to}", it.amountPaise) },
            )
            val fromParts = file.people.indices.map { nets["m$it"] ?: 0L }
            assertEquals(message, file.balancesPaise, fromParts)

            // Every item is sent once, in parts that respect the server's limits.
            val items = ImportParts.items(file, LocalDate.of(2026, 11, 19))
            assertEquals(message, file.itemCount, items.size)
            val chunks = ImportParts.chunk(items) { 400 + 60 * ((it as? SwItem.Expense)?.part?.sharesPaise?.size ?: 1) }
            assertTrue(message, chunks.all { it.size in 1..ImportParts.MAX_ITEMS })
            assertEquals(message, items, chunks.flatten())
        }
    }

    private class Generated(
        val text: String,
        val balances: LongArray,
        val leftOut: Map<String, Int>,
        val zeroRows: Int,
        val expenseRows: Int,
        val paymentRows: Int,
    )

    /** A valid export: random people, expenses (one or several payers), payments, all-zero and foreign rows. */
    private fun generate(random: Random): Generated {
        val people = random.nextInt(2, 9)
        val withForeign = random.nextInt(4) == 0
        val lines = ArrayList<String>()
        lines += "Date,Description,Category,Cost,Currency," + (0 until people).joinToString(",") { "Person $it" }
        val balances = LongArray(people)
        val leftOut = LinkedHashMap<String, Int>()
        var zeroRows = 0
        var expenseRows = 0
        var paymentRows = 0
        var date = LocalDate.of(2024, 1, 1)
        repeat(random.nextInt(1, 40)) {
            date = date.plusDays(random.nextLong(0, 4))
            val values = LongArray(people)
            var cost: Long
            var category = listOf("General", "Dining out", "Groceries", "Rent", "Taxi").random(random)
            var currency = "INR"
            when (random.nextInt(10)) {
                in 0..4 -> { // one payer, who may or may not be in the split
                    cost = random.nextLong(1, 5_000_000)
                    val payer = random.nextInt(people)
                    val sharers = (0 until people).filter { random.nextBoolean() }.ifEmpty { listOf(random.nextInt(people)) }
                    val shares = randomSplit(random, cost, sharers.size)
                    values[payer] += cost
                    sharers.forEachIndexed { i, person -> values[person] -= shares[i] }
                }
                in 5..6 -> { // several payers
                    cost = random.nextLong(2, 5_000_000)
                    val payers = (0 until people).shuffled(random).take(random.nextInt(2, people + 1))
                    val paid = randomSplit(random, cost, payers.size)
                    payers.forEachIndexed { i, person -> values[person] += paid[i] }
                    val sharers = (0 until people).filter { random.nextBoolean() }.ifEmpty { listOf(0) }
                    val shares = randomSplit(random, cost, sharers.size)
                    sharers.forEachIndexed { i, person -> values[person] -= shares[i] }
                }
                7 -> { // a payment
                    val from = random.nextInt(people)
                    val to = (from + random.nextInt(1, people)) % people
                    cost = random.nextLong(1, 1_000_000)
                    values[from] += cost
                    values[to] -= cost
                    category = "Payment"
                }
                8 -> { // paid only for themselves
                    cost = random.nextLong(1, 100_000)
                }
                else -> { // another currency, if this file has them
                    cost = random.nextLong(1, 100_000)
                    if (withForeign) {
                        currency = listOf("USD", "EUR").random(random)
                        values[0] += cost
                        values[1] -= cost
                    }
                }
            }
            val description = listOf("Dinner", "\"Cab, airport\"", "Rent", "", "Chai \"\"special\"\"").random(random)
            val dateCell = if (random.nextBoolean()) date.toString() else "${date}T09:30:00Z"
            lines += "$dateCell,$description,$category,${cell(random, cost)},$currency," +
                values.joinToString(",") { cell(random, it) }
            if (currency != "INR") {
                leftOut[currency] = (leftOut[currency] ?: 0) + 1
                return@repeat
            }
            when {
                values.all { it == 0L } -> zeroRows++
                category == "Payment" -> paymentRows++
                else -> expenseRows++
            }
            for (i in values.indices) balances[i] += values[i]
            if (random.nextInt(8) == 0) lines += if (random.nextBoolean()) "" else " , , "
        }
        // Make sure there is something in rupees.
        if (expenseRows + paymentRows == 0) {
            lines += "$date,Last,General,1.00,INR,1.00,-1.00" + ",0.00".repeat(people - 2)
            balances[0] += 100
            balances[1] -= 100
            expenseRows++
        }
        if (leftOut.isEmpty() && random.nextBoolean()) {
            lines += "$date,Total balance, , ,INR," + balances.joinToString(",") { money(it) }
        }
        val separator = if (random.nextBoolean()) "\r\n" else "\n"
        val text = (if (random.nextBoolean()) "﻿" else "") + lines.joinToString(separator)
        return Generated(text, balances, leftOut, zeroRows, expenseRows, paymentRows)
    }

    /** [total] in [n] random parts, each at least 1 when [total] allows, adding up exactly. */
    private fun randomSplit(random: Random, total: Long, n: Int): List<Long> {
        if (n == 1) return listOf(total)
        val cuts = List(n - 1) { random.nextLong(0, total + 1) }.sorted()
        val bounds = listOf(0L) + cuts + total
        return bounds.zipWithNext { a, b -> b - a }
    }

    /** A money cell, sometimes grouped and quoted as a spreadsheet writes it, sometimes with one decimal. */
    private fun cell(random: Random, paise: Long): String {
        val plain = money(paise)
        return when {
            kotlin.math.abs(paise) >= 100_000 && random.nextInt(3) == 0 -> "\"" + grouped(plain) + "\""
            paise % 10 == 0L && random.nextInt(3) == 0 -> plain.dropLast(1)
            else -> plain
        }
    }

    /** "-123456.78" as "-123,456.78". */
    private fun grouped(plain: String): String {
        val sign = if (plain.startsWith("-")) "-" else ""
        val (whole, fraction) = plain.removePrefix("-").split(".")
        return sign + whole.reversed().chunked(3).joinToString(",").reversed() + "." + fraction
    }
}
