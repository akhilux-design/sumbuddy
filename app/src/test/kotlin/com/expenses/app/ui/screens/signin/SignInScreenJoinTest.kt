package com.expenses.app.ui.screens.signin

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Sign in opened by a friend's link reads "Join a group" (02-everyday/49), and from the family screens "Share a family
 * month" (06-family/08); the rest is Sign in as always.
 */
@RunWith(RobolectricTestRunner::class)
class SignInScreenJoinTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<SignInEvent>()

    private fun show(state: SignInUiState) {
        compose.setContent { AppTheme(dark = false) { SignInScreen(state = state, onEvent = { events += it }) } }
    }

    @Test fun theJoinVariantSaysWhy() {
        show(SignInUiState(forJoin = true))

        compose.onNodeWithText("Sign in").assertExists()
        compose.onNodeWithText("Join a group").assertExists()
        compose.onNodeWithText("Sign in to join a group. Your SMS stay on this phone.").assertExists()
        compose.onNodeWithText("You were invited", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("After you sign in, you see the group and what you owe. Then you choose.", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Personal stays personal", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Split with friends").assertDoesNotExist()
        compose.onNodeWithText("Groups and balances", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithText("Continue with Google", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(listOf(SignInEvent.ContinueWithGoogle, SignInEvent.NotNow), events)
    }

    @Test fun theUsualVariantIsUnchanged() {
        show(SignInUiState())

        compose.onNodeWithText("Split with friends").assertExists()
        compose.onNodeWithText("Sign in to split with friends. Your SMS stay on this phone.").assertExists()
        compose.onNodeWithText("Groups and balances", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Join a group").assertDoesNotExist()
        compose.onNodeWithText("You were invited", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun theFamilyVariantSaysWhy() {
        show(SignInUiState(forFamily = true))

        compose.onNodeWithText("Share a family month").assertExists()
        compose.onNodeWithText("Sign in to share a family month. Your SMS stay on this phone.").assertExists()
        compose.onNodeWithText("Family month", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Kept in your account, so a new phone gets it back.", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Personal stays private", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Only expenses you mark Family are shared. Never your SMS.", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Split with friends").assertDoesNotExist()
        compose.onNodeWithText("Personal stays personal", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithText("Not now").performClick()
        assertEquals(listOf<SignInEvent>(SignInEvent.NotNow), events)
    }
}
