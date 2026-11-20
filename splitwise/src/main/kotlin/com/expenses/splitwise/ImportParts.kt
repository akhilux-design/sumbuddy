package com.expenses.splitwise

import java.time.LocalDate

/** One server item of an import (docs/server.md 4.17): an expense part or a payment, with the date it is sent with. */
sealed interface SwItem {
    /** The file's record number of the row it came from. */
    val row: Int

    /** The row's date clamped to what the server takes ([ImportParts.clampDate]). */
    val occurredOn: LocalDate

    /** Part [partIndex] of [expense]: an expense item. */
    data class Expense(val expense: SwExpense, val partIndex: Int, override val occurredOn: LocalDate) : SwItem {
        override val row: Int get() = expense.row
        val part: SwPart get() = expense.parts[partIndex]
    }

    /** A settlement item. */
    data class Payment(val payment: SwPayment, override val occurredOn: LocalDate) : SwItem {
        override val row: Int get() = payment.row
    }
}

/**
 * The order and parts of an import's requests (docs/splitwise-import.md 3.2 and 5.2): items in date order, then file
 * order, sent in parts of at most [MAX_ITEMS] items and [MAX_BODY_BYTES] bytes of body.
 */
object ImportParts {
    /** The most items in one `POST /v1/groups/{gid}/import` (the server refuses more). */
    const val MAX_ITEMS: Int = 50

    /** The largest encoded body of one part, in UTF-8 bytes; the server's cap is 32 KB. */
    const val MAX_BODY_BYTES: Int = 30_000

    /** The bytes of a body around its items: `{"items":[` and `]}`, as compact JSON encodes it. */
    const val ENVELOPE_BYTES: Int = 12

    /** The earliest date the server takes (docs/server.md 4.7). */
    val FIRST_DATE: LocalDate = LocalDate.of(2000, 1, 1)

    /**
     * [date] as it is sent: a date after [today] (a planned Splitwise expense) becomes [today], one before
     * 2000-01-01 becomes 2000-01-01 (docs/splitwise-import.md 1.4, decision 17). [today] is India's date.
     */
    fun clampDate(date: LocalDate, today: LocalDate): LocalDate =
        when {
            date.isAfter(today) -> today
            date.isBefore(FIRST_DATE) -> FIRST_DATE
            else -> date
        }

    /**
     * Every server item of [file] (one per expense part, one per payment) in the order they are sent: by clamped
     * date, then file row, then part. [SplitwiseFile.itemCount] items.
     */
    fun items(file: SplitwiseFile, today: LocalDate): List<SwItem> {
        val items = ArrayList<SwItem>(file.itemCount)
        for (expense in file.expenses) {
            val on = clampDate(expense.date, today)
            expense.parts.indices.mapTo(items) { SwItem.Expense(expense, it, on) }
        }
        file.payments.mapTo(items) { SwItem.Payment(it, clampDate(it.date, today)) }
        return items.sortedWith(
            compareBy<SwItem>({ it.occurredOn }, { it.row }, { (it as? SwItem.Expense)?.partIndex ?: 0 }),
        )
    }

    /**
     * [items] in parts, greedily and in order: a part ends when one more item would make [MAX_ITEMS] + 1 items or a
     * body over [MAX_BODY_BYTES]. The body is `{"items":[` + the items joined by `,` + `]}`, so its size is
     * [ENVELOPE_BYTES] + the items' sizes + one byte per comma.
     *
     * @param itemBytes the UTF-8 size of one item as encoded in the body (compact JSON).
     */
    fun <T> chunk(items: List<T>, itemBytes: (T) -> Int): List<List<T>> {
        val parts = ArrayList<List<T>>()
        var current = ArrayList<T>()
        var bytes = ENVELOPE_BYTES
        for (item in items) {
            val size = itemBytes(item)
            val added = if (current.isEmpty()) size else size + 1
            if (current.isNotEmpty() && (current.size == MAX_ITEMS || bytes + added > MAX_BODY_BYTES)) {
                parts += current
                current = ArrayList()
                bytes = ENVELOPE_BYTES
            }
            bytes += if (current.isEmpty()) size else size + 1
            current += item
        }
        if (current.isNotEmpty()) parts += current
        return parts
    }
}
