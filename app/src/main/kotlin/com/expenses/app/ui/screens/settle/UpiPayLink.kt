package com.expenses.app.ui.screens.settle

/**
 * The `upi://pay` link Pay by UPI opens, built the same way as the friends' web page builds it (docs/server.md 7):
 * `upi://pay?pa={upiId}&pn={toName}&am={rupees with 2 decimals}&cu=INR&tn={groupName}`. Values are percent-encoded,
 * except the `@` in `pa`, which is legal in a query and which some UPI apps do not decode from `%40`. `tn` is left out
 * for a direct split, which has no group name. Characters are kept or encoded exactly as the page's
 * `encodeURIComponent` does, so the app and the page open the same link. It is handed to the UPI app only, never
 * logged.
 */
internal object UpiPayLink {
    /** What `encodeURIComponent` leaves as it is. */
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
    private const val PAISE_PER_RUPEE = 100
    private const val HEX = "0123456789ABCDEF"

    /**
     * The link that pays [upiId] (named [payeeName]) [amountMinor] paise, with [note] (the group's name) as the
     * transaction note, or no note when it is null.
     */
    fun of(upiId: String, payeeName: String, amountMinor: Long, note: String?): String = buildString {
        append("upi://pay?pa=").append(encode(upiId.trim(), keep = "@"))
        append("&pn=").append(encode(payeeName))
        append("&am=").append(rupees(amountMinor))
        append("&cu=INR")
        if (!note.isNullOrBlank()) append("&tn=").append(encode(note))
    }

    /** Paise as rupees with two decimals: 120000 is "1200.00", 5 is "0.05". */
    fun rupees(amountMinor: Long): String =
        "${amountMinor / PAISE_PER_RUPEE}.${(amountMinor % PAISE_PER_RUPEE).toString().padStart(2, '0')}"

    /** [text] as UTF-8 with every byte outside [UNRESERVED] (and [keep]) written as %XX. */
    private fun encode(text: String, keep: String = ""): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (byte >= 0 && (c in UNRESERVED || c in keep)) {
                append(c)
            } else {
                val value = byte.toInt() and 0xFF
                append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
            }
        }
    }
}
