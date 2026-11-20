package com.expenses.app.ui.screens.you

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.screens.onboarding.ConfirmAccountsEvent
import com.expenses.app.ui.screens.onboarding.ConfirmAccountsScreen
import com.expenses.app.ui.screens.onboarding.OnboardingSamples
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Renaming an account, as its two frames draw it: the dialog on Confirm accounts (01-onboarding/15) and the row that
 * turns into a field on Accounts and cards (04-you/10). Neither has a line under the field.
 */
@RunWith(RobolectricTestRunner::class)
class RenameAccountTest {
    @get:Rule
    val compose = createComposeRule()

    private val helper = "Leave it empty to use the bank's name."

    @Test fun theRowBecomesAFieldWithSaveAndCancel() {
        val events = mutableListOf<AccountsEvent>()
        compose.setContent { AppTheme(dark = false) { AccountsScreen(state = YouSamples.accountsRenaming, onEvent = { events += it }) } }

        compose.onNodeWithText("Name for Bank account ••4521", useUnmergedTree = true).assertExists()
        // Nothing typed: the bank's name is the field's hint, which is how the screen says what an empty name does.
        compose.onNodeWithText("HDFC Bank", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(helper, useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf(AccountsEvent.SaveRename, AccountsEvent.CancelRename), events)
    }

    @Test fun focusingTheFieldBringsSaveAndCancelIntoViewToo() {
        // A screen this short is what is left above the keyboard. SBI, the last account, is renamed: its card starts
        // below the bottom edge.
        val state = YouSamples.accounts.copy(renaming = RenameDraft(accountId = 5, text = ""))
        compose.setContent {
            AppTheme(dark = false) { Box(Modifier.height(340.dp)) { AccountsScreen(state = state) } }
        }
        compose.onNodeWithText("Save").assertIsNotDisplayed()

        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.RequestFocus)

        compose.onNode(hasSetTextAction()).assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun theDialogOnConfirmAccountsHasTheNameAFieldSaveAndCancel() {
        val events = mutableListOf<ConfirmAccountsEvent>()
        compose.setContent {
            AppTheme(dark = false) {
                ConfirmAccountsScreen(state = OnboardingSamples.renameAccount, onEvent = { events += it }, onBack = {})
            }
        }

        compose.onNodeWithText("HDFC Bank ••4521").assertExists()
        compose.onNodeWithText("New name for HDFC Bank", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Salary account", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(helper, useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf(ConfirmAccountsEvent.SaveRename, ConfirmAccountsEvent.CancelRename), events)
    }
}
