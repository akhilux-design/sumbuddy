package com.expenses.app.groups.data

/**
 * Text rules shared with the server's validation (docs/server.md 4.6, 4.7), so the phone never sends what would be
 * refused: trimmed, no control characters, no bidirectional override characters (which can make a name read as
 * something else on the public page), and within the length limits.
 */
object GroupText {
    /** Longest group name. */
    const val MAX_GROUP_NAME: Int = 40

    /** Longest member or user name. */
    const val MAX_PERSON_NAME: Int = 60

    /** Longest expense description. */
    const val MAX_DESCRIPTION: Int = 80

    private val PHONE = Regex("^\\+[1-9][0-9]{7,14}$")
    private val LONG_DIGITS = Regex("[0-9]{6,}")

    /** [text] cleaned and cut to [max] characters; empty when nothing is left. */
    fun clean(text: String?, max: Int): String {
        val kept = text.orEmpty().filterNot { it.isISOControl() || it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 }
        return kept.trim { isServerSpace(it) }.take(max).trim { isServerSpace(it) }
    }

    /**
     * What the server's `String.prototype.trim` drops at both ends: Kotlin's whitespace plus U+FEFF (a byte order
     * mark), which [String.trim] keeps. Otherwise text of only U+FEFF would be sent and refused as blank.
     */
    private fun isServerSpace(c: Char): Boolean = c.isWhitespace() || c == '\uFEFF'

    /**
     * A phone number as E.164 ("+919876543210"), or null when [input] is blank or does not look like one. Spaces,
     * dashes and brackets are ignored; a 10-digit Indian mobile number gets +91.
     */
    fun normalizePhone(input: String?): String? {
        val digits = input.orEmpty().filter { it.isDigit() || it == '+' }
        if (digits.isEmpty()) return null
        val candidate = when {
            digits.startsWith("+") -> digits
            digits.length == 10 && digits.first() in '6'..'9' -> "+91$digits"
            digits.length == 11 && digits.startsWith("0") -> "+91" + digits.drop(1)
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            else -> "+$digits"
        }
        return candidate.takeIf { PHONE.matches(it) }
    }

    /** True when [input] is blank (no phone) or a phone number [normalizePhone] accepts. */
    fun isPhoneOk(input: String?): Boolean = input.isNullOrBlank() || normalizePhone(input) != null

    /** Longest UPI ID: 64 characters before the `@`, one `@`, 64 after. */
    const val MAX_UPI_ID: Int = 129

    private val UPI_ID = Regex("^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,63}$")

    /**
     * The UPI ID rule of docs/server.md 4.5, exactly as the server and the friends' page apply it: spaces at both ends
     * dropped ([String.trim]), case kept as typed. Blank means no UPI ID ([UpiIdInput.None]: send `"upiId": null`,
     * never `""`). Anything else must match `^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,63}$`, for example
     * `name@okaxis` or `98xxxxxx10@ybl`.
     */
    fun checkUpiId(input: String?): UpiIdInput {
        val trimmed = input.orEmpty().trim()
        return when {
            trimmed.isEmpty() -> UpiIdInput.None
            UPI_ID.matches(trimmed) -> UpiIdInput.Valid(trimmed)
            else -> UpiIdInput.Invalid
        }
    }

    /** True when [upiId] is a UPI ID friends can be sent to pay ("Pay by UPI" shows only then). */
    fun isPayableUpiId(upiId: String?): Boolean = upiId != null && UPI_ID.matches(upiId)

    /**
     * What a split of a payment to [counterparty] is called in the group (4.7): the counterparty, except when it is a
     * UPI ID or holds 6 or more digits in a row (a phone or account number, common on person-to-person UPI SMS); then
     * [categoryName].
     */
    fun descriptionFor(counterparty: String?, categoryName: String): String {
        val name = clean(counterparty, MAX_DESCRIPTION)
        val private = name.isEmpty() || name.contains('@') || LONG_DIGITS.containsMatchIn(name)
        return if (private) clean(categoryName, MAX_DESCRIPTION) else name
    }
}

/** What was typed in a UPI ID field, by [GroupText.checkUpiId]. */
sealed interface UpiIdInput {
    /** Blank: the person has no UPI ID. Sent as `null`. */
    data object None : UpiIdInput

    /** A UPI ID the server accepts: [value] is trimmed, case as typed. */
    data class Valid(val value: String) : UpiIdInput

    /** Not a UPI ID: "That does not look like a UPI ID." (04-you/07); nothing is saved until it is fixed. */
    data object Invalid : UpiIdInput
}
