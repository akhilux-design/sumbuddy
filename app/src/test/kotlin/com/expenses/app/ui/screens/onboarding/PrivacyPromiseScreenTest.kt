package com.expenses.app.ui.screens.onboarding

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Privacy promise (design 01-onboarding/05) on a 360dp wide phone, the width of the design. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xxhdpi")
class PrivacyPromiseScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val headline = "Your messages never leave this phone"

    private fun show() {
        compose.setContent {
            AppTheme(dark = true) { PrivacyPromiseScreen(onAllow = {}, onTypeByHand = {}) }
        }
    }

    private fun headlineLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(headline).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    /**
     * Two lines, set in the 336dp the headline is given (its 328dp box and 8dp into the end padding). The line count
     * alone passes also without those 8dp, because Robolectric sets the text in a fallback font narrower than Bricolage
     * Grotesque; the width is what fails when the overhang goes. The real font was checked on the emulator (two lines).
     */
    @Test fun theHeadlineTakesTwoLines() {
        show()
        val layout = headlineLayout()
        assertEquals(with(compose.density) { 336.dp.roundToPx() }, layout.layoutInput.constraints.maxWidth)
        assertEquals(2, layout.lineCount)
        assertEquals("Your messages never ", headline.substring(0, layout.getLineEnd(0)))
    }

    @Test fun theHeadlineStartsWhereTheDesignDoes() {
        show()
        // 16dp from the screen's edge, like the rest of the body; only its end may run 8dp into the padding.
        val bounds = compose.onNodeWithText(headline).fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertEquals(16.dp.toPx(), bounds.left, 1f)
            assertEquals(336.dp.toPx(), bounds.width, 1f)
        }
    }

    /** The fourth promise (docs/card-bills.md D26): the code is public. It is not a link. */
    @Test fun theFourthPromiseSaysTheCodeIsPublic() {
        show()
        compose.onNodeWithText("Check it yourself").assertExists()
        compose.onNodeWithText("The code that reads SMS is public.").assertExists().assertHasNoClickAction()
    }
}
