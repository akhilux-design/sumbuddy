package com.expenses.app

import com.expenses.app.preview.PreviewRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules that keep work done in parallel mergeable (docs/groups-build-plan.md section 1). Two areas that add the same
 * string name each build on their own, and the merged build then fails with "Duplicate resources"; two previews with
 * one name hide one of them.
 */
class BuildRulesTest {
    /** Every string, plural and array in `strings_<x>.xml` (or debug `strings_debug_<x>.xml`) starts with `<x>_`. */
    @Test fun everyStringNameStartsWithItsFilesPrefix() {
        val files = listOf(File("src/main/res/values"), File("src/debug/res/values"), File("src/release/res/values"))
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.name.startsWith("strings_") && it.name.endsWith(".xml") }
        assertTrue("no string files found from ${File(".").absolutePath}", files.size > 10)
        val name = Regex("""<(?:string|plurals|string-array)\s+name="([^"]+)"""")
        val wrong = files.flatMap { file ->
            val prefix = file.name.removePrefix("strings_").removePrefix("debug_").removeSuffix(".xml") + "_"
            name.findAll(file.readText()).map { it.groupValues[1] }.filterNot { it.startsWith(prefix) }.map { "${file.name}: $it" }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /** No two debug previews share a name. */
    @Test fun previewNamesAreUnique() {
        val names = PreviewRegistry.entries.map { it.name }
        assertEquals(emptyList<String>(), names.groupBy { it }.filterValues { it.size > 1 }.keys.toList())
    }
}
