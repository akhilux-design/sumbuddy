package com.expenses.app.ui.screens.activity

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.expenses.app.data.model.PaidWith
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A row of money that came in says "Money in" once, whether or not the SMS names who sent it. */
@RunWith(RobolectricTestRunner::class)
class ActivityItemRowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(title: String?) {
        val item = ActivityItem(
            id = ActivityItemId.Expense(1),
            title = title,
            icon = AppIcon.Cash,
            amountPaise = 120_000,
            detail = ActivityItemDetail.MoneyIn(PaidWithLabel.Method(PaidWith.UPI)),
        )
        compose.setContent { AppTheme(dark = false) { ActivityItemRow(item = item, onClick = {}) } }
    }

    private fun moneyInCount(): Int =
        compose.onAllNodes(hasText("Money in", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun moneyInThatNamesNobodySaysItOnce() {
        show(title = null)
        assertEquals(1, moneyInCount())
        compose.onNodeWithText("Money in", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("UPI", useUnmergedTree = true).assertExists()
    }

    @Test fun moneyInFromSomeoneNamesThemAndSaysMoneyInUnder() {
        show(title = "Rahul")
        assertEquals(1, moneyInCount())
        compose.onNodeWithText("Money in · UPI", useUnmergedTree = true).assertExists()
    }
}
