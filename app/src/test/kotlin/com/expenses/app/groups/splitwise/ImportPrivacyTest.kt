package com.expenses.app.groups.splitwise

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Splitwise file never reaches a log (docs/splitwise-import.md 6, CLAUDE.md): no code that reads it, plans the
 * import or shows it logs or prints anything. Checked in the sources, so a later change cannot add a log line by
 * mistake. (The sync engine and the repository, which hold the plan's rows, are checked in SplitwiseImportTest by
 * watching Android's log during an import.)
 */
class ImportPrivacyTest {
    private val folders = listOf(
        File("src/main/kotlin/com/expenses/app/groups/splitwise"),
        File("src/main/kotlin/com/expenses/app/ui/screens/splitwise"),
        File("../splitwise/src/main/kotlin"),
    )
    private val logging = Regex("""\b(Log\.[a-z]+\(|println\(|print\(|printStackTrace\(|Timber\.|System\.(out|err))""")

    @Test fun nothingThatReadsTheFileLogs() {
        val files = folders.flatMap { folder -> folder.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("no sources found from ${File(".").absolutePath}", files.size >= 10)
        val found = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line -> if (logging.containsMatchIn(line)) "${file.name}:${index + 1}" else null }
        }
        assertEquals(emptyList<String>(), found)
    }
}
