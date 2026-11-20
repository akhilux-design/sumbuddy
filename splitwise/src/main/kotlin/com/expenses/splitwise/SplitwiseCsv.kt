package com.expenses.splitwise

import com.expenses.splits.SplitMath
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

/**
 * Reads Splitwise's per-group (or per-friendship) "Export as spreadsheet" file (docs/splitwise-import.md 1 and 4,
 * docs/server.md 4.17). The file is read in memory only. Nothing here logs, and a refusal carries no content.
 *
 * The layout: a header `Date, Description, Category, Cost, Currency, <one column per person>`; one row per expense
 * or payment, each person's cell holding how the row changed their balance (paid minus owed); a "Total balance" row.
 */
object SplitwiseCsv {
    /** The largest file read: 5 MB. The app reads at most this plus one byte and says when it got more. */
    const val MAX_BYTES: Int = 5 * 1024 * 1024

    /** The most people in a file: the group cap (docs/server.md 4.6). */
    const val MAX_PEOPLE: Int = SplitMath.MAX_PARTICIPANTS

    /** The most imported rupee rows, and the most server items once rows with several payers are split. */
    const val MAX_ROWS: Int = 5_000

    /**
     * Parses an export.
     *
     * @param bytes the file, UTF-8 (a leading byte order mark is dropped).
     * @param truncated true when the file was longer than [MAX_BYTES] and was not read to the end.
     */
    fun parse(bytes: ByteArray, truncated: Boolean = false): SplitwiseResult {
        if (truncated || bytes.size > MAX_BYTES) return SplitwiseResult.Refused(Refusal.TOO_LARGE_FILE)
        val text = decode(bytes) ?: return SplitwiseResult.Refused(Refusal.NOT_SPLITWISE)
        return try {
            SplitwiseResult.Ok(FileParser(text.removePrefix("\uFEFF")).parse())
        } catch (refused: RefusedException) {
            SplitwiseResult.Refused(refused.reason)
        } catch (notCsv: CsvException) {
            SplitwiseResult.Refused(Refusal.NOT_SPLITWISE)
        }
    }

    /** Hex SHA-256 of the file's bytes, for "This file already came across" (decision 14). Stays on the phone. */
    fun fileHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun decode(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (notUtf8: CharacterCodingException) {
            null
        }
}

/** Stops parsing with [reason]. Carries nothing from the file. */
private class RefusedException(val reason: Refusal) : RuntimeException(null, null, false, false)

private fun refuse(reason: Refusal): Nothing = throw RefusedException(reason)

/** One file's parse (docs/splitwise-import.md 1.2 to 1.4). */
private class FileParser(text: String) {
    private val reader = CsvReader(text)
    private lateinit var people: List<String>

    private val rupeeRows = ArrayList<RupeeRow>()
    private val dateOrder = Dates.OrderEvidence()
    private val totals = ArrayList<CsvRecord>()
    private val leftOut = LinkedHashMap<String, Int>()
    private var balances = LongArray(0)
    private var noEffectRows = 0
    private var importedRows = 0
    private var items = 0

    fun parse(): SplitwiseFile {
        readHeader()
        while (true) {
            val record = reader.next() ?: break
            if (!record.isBlank) readRow(record)
        }
        if (importedRows == 0) refuse(Refusal.NOTHING_IN_RUPEES)
        if (importedRows > SplitwiseCsv.MAX_ROWS || items > SplitwiseCsv.MAX_ROWS) refuse(Refusal.TOO_MANY_ROWS)
        checkTotal()
        val order = dateOrder.order
        val expenses = ArrayList<SwExpense>()
        val payments = ArrayList<SwPayment>()
        for (row in rupeeRows) {
            val date = Dates.parse(row.dateToken, order) ?: refuse(Refusal.NOT_SPLITWISE)
            when (val kind = row.kind) {
                is RowKind.Payment -> payments += SwPayment(row.number, date, kind.from, kind.to, kind.amount)
                is RowKind.Expense -> expenses += SwExpense(row.number, date, row.description, row.category, kind.parts)
            }
        }
        return SplitwiseFile(
            people = people,
            expenses = expenses,
            payments = payments,
            balancesPaise = balances.toList(),
            leftOutByCurrency = leftOut.toMap(),
            noEffectRows = noEffectRows,
        )
    }

    /** The first record that is not blank: `date, description, category, cost, currency`, then the people. */
    private fun readHeader() {
        var header: CsvRecord
        do {
            header = reader.next() ?: refuse(Refusal.NOT_SPLITWISE)
        } while (header.isBlank)
        val cells = header.cells
        if (cells.size < FIXED || cells.take(FIXED).map { it.lowercase(Locale.ROOT) } != HEADER) {
            refuse(Refusal.NOT_SPLITWISE)
        }
        val names = cells.drop(FIXED).dropLastWhile { it.isEmpty() }
        val cleaned = names.map { name -> SplitwiseText.clean(name, SplitwiseText.MAX_PERSON_NAME) }
        if (cleaned.isEmpty() || cleaned.any { it.isEmpty() }) refuse(Refusal.NOT_SPLITWISE)
        if (cleaned.size < 2) refuse(Refusal.ONE_PERSON)
        if (cleaned.size > SplitwiseCsv.MAX_PEOPLE) refuse(Refusal.TOO_MANY_PEOPLE)
        people = cleaned
        balances = LongArray(cleaned.size)
    }

    private fun readRow(record: CsvRecord) {
        val cells = record.cells
        val width = FIXED + people.size
        if (cells.size < width || (width until cells.size).any { cells[it].isNotEmpty() }) refuse(Refusal.NOT_SPLITWISE)
        val description = cells[1]
        val cost = cells[3]
        if (cost.isEmpty() && description.equals(TOTAL_BALANCE, ignoreCase = true)) {
            totals += record
            return
        }
        val currency = cells[4].uppercase(Locale.ROOT)
        if (currency.isEmpty()) refuse(Refusal.NOT_SPLITWISE)
        val dateToken = Dates.token(cells[0])
        dateOrder.add(dateToken)
        if (currency != RUPEES) {
            leftOut[currency] = (leftOut[currency] ?: 0) + 1
            return
        }
        val costPaise = paiseOf(cost)
        val values = LongArray(people.size) { paiseOf(cells[FIXED + it]) }
        val kind = classify(cells[2], costPaise, values) ?: run {
            noEffectRows++
            return
        }
        for (person in values.indices) balances[person] += values[person]
        importedRows++
        items += when (kind) {
            is RowKind.Payment -> 1
            is RowKind.Expense -> kind.parts.size
        }
        // Past the limit the answer is TOO_MANY_ROWS (unless a later row is refused): stop keeping rows.
        if (importedRows > SplitwiseCsv.MAX_ROWS || items > SplitwiseCsv.MAX_ROWS) return
        rupeeRows += RupeeRow(
            number = record.number,
            dateToken = dateToken,
            description = SplitwiseText.clean(description, SplitwiseText.MAX_DESCRIPTION),
            category = SplitwiseText.clean(cells[2], SplitwiseText.MAX_DESCRIPTION),
            kind = kind,
        )
    }

    private fun paiseOf(cell: String): Long =
        when (val money = Money.parse(cell)) {
            is MoneyCell.Paise -> money.value
            MoneyCell.TooLarge -> refuse(Refusal.TOO_LARGE_AMOUNT)
            MoneyCell.Unreadable -> refuse(Refusal.NOT_SPLITWISE)
        }

    /**
     * Payer and shares from one rupee row's values (docs/splitwise-import.md 1.3), or null for a row that changes no
     * balance. [values] are paid minus owed per person; [cost] is the row's Cost.
     */
    private fun classify(category: String, cost: Long, values: LongArray): RowKind? {
        // 1. Splitwise rows are exact to the paisa.
        if (values.sum() != 0L) refuse(Refusal.DOES_NOT_ADD_UP)
        // 2. Someone paid only for themselves.
        if (values.all { it == 0L }) return null
        // 3.
        if (cost <= 0L) refuse(Refusal.DOES_NOT_ADD_UP)
        val positive = values.indices.filter { values[it] > 0 }
        val negative = values.indices.filter { values[it] < 0 }
        // 4. A payment: the positive person handed the money to the negative one.
        if (category.trim().equals(PAYMENT, ignoreCase = true) && positive.size == 1 && negative.size == 1 &&
            values[positive[0]] == cost
        ) {
            checkAmount(cost)
            return RowKind.Payment(from = positive[0], to = negative[0], amount = cost)
        }
        // 5. One payer: they paid the larger of the Cost and their value; their share is the difference.
        if (positive.size == 1) {
            val payer = positive[0]
            val amount = maxOf(cost, values[payer])
            checkAmount(amount)
            val shares = LinkedHashMap<Int, Long>()
            for (person in values.indices) {
                val share = if (person == payer) amount - values[payer] else -values[person]
                if (share > 0) shares[person] = share
            }
            return RowKind.Expense(listOf(SwPart(payer, amount, shares)))
        }
        // 6. Several payers (or income): one part per payer, shares filled from the people who owe, column order.
        val owed = LongArray(values.size) { if (values[it] < 0) -values[it] else 0L }
        var next = 0
        val parts = positive.map { payer ->
            val amount = values[payer]
            checkAmount(amount)
            val shares = LinkedHashMap<Int, Long>()
            var need = amount
            while (need > 0) {
                while (owed[negative[next]] == 0L) next++
                val person = negative[next]
                val take = minOf(need, owed[person])
                shares[person] = take
                owed[person] -= take
                need -= take
            }
            SwPart(payer, amount, shares)
        }
        return RowKind.Expense(parts)
    }

    private fun checkAmount(amount: Long) {
        if (amount > SplitMath.MAX_AMOUNT_MINOR) refuse(Refusal.TOO_LARGE_AMOUNT)
    }

    /**
     * A rupee-only file with exactly one rupee "Total balance" row: each of its values must equal the column's sum.
     * Skipped when other currencies are in the file (the shape of their total rows is not known).
     */
    private fun checkTotal() {
        if (leftOut.isNotEmpty() || totals.size != 1) return
        val total = totals.single().cells
        if (total[4].uppercase(Locale.ROOT) != RUPEES) return
        for (person in people.indices) {
            if (paiseOf(total[FIXED + person]) != balances[person]) refuse(Refusal.DOES_NOT_ADD_UP)
        }
    }

    private class RupeeRow(
        val number: Int,
        val dateToken: String,
        val description: String,
        val category: String,
        val kind: RowKind,
    )

    private sealed interface RowKind {
        class Payment(val from: Int, val to: Int, val amount: Long) : RowKind
        class Expense(val parts: List<SwPart>) : RowKind
    }

    private companion object {
        const val FIXED = 5
        val HEADER = listOf("date", "description", "category", "cost", "currency")
        const val TOTAL_BALANCE = "Total balance"
        const val PAYMENT = "Payment"
        const val RUPEES = "INR"
    }
}
