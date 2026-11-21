package com.expenses.app.ui.screens.you

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.expenses.app.R
import com.expenses.app.ui.nav.openSourceCode
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The top of the You tab, signed in (04-you/00) and signed out (06). */
@RunWith(RobolectricTestRunner::class)
class YouScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<YouEvent>()

    private fun show(state: YouUiState) {
        compose.setContent { AppTheme(dark = false) { YouScreen(state = state, onEvent = { events += it }) } }
    }

    @Test fun signedInTheNameOpensAccount() {
        show(YouSamples.default)
        compose.onNodeWithText("Signed in with Google", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Not signed in", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Sign in with Google").assertDoesNotExist()

        compose.onNode(hasText("Arjun") and hasClickAction()).assertHasClickAction().performClick()

        assertEquals(listOf<YouEvent>(YouEvent.OpenAccount), events)
    }

    @Test fun signedOutTheCardOpensSignIn() {
        show(YouSamples.signedOut)
        compose.onNodeWithText("Not signed in", useUnmergedTree = true).assertExists()
        // The name does nothing while signed out.
        compose.onNode(hasText("Arjun") and hasClickAction()).assertDoesNotExist()

        compose.onNodeWithText("Sign in with Google").performClick()

        assertEquals(listOf<YouEvent>(YouEvent.SignIn), events)
        compose.onNodeWithText("To split with friends. Your SMS stay on this phone.").assertExists()
    }

    @Test fun plusStaysOut() {
        show(YouSamples.default)
        val appName = ApplicationProvider.getApplicationContext<Context>().getString(R.string.app_name)
        compose.onNodeWithText("$appName Plus", substring = true).assertDoesNotExist()
    }

    /** 04-you/00, 06 and 09: See the code under PRIVACY, after What we've read. */
    @Test fun seeTheCodeIsUnderPrivacySignedInSignedOutAndInManualMode() {
        var shown by mutableStateOf(YouSamples.default)
        compose.setContent { AppTheme(dark = false) { YouScreen(state = shown, onEvent = { events += it }) } }
        for (state in listOf(YouSamples.default, YouSamples.signedOut, YouSamples.default.copy(smsTrackingOn = false))) {
            shown = state
            compose.onNodeWithText("The SMS reader is public").assertExists()
        }
        compose.onNodeWithText("See the code").performScrollTo().performClick()
        assertEquals(listOf<YouEvent>(YouEvent.OpenSourceCode), events)
    }

    /** The link opens in the browser: ACTION_VIEW of `source_code_url`, browsable apps only. */
    @Test fun seeTheCodeOpensTheRepositoryInTheBrowser() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        openSourceCode(context)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(context.getString(R.string.source_code_url), started.dataString)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test fun importFromSplitwiseUnderMoreOpensTheImport() {
        show(YouSamples.default)
        compose.onNodeWithText("MORE", ignoreCase = true).assertExists()
        compose.onNodeWithText("Bring groups and balances").assertExists()
        compose.onNodeWithText("Import from Splitwise").performScrollTo().performClick()
        assertEquals(listOf<YouEvent>(YouEvent.OpenImport), events)
    }

    @Test fun importFromSplitwiseShowsSignedOutToo() {
        show(YouSamples.signedOut)
        compose.onNodeWithText("Import from Splitwise").performScrollTo().performClick()
        assertEquals(listOf<YouEvent>(YouEvent.OpenImport), events)
    }
    @Test fun theFamilyRowSaysWhereTheFamilyStandsAndOpensIt() {
        val subtitles = mapOf(
            YouSamples.default to "Home · Arjun and Meera",
            YouSamples.familyWaiting to "Home · waiting for your partner",
            YouSamples.familyAlone to "Home · just you",
            YouSamples.familyNone to "Share a monthly total with your partner",
        )
        var shown by mutableStateOf(YouSamples.default)
        compose.setContent { AppTheme(dark = false) { YouScreen(state = shown, onEvent = { events += it }) } }
        compose.onNodeWithText("FAMILY", useUnmergedTree = true).assertExists()
        for ((state, subtitle) in subtitles) {
            shown = state
            compose.onNodeWithText(subtitle, useUnmergedTree = true).performScrollTo().assertExists()
        }
        compose.onNode(hasText("Family") and hasClickAction()).performScrollTo().performClick()
        assertEquals(listOf<YouEvent>(YouEvent.OpenFamily), events)
    }

    @Test fun signedOutTheFamilyRowInvitesToSetOneUp() {
        show(YouSamples.signedOut)
        compose.onNodeWithText("Share a monthly total with your partner", useUnmergedTree = true).performScrollTo().assertExists()
    }
}
