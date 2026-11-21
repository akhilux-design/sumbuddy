package com.expenses.app.ui.screens.groups

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the Groups tab offers while its groups are not known: "No groups yet" with New group (02-everyday/21), the
 * placeholders (22) and Could not load (the card of 23), made from the previews.
 */
@RunWith(RobolectricTestRunner::class)
class GroupsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<GroupsEvent>()

    private fun show(state: GroupsUiState) {
        compose.setContent { AppTheme(dark = false) { GroupsScreen(state = state, onEvent = { events += it }) } }
    }

    @Test fun couldNotLoadOffersTryAgainAndNoNewGroup() {
        show(GroupsSamples.couldNotLoad)

        compose.onNodeWithText("Groups").assertExists()
        compose.onNodeWithText("Could not load your groups", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Check your connection and try again. Nothing is lost.", useUnmergedTree = true).assertExists()
        // The person may have groups already: nothing here makes a new one, and nothing says there are none.
        compose.onNodeWithText("+ New").assertDoesNotExist()
        compose.onNodeWithText("New group").assertDoesNotExist()
        compose.onNodeWithText("No groups yet", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf<GroupsEvent>(GroupsEvent.TryAgainClicked), events)
    }

    @Test fun loadingShowsPlaceholdersAndNeverNoGroupsYet() {
        show(GroupsSamples.loading)

        compose.onNodeWithContentDescription("Loading groups").assertExists()
        compose.onNodeWithText("No groups yet", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Could not load your groups", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theOverallCardCountsTheNamedGroups() {
        show(GroupsSamples.default)

        compose.onNodeWithText("across 3 groups", useUnmergedTree = true).assertExists()
    }

    /** Only direct splits, no named group: the card counts the friends, never "across 0 groups". */
    @Test fun withOnlyFriendsTheOverallCardCountsTheFriends() {
        val friends = (GroupsSamples.default as GroupsUiState.Loaded).friends
        show(GroupsUiState.Loaded(groups = emptyList(), friends = friends, overallPaise = 300_000))

        compose.onNodeWithText("across 2 friends", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("across 0 groups", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun noGroupsYetOffersNewGroup() {
        show(GroupsSamples.empty)

        compose.onNodeWithText("No groups yet", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Could not load your groups", useUnmergedTree = true).assertDoesNotExist()
        // Splitwise import is not built: nothing here leads to it (docs/product.md, Version 1 scope).
        compose.onNodeWithText("Coming from Splitwise?", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Import a group from its export file.", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithText("New group").performClick()
        assertEquals(listOf<GroupsEvent>(GroupsEvent.NewGroupClicked), events)
    }
}
