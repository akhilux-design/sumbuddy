package com.expenses.app.ui.format

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString

/** WORD JOINER: invisible, and no line may break on either side of it. */
private const val NO_BREAK = "⁠"

/** ZERO WIDTH SPACE: invisible, and a line may break after it. */
private const val MAY_BREAK = "​"

/**
 * True when [text] is a UPI ID, as a bank prints a payee it has no name for ("rahul.kumar@okaxis", "9000000002@ybl"):
 * one word with "@" inside. Android would break such a word wherever it likes ("rahul" / ".kumar@okaxis"), so the
 * app shows it through [upiIdLineBreaks].
 */
fun isUpiIdText(text: String): Boolean {
    val word = text.trim()
    val at = word.indexOf('@')
    return at > 0 && at < word.length - 1 && word.none(Char::isWhitespace)
}

/**
 * [text] as it is drawn, so that a UPI ID is always shown whole and breaks only after its "@": a word joiner between
 * its characters forbids a line break, and a zero width space after the "@" allows one. "rahul.kumar@okaxis" then
 * stays on one line where it fits and otherwise reads "rahul.kumar@" / "okaxis", so the bank handle that tells two
 * payees apart is never cut off. Only an ID too long for a whole line on its own still breaks where it must. Both
 * marks are invisible. Text that is not a UPI ID ([isUpiIdText]) is returned as it is.
 *
 * Give the [AppText][com.expenses.app.ui.components.core.AppText] that shows it [readAs] with the plain text, so
 * screen readers (and tests) never meet the marks.
 */
fun upiIdLineBreaks(text: String): String {
    if (!isUpiIdText(text)) return text
    val id = text.trim()
    return buildString(id.length * 2) {
        var i = 0
        while (i < id.length) {
            val next = i + Character.charCount(id.codePointAt(i))
            append(id, i, next)
            if (next < id.length) append(if (id[i] == '@') MAY_BREAK else NO_BREAK)
            i = next
        }
    }
}

/**
 * [text] without the marks [upiIdLineBreaks] puts in: what a screen reader says for a sentence that holds a UPI ID
 * drawn with them ("rahul.kumar@okaxis is always Food").
 */
fun withoutUpiIdLineBreaks(text: String): String = text.replace(NO_BREAK, "").replace(MAY_BREAK, "")

/**
 * Screen readers read [plain] for this text, not the string drawn: for text that went through [upiIdLineBreaks].
 *
 * @param plain the text without the line break marks.
 */
fun Modifier.readAs(plain: String): Modifier = clearAndSetSemantics { text = AnnotatedString(plain) }
