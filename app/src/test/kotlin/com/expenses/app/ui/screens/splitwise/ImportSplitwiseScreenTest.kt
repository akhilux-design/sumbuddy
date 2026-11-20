package com.expenses.app.ui.screens.splitwise

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.expenses.app.ui.theme.AppTheme
import com.expenses.splitwise.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Import from Splitwise and Group imported as drawn (design 02-everyday/28 to 31, 133 to 141). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h2400dp")
class ImportSplitwiseScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<Any>()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { AppTheme(dark = true) { content() } }
    }

    private fun show(state: ImportUiState) = show { ImportSplitwiseScreen(state = state, onEvent = { events += it }) }

    private fun exists(vararg texts: String) = texts.forEach { compose.onNodeWithText(it).assertExists() }

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    /** Every text on screen is at least 14sp (CLAUDE.md). */
    private fun assertNoTextUnder14sp() {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        for (node in nodes) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.forEach { assertTrue("${it.layoutInput.text} at ${it.layoutInput.style.fontSize}", it.layoutInput.style.fontSize.value >= 14f) }
        }
    }

    @Test fun noFile28() {
        show(ImportSplitwiseSamples.noFile)
        exists(
            "Import from Splitwise", "Bring a group from Splitwise",
            "In Splitwise, open the group and choose Export as spreadsheet. Then pick that file here.",
            "No file chosen yet", "Pick the .csv file you exported from Splitwise.",
            "What comes across", "Expenses, who paid, and what each person owes.",
            "One file, one group", "Export each group separately in Splitwise.",
            "Names only", "The file has no phone numbers. Invite friends with a link after.",
        )
        compose.onNodeWithText("Choose file").assertIsEnabled().performClick()
        assertEquals(listOf<Any>(ImportEvent.ChooseFile), events)
        assertNoTextUnder14sp()
    }

    @Test fun reading135() {
        show(ImportSplitwiseSamples.reading)
        exists("Reading the file", "flat-302-export.csv")
        compose.onNodeWithText("Choose file").assertIsNotEnabled()
    }

    @Test fun chosen29WithItsRadioGroup() {
        show(ImportSplitwiseSamples.chosen)
        exists("flat-302-export.csv", "46 expenses · 3 people", "Group name", "Flat 302", "Is owed ₹2,350", "Owes ₹1,150", "Owes ₹1,200")
        compose.onNodeWithText("WHICH ONE IS YOU?", ignoreCase = true).assertExists()
        compose.onNode(radio and hasText("Arjun Mehta")).assertIsSelected()
        compose.onNode(radio and hasText("Priya Nair")).assertIsNotSelected()
        compose.onNode(radio and hasText("Kiran Rao")).assertIsNotSelected().performClick()
        assertEquals(listOf<Any>(ImportEvent.PersonSelected(2)), events)
        compose.onNodeWithText("Import Flat 302").assertIsEnabled()
        compose.onNodeWithText("Choose a different file").assertIsEnabled()
        compose.onAllNodesWithText("expenses in other currencies", substring = true).assertCountEquals(0)
        assertNoTextUnder14sp()
    }

    @Test fun whichOne133HasImportOff() {
        show(ImportSplitwiseSamples.whichOne)
        compose.onNode(radio and hasText("Arjun Mehta")).assertIsNotSelected()
        compose.onNodeWithText("Import Flat 302").assertIsNotEnabled().performClick()
        assertTrue(events.isEmpty())
    }

    @Test fun otherCurrencies134() {
        show(ImportSplitwiseSamples.otherCurrencies)
        exists("3 expenses in other currencies stay out. Only rupees come across.")
    }

    @Test fun aBlankNameSaysSoAndImportIsOff() {
        show(ImportSplitwiseSamples.nameBlank)
        exists("That does not look right.")
        compose.onNodeWithText("Import group").assertIsNotEnabled()
    }

    @Test fun wrongFile30() {
        show(ImportSplitwiseSamples.wrongFile)
        exists("That is not a Splitwise export", "expenses-2026.pdf cannot be read. Pick the .csv file that Splitwise made.", "What comes across")
        compose.onNodeWithText("Choose another file").assertIsEnabled()
    }

    @Test fun everyRefusalHasItsTitleAndBody136() {
        val texts = mapOf(
            Refusal.NOT_SPLITWISE to ("That is not a Splitwise export" to "f.csv cannot be read. Pick the .csv file that Splitwise made."),
            Refusal.DOES_NOT_ADD_UP to ("This file does not add up" to "f.csv was changed after Splitwise made it. Export the group again and pick the new file."),
            Refusal.TOO_LARGE_FILE to ("This group is too big" to "f.csv is over 5 MB. Only smaller groups can come across."),
            Refusal.TOO_MANY_PEOPLE to ("This group is too big" to "f.csv has more than 50 people. Only groups of up to 50 people can come across."),
            Refusal.TOO_MANY_ROWS to ("This group is too big" to "f.csv has more than 5,000 expenses. Only groups with up to 5,000 can come across."),
            Refusal.TOO_LARGE_AMOUNT to ("An expense is too big" to "f.csv has an expense over ₹1 crore, the most one expense can be here."),
            Refusal.NOTHING_IN_RUPEES to ("Nothing to bring across" to "f.csv has no expenses in rupees. Only rupees can come across."),
            Refusal.ONE_PERSON to ("Nobody else is in this file" to "f.csv has only one person. Pick the export of a group you share."),
        )
        assertEquals(Refusal.entries.toSet(), texts.keys)
        val res = RuntimeEnvironment.getApplication().resources
        for ((reason, pair) in texts) {
            val (title, body) = refusalTexts(reason)
            assertEquals(pair.first, res.getString(title))
            assertEquals(pair.second, res.getString(body, "f.csv"))
        }
    }

    @Test fun alreadyHere137() {
        show(ImportSplitwiseSamples.alreadyHere)
        exists("46 expenses · 3 people", "This file already came across as Flat 302 on 18 Nov.")
        compose.onNodeWithText("Open Flat 302").performClick()
        compose.onNodeWithText("Choose a different file").assertIsEnabled()
        assertEquals(listOf<Any>(ImportEvent.OpenGroup), events)
    }

    @Test fun importing138And139() {
        show(ImportSplitwiseSamples.importingOffline)
        exists(
            "You're offline", "Check your connection and try again. Nothing is lost.", "Bringing Flat 302 across",
            "Balances and past expenses are being saved. You can leave this screen. It finishes by itself.", "20 of 46 sent",
        )
        compose.onNodeWithText("Import from Splitwise").assertDoesNotExist()
        compose.onNodeWithText("Open Flat 302").performClick()
        assertEquals(listOf<Any>(ImportEvent.OpenGroup), events)
        assertNoTextUnder14sp()
    }

    @Test fun importingWithAProblem() {
        show(ImportSplitwiseSamples.importingProblem)
        exists("Could not do that now", "Try again in a moment.")
    }

    @Test fun failed140() {
        show(ImportSplitwiseSamples.failed)
        exists(
            "Flat 302 did not come across",
            "Part of the file could not be saved, so none of it was kept. Export the group from Splitwise again and try once more.",
        )
        compose.onNodeWithText("Choose another file").performClick()
        compose.onNodeWithText("Close").performClick()
        assertEquals(listOf<Any>(ImportEvent.ChooseFile, ImportEvent.Close), events)
    }

    @Test fun groupImported31And141() {
        show { GroupImportedScreen(state = ImportSplitwiseSamples.importedOffline, onEvent = { events += it }) }
        exists(
            "You're offline", "Flat 302 is in", "Balances and 46 past expenses came across.", "Priya and Kiran",
            "One message with a link for each person.", "Friends can join or just pay", "Nobody needs the app to see what they owe.",
        )
        compose.onNodeWithText("INVITE LINKS", ignoreCase = true).assertExists()
        compose.onNodeWithText("Share invite links").performClick()
        compose.onNodeWithText("Later").performClick()
        assertEquals(listOf<Any>(GroupImportedEvent.Share, GroupImportedEvent.Later), events)
        assertNoTextUnder14sp()
    }

    @Test fun groupImportedForOneFriendAndForMany() {
        show { GroupImportedScreen(state = ImportSplitwiseSamples.importedOneFriend, onEvent = {}) }
        exists("Priya", "One message with the link for Priya.", "Share invite link")
        compose.onNodeWithText("INVITE LINK", ignoreCase = true).assertExists()
    }

    @Test fun groupImportedNamesFourOrMoreAsMore() {
        show { GroupImportedScreen(state = ImportSplitwiseSamples.importedMany, onEvent = {}) }
        exists("Priya, Kiran and 3 more")
    }

    @Test fun sharingTurnsShareOff() {
        show { GroupImportedScreen(state = ImportSplitwiseSamples.imported.copy(sharing = true), onEvent = {}) }
        compose.onNodeWithText("Share invite links").assertIsNotEnabled()
    }
}
