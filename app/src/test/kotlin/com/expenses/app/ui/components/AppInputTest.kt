package com.expenses.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A field's error message is never shown by colour alone: it scrolls into view when it appears, and screen readers
 * read it out (Your name 01-onboarding/03, Account 04-you/07).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class AppInputTest {
    @get:Rule
    val compose = createComposeRule()

    private var invalid by mutableStateOf(false)

    private fun show() {
        compose.setContent {
            AppTheme(dark = false) {
                Column(modifier = Modifier.size(width = 360.dp, height = 400.dp).verticalScroll(rememberScrollState())) {
                    Spacer(modifier = Modifier.height(360.dp))
                    AppInput(
                        value = "arjun",
                        onValueChange = {},
                        label = "UPI ID (optional)",
                        modifier = Modifier.fillMaxWidth(),
                        helper = "That does not look like a UPI ID.",
                        state = if (invalid) AppInputState.Error else AppInputState.Default,
                    )
                }
            }
        }
    }

    @Test fun theErrorScrollsIntoViewAndIsReadOut() {
        show()

        compose.runOnIdle { invalid = true }

        compose.onNodeWithText("That does not look like a UPI ID.", useUnmergedTree = true)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }
}
