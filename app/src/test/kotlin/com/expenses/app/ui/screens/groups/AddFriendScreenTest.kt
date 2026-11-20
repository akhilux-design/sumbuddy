package com.expenses.app.ui.screens.groups

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Add friend's phone field (02-everyday/27): a whole Indian mobile number is shown "+91 90000 00001" while the number
 * kept, and later sent, stays exactly what was typed.
 */
@RunWith(RobolectricTestRunner::class)
class AddFriendScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(AddFriendUiState(name = "Kiran Rao"))

    private fun show() {
        compose.setContent {
            AppTheme(dark = false) {
                AddFriendScreen(
                    state = state,
                    onEvent = { event -> if (event is AddFriendEvent.PhoneChanged) state = state.copy(phone = event.phone) },
                )
            }
        }
    }

    /** The phone field: the second of the two fields. */
    private fun phone(): SemanticsNodeInteraction = compose.onAllNodes(hasSetTextAction())[1]

    private fun SemanticsNodeInteraction.shownText(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun SemanticsNodeInteraction.cursor(): TextRange? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)

    @Test fun aWholeNumberIsShownGroupedAndKeptAsTyped() {
        show()

        phone().performTextInput("900000000")
        // Nine digits: still as typed.
        assertEquals("900000000", phone().shownText())

        phone().performTextInput("1")
        assertEquals("+91 90000 00001", phone().shownText())
        assertEquals("9000000001", state.phone)
        // The cursor is after the last digit typed.
        assertEquals(TextRange(10), phone().cursor())
    }

    @Test fun deletingADigitShowsTheNumberAsTypedAgain() {
        state = state.copy(phone = "+919000000001")
        show()
        assertEquals("+91 90000 00001", phone().shownText())

        phone().performTextReplacement("+91900000000")
        assertEquals("+91900000000", phone().shownText())
        assertEquals("+91900000000", state.phone)
    }

    @Test fun aNumberPickedFromContactsWithItsSpacesReadsTheSame() {
        state = state.copy(phone = "+91 90000 00001")
        show()

        assertEquals("+91 90000 00001", phone().shownText())
        assertEquals("+91 90000 00001", state.phone)
    }
}
