package com.expenses.app.ui.screens.grouppage

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.expenses.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Group settings (02-everyday/25) with the friend's link sheet (46) open: screen readers reach only the sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h2000dp")
class GroupSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: GroupSettingsUiState) {
        compose.setContent { AppTheme(dark = false) { GroupSettingsScreen(state = state, onEvent = {}) } }
    }

    @Test fun theRowsBehindTheSheetAreHiddenFromScreenReaders() {
        show(GroupPageSamples.settingsFriendsLink)

        compose.onNodeWithText("Turn off link").assertIsDisplayed()
        compose.onNodeWithText("Leave group").assertDoesNotExist()
        compose.onNodeWithText("Add someone").assertDoesNotExist()
    }

    @Test fun aFriendWithoutALinkFromThisPhoneGetsTheSameSheetWithRemindAndTurnOff() {
        show(GroupPageSamples.settingsFriendNoLink)

        // Only what this phone knows: another member may have sent Priya a link.
        compose.onNodeWithText("No link from this phone").assertIsDisplayed()
        compose.onNodeWithText("If Priya got a link from another phone, Turn off link stops it.").assertIsDisplayed()
        compose.onNodeWithText("Remind Priya").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Turn off link").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun remindIsOffWhileTheFriendOwesNothingAndTheNoteSaysWhy() {
        show(GroupPageSamples.settingsFriendNoLinkOwesNothing)

        compose.onNodeWithText("Remind Priya").assertIsNotEnabled()
        compose.onNodeWithText("Remind can send Priya a link once they owe you.").assertIsDisplayed()
        compose.onNodeWithText("Turn off link").assertIsEnabled()
    }

    @Test fun nobodyElseJoinedTheOwnerMayDeleteAndTheRowSaysWhy() {
        show(GroupPageSamples.settingsNobodyJoined)

        compose.onNodeWithText("Removes it for everyone. Nobody else has joined it yet.").assertExists()
        compose.onNodeWithText("Removes it for everyone. Balances must be settled first.").assertDoesNotExist()
        compose.onNodeWithText("Delete group").assertHasClickAction()
    }

    @Test fun withoutTheSheetTheRowsAreThere() {
        show(GroupPageSamples.settings)

        compose.onNodeWithText("Leave group").assertExists()
        compose.onNodeWithText("Add someone").assertExists()
    }
}
