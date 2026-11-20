package com.expenses.app.ui.screens.split

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.expenses.app.ui.theme.AppTheme
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The line under "Your share is in November" on Split. Sorted. (02-everyday/13). */
@RunWith(RobolectricTestRunner::class)
class SplitAddedShareTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(myShareMinor: Long, totalMinor: Long) {
        val state = SplitAddedUiState.Shown(
            sentence = AddedSentence.WithFriend("Rahul"),
            balances = emptyList(),
            myShareMinor = myShareMinor,
            totalMinor = totalMinor,
            month = YearMonth.of(2026, 11),
            rememberedFor = null,
            paymentId = null,
            busy = false,
        )
        compose.setContent { AppTheme(dark = false) { SplitAddedScreen(state = state, onEvent = {}) } }
    }

    @Test fun aPartOfTheAmountReadsAsDrawn() {
        show(myShareMinor = 15_000, totalMinor = 45_000)

        compose.onNodeWithText("Only ₹150 counts in your month, not the full ₹450.", useUnmergedTree = true).assertExists()
    }

    @Test fun theWholeAmountSaysAllOfIt() {
        show(myShareMinor = 45_000, totalMinor = 45_000)

        compose.onNodeWithText("All of the ₹450 counts in your month.", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Only ₹450 counts in your month, not the full ₹450.", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun nothingSaysNoneOfIt() {
        show(myShareMinor = 0, totalMinor = 45_000)

        compose.onNodeWithText("None of the ₹450 counts in your month.", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Only ₹0 counts in your month, not the full ₹450.", useUnmergedTree = true).assertDoesNotExist()
    }
}
