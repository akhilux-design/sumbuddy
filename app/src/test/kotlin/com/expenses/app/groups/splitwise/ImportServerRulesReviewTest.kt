package com.expenses.app.groups.splitwise

import com.expenses.app.data.category.Category
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.data.GroupBalance
import com.expenses.splitwise.SplitwiseCsv
import com.expenses.splitwise.SplitwiseResult
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Review (2026-11-19): synthetic Splitwise exports through `:splitwise` and [ImportPlanner], checked against the
 * server's own rules for `POST /v1/groups` and `POST /v1/groups/{gid}/import` (docs/server.md 4.6, 4.7, 4.17;
 * `server/src/validate.ts`, `routes/ledger.ts` `parseExpenseFields`, `routes/imports.ts` `parseItems`, `domain/splits.ts`
 * `computeShares`). A part the server refuses takes the whole import back (docs/splitwise-import.md 5.4), so every
 * item the phone sends must pass. Balances after import must equal the file's columns.
 */
class ImportServerRulesReviewTest {
    private val user = UserDto("a0000000-0000-4000-8000-000000000001", "Arjun Mehta", null, null)
    private val today = LocalDate.of(2026, 11, 19)
    private val now = Instant.parse("2026-11-19T06:30:00Z")
    private val categoryName: (Category) -> String = { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase) }

    // ---- The server's rules, ported from server/src ----

    /** `String.prototype.trim` (ECMAScript WhiteSpace and LineTerminator), which `v.text` applies. */
    private fun isJsSpace(c: Char): Boolean =
        c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r' || c == ' ' || c == ' ' || c == ' ' ||
            c in ' '..' ' || c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　' ||
            c == '﻿'

    private val forbidden = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]")
    private val dateRe = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val categories = setOf("FOOD", "GROCERIES", "TRAVEL", "RENT_AND_BILLS", "SHOPPING", "FUN", "OTHER")
    private val maxAmount = 1_000_000_000L

    /** `v.text(v, field, min, max)`: null when the server answers `400`. */
    private fun serverText(value: String?, min: Int, max: Int): String? {
        if (value == null) return null
        val t = value.trim(::isJsSpace)
        val length = t.codePointCount(0, t.length)
        if (length < min || length > max) return null
        if (forbidden.containsMatchIn(t)) return null
        return t
    }

    private fun serverDateOk(value: String?): Boolean {
        if (value == null || !dateRe.matches(value)) return false
        val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return false
        return !date.isBefore(LocalDate.of(2000, 1, 1)) && !date.isAfter(today.plusDays(1))
    }

    /** `computeShares("EQUAL", …)`: sorted by member id, the extra paise to the first ones. */
    private fun serverEqual(amount: Long, ids: List<String>): Map<String, Long> {
        val sorted = ids.sorted()
        val base = amount / sorted.size
        val extra = amount % sorted.size
        return sorted.mapIndexed { i, id -> id to base + if (i < extra) 1 else 0 }.toMap()
    }

    /** Every reason the server would refuse [item] (index [i]); empty when it would be written. */
    private fun problemsOf(i: Int, item: JsonObject, members: Set<String>): List<String> {
        val out = ArrayList<String>()
        val p = "items[$i]"
        fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun long(o: JsonObject, key: String): Long? = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val id = str(item, "id")
        if (id == null || !uuidV4.matches(id)) out += "$p.id"
        when (str(item, "kind")) {
            "expense" -> {
                val amount = long(item, "amountPaise")
                if (amount == null || amount !in 1..maxAmount) out += "$p.amountPaise"
                val method = str(item, "splitMethod")
                if (method !in setOf("EQUAL", "AMOUNTS")) out += "$p.splitMethod"
                val shares = item["shares"]?.jsonArray
                if (shares == null || shares.size !in 1..50) {
                    out += "$p.shares"
                } else {
                    val seen = HashSet<String>()
                    var sum = 0L
                    val byId = LinkedHashMap<String, Long>()
                    shares.forEachIndexed { j, raw ->
                        val s = raw.jsonObject
                        val memberId = str(s, "memberId")
                        if (memberId == null || !seen.add(memberId)) out += "$p.shares[$j].memberId"
                        if (memberId != null && memberId !in members) out += "$p.shares[$j].memberId (not a member)"
                        val share = long(s, "sharePaise")
                        if (share == null || share !in 0..maxAmount) out += "$p.shares[$j].sharePaise"
                        val input = s["input"]
                        when (method) {
                            "EQUAL" -> if (input != null && input !is JsonNull) out += "$p.shares[$j].input"
                            "AMOUNTS" -> if ((input as? JsonPrimitive)?.longOrNull != share) out += "$p.shares[$j].input"
                        }
                        sum += share ?: 0
                        if (memberId != null && share != null) byId[memberId] = share
                    }
                    if (sum != amount) out += "$p.shares (sum)"
                    if (method == "EQUAL" && amount != null && byId.size == shares.size && serverEqual(amount, byId.keys.toList()) != byId) {
                        out += "$p.shares (shares_mismatch)"
                    }
                }
                if (serverText(str(item, "description"), 1, 80) == null) out += "$p.description"
                if (str(item, "category") !in categories) out += "$p.category"
                if (!serverDateOk(str(item, "occurredOn"))) out += "$p.occurredOn"
                val payer = str(item, "paidByMemberId")
                if (payer == null || payer !in members) out += "$p.paidByMemberId"
                val auto = item["auto"]
                if (auto != null && auto !is JsonNull && (auto as? JsonPrimitive)?.booleanOrNull != false) out += "$p.auto"
                val base = item["baseVersion"]
                if (base != null && base !is JsonNull) out += "$p.baseVersion"
            }
            "settlement" -> {
                val from = str(item, "fromMemberId")
                val to = str(item, "toMemberId")
                if (from == null || from !in members) out += "$p.fromMemberId"
                if (to == null || to !in members || to == from) out += "$p.toMemberId"
                val amount = long(item, "amountPaise")
                if (amount == null || amount !in 1..maxAmount) out += "$p.amountPaise"
                if (!serverDateOk(str(item, "occurredOn"))) out += "$p.occurredOn"
            }
            else -> out += "$p.kind"
        }
        return out
    }

    // ---- Synthetic exports ----

    private val namePool = listOf(
        "Arjun Mehta", "Priya Nair", "Kiran Rao", "Meera", "Rahul 🙂", "Zoë Fernandes", "Ravi, Jr.",
        "Sam \"SK\" Kumar", "A".repeat(70), "😀".repeat(40), "x" + "😀".repeat(40), "  Spaced   Name  ",
        "Name\twith tab", "Priya Nair", " Nbsp Name ", "‮Reversed",
    )

    private val descriptionPool = listOf(
        "Rent", "Dinner, drinks", "He said \"hi\"", "Rent\nApril", "  Tea ", " ", "", "Swiggy",
        "🍕".repeat(41) + "x", "x" + "🍕".repeat(41), "क".repeat(90), "a\u0007bell", "‮evil‬",
        "Café ☕ with the whole gang near the lake on a rainy evening, and then some more words",
        "Blinkit", "Electricity", "Uber to airport",
    )

    private val categoryPool = listOf("General", "Dining out", "Rent", "Groceries", "Taxi", "Utilities - Other", "", "Mystery", "Food and drink - Other")

    private fun cell(text: String): String =
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || text != text.trim { it == ' ' || it == '\t' }) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else {
            text
        }

    private fun money(paise: Long): String {
        val sign = if (paise < 0) "-" else ""
        val a = abs(paise)
        return "$sign${a / 100}.${"%02d".format(a % 100)}"
    }

    /** A random share in paise, sometimes tiny, sometimes large (50 of the largest stay under ₹1 crore). */
    private fun Random.share(): Long = when (nextInt(10)) {
        0 -> 1L
        1 -> nextLong(1, 10_000_000)
        else -> nextLong(1, 500_000)
    }

    private class Synthetic(val csv: String, val balances: LongArray)

    private fun synthetic(random: Random): Synthetic {
        val n = if (random.nextInt(8) == 0) 50 else random.nextInt(2, 9)
        val people = List(n) { namePool[random.nextInt(namePool.size)] }
        val balances = LongArray(n)
        val sb = StringBuilder()
        if (random.nextBoolean()) sb.append('﻿')
        sb.append("Date,Description,Category,Cost,Currency,").append(people.joinToString(",") { cell(it) }).append("\r\n")
        var foreign = false
        var lastDate = LocalDate.of(2025, 1, 1)
        repeat(random.nextInt(1, 260)) {
            val values = LongArray(n)
            var category = categoryPool[random.nextInt(categoryPool.size)]
            var currency = "INR"
            val cost: Long
            when (random.nextInt(20)) {
                in 0..2 -> { // A payment.
                    val from = random.nextInt(n)
                    val to = (from + 1 + random.nextInt(n - 1)) % n
                    val a = random.share()
                    values[from] = a
                    values[to] = -a
                    category = "Payment"
                    cost = a
                }
                3 -> { // Several payers.
                    if (n < 3) return@repeat
                    val payers = (0 until n).shuffled(random).take(2)
                    val a = random.share()
                    val b = random.share()
                    values[payers[0]] = a
                    values[payers[1]] = b
                    val owers = (0 until n).filter { it !in payers }.shuffled(random).take(random.nextInt(1, n - 1))
                    var left = a + b
                    owers.forEachIndexed { k, o ->
                        val take = if (k == owers.lastIndex) left else random.nextLong(0, left + 1)
                        values[o] -= take
                        left -= take
                    }
                    cost = a + b
                }
                4 -> { // Paid only for themselves: no effect.
                    cost = random.share()
                }
                5 -> { // Another currency.
                    currency = "USD"
                    foreign = true
                    val a = random.share()
                    values[0] = a
                    values[1] = -a
                    cost = a
                }
                else -> { // One payer, maybe not in the split; sometimes split equally as Splitwise does.
                    val payer = random.nextInt(n)
                    val owers = (0 until n).filter { it != payer }.shuffled(random).take(random.nextInt(1, n))
                    val payerIn = random.nextBoolean()
                    val shares = LinkedHashMap<Int, Long>()
                    if (random.nextInt(3) == 0) {
                        val parties = (if (payerIn) owers + payer else owers).shuffled(random)
                        val total = random.nextLong(1, 500_000) * parties.size + random.nextLong(0, parties.size.toLong())
                        val base = total / parties.size
                        val extra = (total % parties.size).toInt()
                        parties.forEachIndexed { k, who -> shares[who] = base + if (k < extra) 1 else 0 }
                    } else {
                        owers.forEach { shares[it] = random.share() }
                        if (payerIn) shares[payer] = random.share()
                    }
                    if (shares.values.any { it == 0L }) return@repeat
                    cost = shares.values.sum()
                    for ((who, s) in shares) values[who] -= s
                    values[payer] += cost
                }
            }
            val date = LocalDate.of(1999, 6, 1).plusDays(random.nextLong(0, 10_400))
            lastDate = date
            val description = descriptionPool[random.nextInt(descriptionPool.size)]
            if (currency == "INR") for (k in 0 until n) balances[k] += values[k]
            sb.append(date).append(',').append(cell(description)).append(',').append(cell(category)).append(',')
                .append(money(cost)).append(',').append(currency).append(',')
                .append(values.joinToString(",") { money(it) }).append(if (random.nextBoolean()) "\r\n" else "\n")
        }
        if (!foreign && random.nextBoolean()) {
            sb.append(lastDate).append(",Total balance, , ,INR,").append(balances.joinToString(",") { money(it) }).append("\n")
        }
        return Synthetic(sb.toString(), balances)
    }

    /** Seeded v4 ids, so a failure can be replayed. */
    private fun ids(random: Random): () -> String = {
        val msb = (random.nextLong() and -0xf001L) or 0x4000L
        val lsb = (random.nextLong() and 0x3fffffffffffffffL) or Long.MIN_VALUE
        UUID(msb, lsb).toString()
    }

    @Test fun randomExportsKeepTheirBalancesAndPassEveryServerCheck() {
        var planned = 0
        for (seed in 1..60) {
            val random = Random(seed)
            val synthetic = synthetic(random)
            val read = SplitwiseCsv.parse(synthetic.csv.toByteArray(Charsets.UTF_8))
            if (read !is SplitwiseResult.Ok) {
                // A file with no rupee row that changes a balance is refused, as it should be.
                if (synthetic.balances.all { it == 0L } && read == SplitwiseResult.Refused(com.expenses.splitwise.Refusal.NOTHING_IN_RUPEES)) continue
                fail("seed $seed: $read")
                return
            }
            val file = read.file
            assertEquals("seed $seed: the module's balances", synthetic.balances.toList(), file.balancesPaise)
            val me = random.nextInt(file.people.size)
            val plan = ImportPlanner.plan(file, me, "Flat 302", user, today, now, ids(random), categoryName)

            // The people: column me is the user, everyone else a friend without the app, in column order.
            assertEquals(user.id, plan.members[me].userId)
            assertEquals(file.people.size - 1, plan.friends.size)
            // Balances after import equal the file's, column by column.
            val balance = GroupBalance.of(plan.expenses, plan.settlements)
            plan.members.forEachIndexed { index, member ->
                assertEquals("seed $seed: net of column $index", synthetic.balances[index], balance.netOf(member.id))
            }

            // POST /v1/groups (4.6).
            assertNotNull("seed $seed: group name", serverText(plan.group.name, 1, 40))
            assertTrue("seed $seed: members", plan.friends.size <= 49)
            plan.friends.forEachIndexed { i, friend -> assertNotNull("seed $seed: members[$i].name '${friend.name}'", serverText(friend.name, 1, 60)) }

            // POST /v1/groups/{gid}/import (4.17), part by part.
            val memberIds = plan.members.map { it.id }.toSet()
            val allIds = HashSet<String>()
            for (part in plan.parts) {
                val text = ApiJson.encodeToString(JsonElement.serializer(), part.body)
                assertTrue("seed $seed: body of ${text.toByteArray(Charsets.UTF_8).size} bytes", text.toByteArray(Charsets.UTF_8).size <= 30_000)
                val items = part.body.getValue("items").jsonArray
                assertTrue("seed $seed: ${items.size} items", items.size in 1..50)
                items.forEachIndexed { i, raw ->
                    val item = raw.jsonObject
                    val problems = problemsOf(i, item, memberIds)
                    assertTrue("seed $seed: the server refuses ${problems.joinToString()}", problems.isEmpty())
                    assertTrue("seed $seed: duplicate id", allIds.add(item.getValue("id").jsonPrimitive.content))
                }
            }
            assertEquals(plan.itemCount, allIds.size)
            planned++
        }
        assertTrue("only $planned of 60 files came across", planned >= 50)
    }

    /**
     * A description cell that holds only U+FEFF (a stray byte order mark, as some editors leave when they join files).
     * The phone keeps it as text (Kotlin's `trim` does not count U+FEFF as a space), so it is not replaced by the
     * category's name, but the server's `v.text` trims it away (`String.prototype.trim` does) and answers `400
     * items[0].description`: the whole import is taken back.
     */
    @Test fun aDescriptionOfOnlyAByteOrderMarkPassesTheServersTextRule() {
        val csv = "Date,Description,Category,Cost,Currency,Arjun Mehta,Priya Nair\n" +
            "2026-11-01,﻿,Rent,300.00,INR,150.00,-150.00\n"
        val file = (SplitwiseCsv.parse(csv.toByteArray(Charsets.UTF_8)) as SplitwiseResult.Ok).file
        val plan = ImportPlanner.plan(file, 0, "Flat 302", user, today, now, ids(Random(1)), categoryName)
        val item = plan.parts.single().body.getValue("items").jsonArray.single().jsonObject
        assertEquals(emptyList<String>(), problemsOf(0, item, plan.members.map { it.id }.toSet()))
    }

    /**
     * A header cell that holds only U+FEFF passes the module's "blank name" check and the phone's own cleaning, and
     * goes out as a friend's name in `POST /v1/groups`; the server trims it to nothing and refuses the create (`400
     * members[0].name`), so the import fails after "Import" was accepted, instead of the file being refused on 30.
     */
    @Test fun aPersonNamedOnlyAByteOrderMarkPassesTheServersNameRule() {
        val csv = "Date,Description,Category,Cost,Currency,Arjun Mehta,﻿\n" +
            "2026-11-01,Rent,Rent,300.00,INR,150.00,-150.00\n"
        val read = SplitwiseCsv.parse(csv.toByteArray(Charsets.UTF_8))
        if (read !is SplitwiseResult.Ok) return // Refused on the phone (30): fine.
        val plan = ImportPlanner.plan(read.file, 0, "Flat 302", user, today, now, ids(Random(1)), categoryName)
        plan.friends.forEachIndexed { i, friend -> assertNotNull("members[$i].name", serverText(friend.name, 1, 60)) }
    }
}
