package com.expenses.app.ui.screens.join

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The words of Join group (02-everyday/32, 50), made from the preview. */
@RunWith(RobolectricTestRunner::class)
class JoinGroupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<JoinGroupEvent>()

    private fun show(state: JoinGroupUiState) {
        compose.setContent { AppTheme(dark = false) { JoinGroupScreen(state = state, onEvent = { events += it }) } }
    }

    @Test fun theGroupAsDrawn() {
        show(JoinSamples.ready)

        compose.onNodeWithText("Rahul added you to Flat 302").assertExists()
        compose.onNodeWithText("3 people share expenses here. You can see what you owe before you join.").assertExists()
        compose.onNodeWithText("Flat 302", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Rahul, Priya and you", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("You owe ₹1,150 so far", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("5 expenses in November", useUnmergedTree = true).assertExists()

        compose.onNodeWithText("Join group").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(listOf(JoinGroupEvent.Join, JoinGroupEvent.Leave), events)
    }

    @Test fun owedInABiggerGroup() {
        show(JoinSamples.owed)

        compose.onNodeWithText("6 people share expenses here. You can see what you owe before you join.").assertExists()
        compose.onNodeWithText("Rahul, Priya, Sam, you and 2 more", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Flat 302 owes you ₹420 so far", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("1 expense in November", useUnmergedTree = true).assertExists()
    }

    @Test fun fourOtherMembersAreAllNamedWithTheSenderFirst() {
        show(JoinSamples.fourOthers)

        compose.onNodeWithText("Kiran added you to Goa trip").assertExists()
        // Not "Meera, Rahul, Dev, you and 1 more", which hid Kiran, the person in the headline.
        compose.onNodeWithText("Kiran, Meera, Rahul, Dev and you", useUnmergedTree = true).assertExists()
    }

    @Test fun whoIsNamedInTheGroupRow() {
        // As drawn (32): everyone, the sender first.
        assertEquals(NamedMembers(listOf("Rahul", "Priya"), more = 0), namedMembers(listOf("Rahul", "Priya"), "Rahul"))
        assertEquals(NamedMembers(listOf("Rahul", "Priya"), more = 0), namedMembers(listOf("Priya", "Rahul"), "Rahul Menon"))
        // Never "and 1 more": the fourth is named.
        assertEquals(NamedMembers(listOf("A", "B", "C", "D"), more = 0), namedMembers(listOf("A", "B", "C", "D"), "A"))
        // More than four: three names, and the sender is never among those left out.
        assertEquals(NamedMembers(listOf("E", "A", "B"), more = 2), namedMembers(listOf("A", "B", "C", "D", "E"), "E"))
        assertEquals(NamedMembers(listOf("A", "B", "C"), more = 3), namedMembers(listOf("A", "B", "C", "D", "E", "F"), "A"))
        // A sender who is not among the names (they left since) changes nothing; blank names are not counted.
        assertEquals(NamedMembers(listOf("A", "B"), more = 0), namedMembers(listOf("A", " ", "B"), "Zoya"))
        assertEquals(NamedMembers(emptyList(), more = 0), namedMembers(emptyList(), "Rahul"))
    }

    @Test fun theLinkNoLongerWorks() {
        show(JoinGroupUiState.LinkNoLongerWorks)

        compose.onNodeWithText("This link no longer works").assertExists()
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf<JoinGroupEvent>(JoinGroupEvent.Leave), events)
    }

    @Test fun couldNotLoadOffersTryAgain() {
        show(JoinSamples.couldNotLoad)

        compose.onNodeWithText("Check your connection and try again. Nothing is lost.", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf<JoinGroupEvent>(JoinGroupEvent.TryAgain), events)
    }
}
