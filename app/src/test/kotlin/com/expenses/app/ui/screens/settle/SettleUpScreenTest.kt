package com.expenses.app.ui.screens.settle

import android.app.Application
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Settle up offers with and without the friend's UPI ID (design 03-getting-paid-back/00 and 07). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettleUpScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<SettleUpEvent>()

    private fun show(state: SettleUpUiState) {
        compose.setContent {
            AppTheme(dark = false) {
                SettleUpScreen(state = state, onEvent = { events += it })
            }
        }
    }

    @Test fun withAUpiIdPayByUpiIsPickedAndPays() {
        show(SettleSamples.settleUp)

        compose.onNodeWithText("Pay by UPI").assertExists()
        // The tag shows capitals and reads the words as written.
        compose.onNodeWithContentDescription("4 expenses · Flat 302", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("It clears itself").assertExists()
        compose.onNodeWithText("Pay ₹1,200").performClick()
        // The body scrolls on a small test window: bring the card into view first.
        compose.onNodeWithText("Paid another way").performScrollTo().performClick()

        assertEquals(listOf(SettleUpEvent.Confirm, SettleUpEvent.Choose(SettleChoice.Other)), events)
    }

    @Test fun withoutAUpiIdOnlyPaidAnotherWayIsOffered() {
        show(SettleSamples.settleUpNoUpiId)

        compose.onNodeWithText("No UPI ID for Rahul").assertExists()
        compose.onNodeWithText("Ask Rahul how to pay. Then mark it as paid here.").assertExists()
        compose.onNodeWithText("Pay by UPI").assertDoesNotExist()
        compose.onNodeWithText("It clears itself").assertDoesNotExist()
        compose.onNodeWithText("Paid another way", useUnmergedTree = false).assertExists()
        compose.onNodeWithText("Mark ₹1,200 as paid").performClick()

        assertEquals(listOf(SettleUpEvent.Confirm), events)
    }

    @Test fun theOptionPickedIsSelectedNotOnlyColoured() {
        show(SettleSamples.settleUpOther)

        compose.onNodeWithText("Paid another way").assertIsSelected()
    }
}
