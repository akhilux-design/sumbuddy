package com.expenses.splitwise

import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/** Helpers for the tests. Every file here is synthetic: made-up people and amounts. */
internal object TestFiles {
    fun resource(name: String): ByteArray =
        requireNotNull(TestFiles::class.java.getResourceAsStream("/$name")) { "missing fixture $name" }.use { it.readBytes() }

    /** [lines] joined with LF, as UTF-8. */
    fun bytes(vararg lines: String): ByteArray = lines.joinToString("\n").toByteArray(Charsets.UTF_8)

    fun parse(vararg lines: String): SplitwiseResult = SplitwiseCsv.parse(bytes(*lines))

    fun ok(result: SplitwiseResult): SplitwiseFile =
        when (result) {
            is SplitwiseResult.Ok -> result.file
            is SplitwiseResult.Refused -> {
                fail("refused: ${result.reason}")
                throw AssertionError()
            }
        }

    fun ok(vararg lines: String): SplitwiseFile = ok(parse(*lines))

    fun assertRefused(reason: Refusal, result: SplitwiseResult) {
        assertEquals(SplitwiseResult.Refused(reason), result)
    }

    fun assertRefused(reason: Refusal, vararg lines: String) = assertRefused(reason, parse(*lines))

    const val HEADER2 = "Date,Description,Category,Cost,Currency,Asha,Ravi"
    const val HEADER3 = "Date,Description,Category,Cost,Currency,Asha,Ravi,Neel"

    /** Paise as a money cell: 12345 is "123.45", -5 is "-0.05". */
    fun money(paise: Long): String {
        val sign = if (paise < 0) "-" else ""
        val abs = kotlin.math.abs(paise)
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }
}
