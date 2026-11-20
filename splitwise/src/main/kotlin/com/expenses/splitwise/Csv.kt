package com.expenses.splitwise

/**
 * One CSV record.
 *
 * @param number 1-based record number in the file, blank records included: the spreadsheet row when no cell holds a
 * line break.
 * @param cells the cells, each with spaces and tabs trimmed at both ends.
 */
internal class CsvRecord(val number: Int, val cells: List<String>) {
    /** True when every cell is blank: a blank line, or `, , ,`. */
    val isBlank: Boolean get() = cells.all { it.isEmpty() }
}

/** The text is not CSV (an unterminated quote, text after a closing quote, an absurd number of cells). No content. */
internal class CsvException : RuntimeException()

/**
 * An RFC 4180 reader (docs/splitwise-import.md 1.2), one record at a time so a 5 MB file is never held twice:
 * comma-separated; records end in CRLF, LF or CR; a cell that starts with `"` (after spaces or tabs) is quoted, `""`
 * inside it is a quote, and commas and line breaks inside quotes belong to the cell. Only spaces and tabs may follow
 * a closing quote. A `"` inside an unquoted cell is kept as it is.
 */
internal class CsvReader(private val text: String) {
    private var index = 0
    private var number = 0
    private var done = false

    /** The next record, or null at the end of the text. */
    fun next(): CsvRecord? {
        if (done) return null
        val cells = ArrayList<String>()
        number++
        while (true) {
            cells += readCell()
            if (cells.size > MAX_CELLS) throw CsvException()
            if (index >= text.length) {
                done = true
                return CsvRecord(number, cells)
            }
            when (text[index]) {
                ',' -> index++
                else -> {
                    // A line break: CRLF, LF or CR. A break at the very end of the text ends the last record.
                    index += if (text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n') 2 else 1
                    if (index >= text.length) done = true
                    return CsvRecord(number, cells)
                }
            }
        }
    }

    /** Reads one cell and leaves [index] on the comma, the line break or the end of the text after it. */
    private fun readCell(): String {
        var start = index
        while (start < text.length && text[start].isBlankChar()) start++
        if (start < text.length && text[start] == '"') return readQuoted(start + 1)
        var end = index
        while (end < text.length && !text[end].isCellEnd()) end++
        val cell = text.substring(index, end).trimBlank()
        index = end
        return cell
    }

    private fun readQuoted(from: Int): String {
        val cell = StringBuilder()
        var at = from
        while (true) {
            if (at >= text.length) throw CsvException() // unterminated quote
            val c = text[at]
            if (c == '"') {
                if (at + 1 < text.length && text[at + 1] == '"') {
                    cell.append('"')
                    at += 2
                } else {
                    at++
                    break
                }
            } else {
                cell.append(c)
                at++
            }
        }
        while (at < text.length && text[at].isBlankChar()) at++
        if (at < text.length && !text[at].isCellEnd()) throw CsvException()
        index = at
        return cell.toString().trimBlank()
    }

    companion object {
        /**
         * More cells than any export has (5 + 50 people, and spreadsheet padding). Past it the text is not read as a
         * Splitwise file, so a line of millions of commas cannot fill the memory.
         */
        const val MAX_CELLS: Int = 10_000
    }
}

private fun Char.isBlankChar(): Boolean = this == ' ' || this == '\t'

private fun Char.isCellEnd(): Boolean = this == ',' || this == '\n' || this == '\r'

/** Spaces and tabs dropped at both ends (docs/splitwise-import.md 1.2: the total row's blank cells are a space). */
internal fun String.trimBlank(): String = trim { it.isBlankChar() }
