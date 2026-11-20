package com.expenses.splitwise

import java.util.Locale

/**
 * Text rules for what comes out of a file. [clean] gives what the app's `GroupText.clean` gives (docs/server.md 4.6,
 * 4.7: no control or bidirectional override characters, trimmed, cut to a length), except that a line break or tab
 * inside a cell becomes a space instead of joining two words, and a cut never splits a surrogate pair. Running
 * `GroupText.clean` on the result changes nothing.
 */
object SplitwiseText {
    /** Longest group name. */
    const val MAX_GROUP_NAME: Int = 40

    /** Longest member name. */
    const val MAX_PERSON_NAME: Int = 60

    /** Longest expense description. */
    const val MAX_DESCRIPTION: Int = 80

    private val WHITESPACE_RUN = Regex("""\s+""")

    /** [text] cleaned and cut to [max] characters; empty when nothing is left. */
    fun clean(text: String?, max: Int): String {
        val spaced = text.orEmpty().replace(WHITESPACE_RUN, " ")
        val kept = spaced.filterNot { it.isISOControl() || it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 }
        var cut = kept.trim { isServerSpace(it) }.take(max)
        if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return cut.trim { isServerSpace(it) }
    }

    /**
     * What the server's `String.prototype.trim` drops at both ends: Kotlin's whitespace plus U+FEFF (a byte order mark
     * an editor left in a cell), which [String.trim] keeps. Otherwise a cell of only U+FEFF would pass as text here and
     * be refused there as blank.
     */
    private fun isServerSpace(c: Char): Boolean = c.isWhitespace() || c == '\uFEFF'

    /** Trimmed, lower-cased, spaces collapsed: how names are compared. */
    internal fun key(name: String): String = name.trim().replace(WHITESPACE_RUN, " ").lowercase(Locale.ROOT)
}

/**
 * The group name from the picked file's display name (docs/splitwise-import.md 1.5): `flat-302-export.csv` becomes
 * "Flat 302". The CSV holds no group name, so this is a starting point the person can change.
 */
object GroupNameFromFile {
    private val CSV = Regex("""\.csv$""", RegexOption.IGNORE_CASE)
    private val COPY = Regex("""\s*\(\d+\)$""")
    private val EXPORT = Regex("""[ _-]export$""", RegexOption.IGNORE_CASE)
    private val DATE = Regex("""[ _-]\d{4}-\d{2}-\d{2}$""")
    private val SEPARATORS = Regex("""[_\s-]+""")
    private val GENERIC = setOf("export", "splitwise", "expenses", "download", "file")

    /** The cleaned name, at most 40 characters, or "" when nothing usable is left (Import stays off until typed). */
    fun of(displayName: String?): String {
        var name = displayName.orEmpty().trim()
        // 1. The extension and a copy suffix ("flat-302-export (1).csv"), in either order.
        while (true) {
            val before = name
            name = name.replace(CSV, "").trimEnd().replace(COPY, "").trimEnd()
            if (name == before) break
        }
        // 2. "_export", then a date, then "_export" again.
        name = name.replace(EXPORT, "").replace(DATE, "").replace(EXPORT, "")
        // 3. Underscores and dashes become spaces.
        name = name.replace(SEPARATORS, " ").trim()
        // 4. All lower case: each word gets a capital ("flat 302" becomes "Flat 302").
        if (name.none { it.isUpperCase() }) {
            name = name.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
        }
        // 5 and 6.
        val cleaned = SplitwiseText.clean(name, SplitwiseText.MAX_GROUP_NAME)
        return if (cleaned.lowercase(Locale.ROOT) in GENERIC) "" else cleaned
    }
}

/** "Which one is you?" (docs/splitwise-import.md 1.6). */
object SplitwisePeople {
    /**
     * The person to preselect for the signed-in [profileName], or null to select nobody: the one person whose whole
     * name matches; otherwise the one person whose first word matches the profile's first word. Names are compared
     * trimmed, lower-cased, spaces collapsed. Two matches select nobody.
     */
    fun preselect(people: List<String>, profileName: String?): Int? {
        val me = SplitwiseText.key(profileName.orEmpty())
        if (me.isEmpty()) return null
        val keys = people.map { SplitwiseText.key(it) }
        val exact = keys.indices.filter { keys[it] == me }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        val first = me.substringBefore(' ')
        return keys.indices.filter { keys[it].substringBefore(' ') == first }.singleOrNull()
    }
}
