package com.expenses.app.ui.components

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Typing into a field whose owner answers late. Most screens' ViewModels build their state with `combine`, which can
 * hand a typed text back one main-thread message later; a frame drawn in between used to put the older text back in
 * the field, so the next key landed in the wrong place (Add expense, "What was it for?": "Filtcoffeeer").
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class AppInputTypingTest {
    @get:Rule
    val compose = createComposeRule()

    /** The text the field's owner holds, as a ViewModel would. */
    private var owned by mutableStateOf("")

    /** Every text the field told its owner. */
    private val told = mutableListOf<String>()

    private fun show(onValueChange: (String) -> Unit) {
        compose.setContent {
            AppTheme(dark = false) {
                AppInput(
                    value = owned,
                    onValueChange = {
                        told += it
                        onValueChange(it)
                    },
                    label = "What was it for?",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    private fun field() = compose.onNode(hasSetTextAction())

    private fun shows(text: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    @Test fun aKeyTypedBeforeTheOwnerAnswersLandsAfterTheEarlierOnes() {
        compose.mainClock.autoAdvance = false
        // The owner answers one main-thread message later.
        val mainThread = Handler(Looper.getMainLooper())
        show { text -> mainThread.post { owned = text } }

        field().performTextInput("Filt")
        // A frame is drawn before the main thread gets to the owner's answer.
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the owner has not answered yet", "", owned)
        field().performTextInput("er")

        assertEquals(listOf("Filt", "Filter"), told)
        compose.mainClock.autoAdvance = true
        field().assert(shows("Filter"))
        assertEquals("Filter", owned)
    }

    @Test fun aTextTheOwnerCutsShortShowsAsTheOwnerKeepsIt() {
        show { owned = it.take(4) }

        field().performTextInput("Filter")

        assertEquals(listOf("Filter"), told)
        field().assert(shows("Filt"))
    }

    @Test fun aTextTheOwnerSetsItselfShows() {
        show { owned = it }
        field().performTextInput("Tea")
        field().assert(shows("Tea"))

        compose.runOnIdle { owned = "Sunrise Stores" }

        field().assert(shows("Sunrise Stores"))
    }
}
