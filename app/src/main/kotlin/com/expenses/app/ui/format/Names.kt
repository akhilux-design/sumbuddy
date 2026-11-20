package com.expenses.app.ui.format

/** What the server names the place of a person who deleted their account: never shortened. */
private const val FORMER_MEMBER = "Former member"

private val Spaces = Regex("\\s+")

/** The first word of [name], or null for a blank name. */
private fun firstWordOf(name: String): String? = name.trim().split(Spaces).firstOrNull { it.isNotEmpty() }

/**
 * How a person is named in a sentence, a headline or a balance row. The design writes first names there ("You owe
 * Rahul", "Rahul and Priya each owe you", "All square with Rahul", "Rahul added you to Flat 302", the Balance Rows of a
 * group) and whole names only in lists of people (Group settings, Choose people, New group, Add friend, the title of
 * the friend's link sheet): "Rahul Menon" there, "Rahul" here.
 *
 * It is the first word of [name], or the whole name when someone else among [others] has the same first word (ignoring
 * case), so two Rahuls stay apart: "Rahul Kumar joined / Not Rahul Verma?". "Former member" stays whole.
 *
 * @param name the person's name as stored.
 * @param others the names of the people [name] is shown among: the group's members, or everyone in the list, whole
 * or first names only. The person themselves may be in it once (their whole name or their first name).
 */
fun shortName(name: String, others: Collection<String> = emptyList()): String {
    val whole = name.trim()
    if (whole == FORMER_MEMBER) return whole
    val first = firstWordOf(whole) ?: return whole
    var selfSkipped = false
    val clash = others.any { other ->
        val text = other.trim()
        if (!selfSkipped && (text.equals(whole, ignoreCase = true) || text.equals(first, ignoreCase = true))) {
            selfSkipped = true
            false
        } else {
            firstWordOf(text)?.equals(first, ignoreCase = true) == true
        }
    }
    return if (clash) whole else first
}
