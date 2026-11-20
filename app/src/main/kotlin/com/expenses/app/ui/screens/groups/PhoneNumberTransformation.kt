package com.expenses.app.ui.screens.groups

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** "+91", the country code the design writes before a mobile number. */
private const val COUNTRY_CODE = "+91"

/** How many digits of a mobile number come before the space: "90000 00001". */
private const val FIRST_GROUP = 5

/**
 * A whole Indian mobile number as it may be typed or picked from contacts: ten digits starting with 6 to 9, with or
 * without "+91" before them, and with or without the spaces the design writes. Nothing else is in the field.
 */
private val IndianMobile = Regex("""^(\+91)?( ?)([6-9]\d{4})( ?)(\d{5})$""")

/**
 * Shows a whole Indian mobile number the way Add friend draws it (design 02-everyday/27): "+91 90000 00001". The text
 * in the field is not changed, so what is kept and sent stays exactly what was typed ("9000000001", "+919000000001").
 *
 * Only characters are added, never taken away or replaced: "+91 " before a number typed without it, and the two
 * spaces. So every typed character stays on screen, in its order, and the cursor can stand on either side of each.
 * Anything that is not a whole mobile number (still being typed, another country's number, a number with dashes) is
 * shown as it is; so the number takes this shape when its tenth digit is typed and loses it when one is deleted.
 *
 * Where the cursor goes:
 * - before the first typed character, it is drawn after the added "+91 ", where typing continues;
 * - anywhere else, it is drawn right after the typed character before it, so in front of an added space;
 * - a tap on or before an added character puts it before the typed character that follows.
 */
internal object IndianMobileTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val shown = groupIndianMobile(text.text) ?: return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(AnnotatedString(shown.text), shown)
    }
}

/**
 * A mobile number as shown, with where each typed character went.
 *
 * @param text the number as shown: "+91 90000 00001".
 * @param shownIndex for each typed character, its place in [text], rising.
 */
internal class GroupedMobile(val text: String, private val shownIndex: IntArray) : OffsetMapping {
    /** Characters added before the first typed one. */
    private val addedInFront: Int = shownIndex.firstOrNull() ?: text.length

    override fun originalToTransformed(offset: Int): Int = when {
        offset <= 0 -> addedInFront
        offset >= shownIndex.size -> text.length
        else -> shownIndex[offset - 1] + 1
    }

    override fun transformedToOriginal(offset: Int): Int = shownIndex.count { it < offset }
}

/**
 * [typed] as Add friend shows it when it is a whole Indian mobile number ([IndianMobile]), with the place of every
 * typed character; null for anything else, which is shown as typed.
 */
internal fun groupIndianMobile(typed: String): GroupedMobile? {
    val match = IndianMobile.matchEntire(typed) ?: return null
    val (code, codeSpace, first, middleSpace, last) = match.destructured
    // A space typed after the code needs the code; "90000 00001" may have its own middle space.
    if (code.isEmpty() && codeSpace.isNotEmpty()) return null
    val text = "$COUNTRY_CODE $first $last"
    val shownIndex = IntArray(typed.length)
    var typedAt = 0
    var shownAt = 0
    // Walk the shown text; a character of it that was typed takes the next typed place, the others were added.
    fun take(count: Int, wasTyped: Boolean) {
        repeat(count) {
            if (wasTyped) shownIndex[typedAt++] = shownAt
            shownAt++
        }
    }
    take(COUNTRY_CODE.length, wasTyped = code.isNotEmpty())
    take(1, wasTyped = codeSpace.isNotEmpty())
    take(FIRST_GROUP, wasTyped = true)
    take(1, wasTyped = middleSpace.isNotEmpty())
    take(last.length, wasTyped = true)
    return GroupedMobile(text, shownIndex)
}
