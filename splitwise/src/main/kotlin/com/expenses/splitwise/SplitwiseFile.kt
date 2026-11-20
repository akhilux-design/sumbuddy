package com.expenses.splitwise

import java.time.LocalDate

/** What [SplitwiseCsv.parse] made of a file. */
sealed interface SplitwiseResult {
    /** The file can come across. */
    data class Ok(val file: SplitwiseFile) : SplitwiseResult

    /** The file cannot come across, for [reason] (design 02-everyday/30 and 136). */
    data class Refused(val reason: Refusal) : SplitwiseResult
}

/**
 * Why a file cannot come across (docs/splitwise-import.md 1.4). Each has its own title and body on Import from
 * Splitwise (5.7); the screen shows the file name, never anything read from the file.
 */
enum class Refusal {
    /** Not a Splitwise export: not UTF-8 text, not CSV, another header (a translated export too), a bad cell. */
    NOT_SPLITWISE,

    /** A rupee row whose values do not sum to 0, a cost of 0 or less with values, or a disagreeing total row. */
    DOES_NOT_ADD_UP,

    /** Over [SplitwiseCsv.MAX_BYTES]. */
    TOO_LARGE_FILE,

    /** More than [SplitwiseCsv.MAX_PEOPLE] people. */
    TOO_MANY_PEOPLE,

    /** More than [SplitwiseCsv.MAX_ROWS] imported rupee rows, or server items once rows with several payers split. */
    TOO_MANY_ROWS,

    /** An expense or payment over ₹1 crore, the most one expense can be (docs/server.md 4.7). */
    TOO_LARGE_AMOUNT,

    /** No rupee row that changes a balance (only other currencies, an empty export, only all-zero rows). */
    NOTHING_IN_RUPEES,

    /** Only one person in the file. */
    ONE_PERSON,
}

/**
 * A Splitwise export that can come across: the rupee rows only, as expenses with payers and shares and as payments.
 * People are column indexes into [people] everywhere. Holds the file's names, descriptions and amounts: keep it in
 * memory, never log it.
 *
 * @param people the header's names in file order, cleaned ([SplitwiseText.clean], 60). Names may repeat.
 * @param expenses rupee expense rows in file order (rules 5 and 6 of 1.3).
 * @param payments rupee Payment rows in file order (rule 4).
 * @param balancesPaise per person, the sum of their rupee values: positive is owed, negative owes. Equals the nets of
 * [expenses] and [payments] together.
 * @param leftOutByCurrency rows in other currencies, by upper-cased currency code; they do not come across.
 * @param noEffectRows rupee rows whose values are all 0 (someone paid only for themselves); left out.
 */
data class SplitwiseFile(
    val people: List<String>,
    val expenses: List<SwExpense>,
    val payments: List<SwPayment>,
    val balancesPaise: List<Long>,
    val leftOutByCurrency: Map<String, Int>,
    val noEffectRows: Int,
) {
    /** Server items: one per expense part and one per payment. At most [SplitwiseCsv.MAX_ROWS]. */
    val itemCount: Int get() = expenses.sumOf { it.parts.size } + payments.size

    /** Expense rows, for "46 expenses · 3 people" (29) and "46 past expenses" (31). Payments are not counted. */
    val expenseCount: Int get() = expenses.size

    /** People in the file, for "46 expenses · 3 people". */
    val peopleCount: Int get() = people.size

    /** Rows in other currencies, for "3 expenses in other currencies stay out" (134). */
    val leftOutCount: Int get() = leftOutByCurrency.values.sum()
}

/**
 * One rupee expense row.
 *
 * @param row the file's record number (the spreadsheet row), for ordering.
 * @param date the date as in the file; [ImportParts.clampDate] makes it one the server takes.
 * @param description the row's description, cleaned ([SplitwiseText.clean], 80); may be blank (then the app uses the
 * category's name).
 * @param category Splitwise's category text as in the file (spaces trimmed); [SplitwiseCategories.map] maps it.
 * @param parts one part for a row with one payer; one per payer, in column order, for a row with several.
 */
data class SwExpense(
    val row: Int,
    val date: LocalDate,
    val description: String,
    val category: String,
    val parts: List<SwPart>,
)

/**
 * One expense as the server takes it: [payer] paid [amountPaise], split as [sharesPaise].
 *
 * @param payer person index.
 * @param amountPaise 1 to ₹1 crore in paise.
 * @param sharesPaise person index to share, in column order, every share above 0; adds up to [amountPaise]. The
 * payer is in it only when they had a share.
 */
data class SwPart(val payer: Int, val amountPaise: Long, val sharesPaise: Map<Int, Long>)

/**
 * One rupee Payment row: [from] handed [amountPaise] to [to] (a settlement, method Other).
 *
 * @param row the file's record number, for ordering.
 * @param date the date as in the file; see [ImportParts.clampDate].
 */
data class SwPayment(val row: Int, val date: LocalDate, val from: Int, val to: Int, val amountPaise: Long)
