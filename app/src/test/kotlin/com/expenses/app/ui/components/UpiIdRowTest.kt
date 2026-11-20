package com.expenses.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.expenses.app.ui.format.PayeeWording
import com.expenses.app.ui.screens.home.HomeSamples
import com.expenses.app.ui.screens.home.HomeScreen
import com.expenses.app.ui.screens.payment.AddToExpensesScreen
import com.expenses.app.ui.screens.payment.AddToExpensesUiState
import com.expenses.app.ui.screens.payment.ExpenseAddedScreen
import com.expenses.app.ui.screens.payment.ExpenseAddedUiState
import com.expenses.app.ui.screens.payment.ExpenseDetailScreen
import com.expenses.app.ui.screens.payment.ExpenseDetailUiState
import com.expenses.app.ui.screens.payment.PaymentSamples
import com.expenses.app.ui.screens.review.ReviewPaymentsSamples
import com.expenses.app.ui.screens.review.SwipeCard
import com.expenses.app.ui.screens.review.SwipeStamp
import com.expenses.app.ui.screens.settle.PaymentMatchScreen
import com.expenses.app.ui.screens.settle.SettleSamples
import com.expenses.app.ui.screens.split.SameSplitScreen
import com.expenses.app.ui.screens.split.SameSplitUiState
import com.expenses.app.ui.screens.split.SplitAddedScreen
import com.expenses.app.ui.screens.split.SplitAddedUiState
import com.expenses.app.ui.screens.split.SplitSamples
import com.expenses.app.ui.screens.split.SplitScreen
import com.expenses.app.ui.screens.split.SplitUiState
import com.expenses.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A payee or account known only by its UPI ID is drawn with marks that let it break only after its "@"; screen readers
 * (and these lookups) still meet the plain ID, never the marks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h2000dp")
class UpiIdRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun aRowReadsThePlainUpiId() {
        compose.setContent {
            AppTheme(dark = false) {
                ExpenseRow(title = "rahul.kumar@okaxis", subtitle = "Money in · UPI", amount = "+₹300", meta = "Not spending")
            }
        }

        compose.onNodeWithText("rahul.kumar@okaxis", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun homesPaymentCardReadsThePlainHeadline() {
        compose.setContent { AppTheme(dark = false) { HomeScreen(state = HomeSamples.upiPayees, onEvent = {}) } }

        compose.onNodeWithText("₹312 to uberindia.rides@hdfcbank", useUnmergedTree = true).assertExists()
    }

    @Test fun theReviewCardReadsThePlainPayee() {
        val card = ReviewPaymentsSamples.reviewing.card.copy(payee = "uberindia.rides@hdfcbank")
        compose.setContent {
            AppTheme(dark = false) { SwipeCard(card = card, stamp = SwipeStamp.None, onChangeCategory = {}, onSplit = {}) }
        }

        compose.onNodeWithText("uberindia.rides@hdfcbank", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun expenseAddedReadsThePlainSentenceAndRow() {
        val state = (PaymentSamples.expenseAdded as ExpenseAddedUiState.Shown)
            .copy(merchant = "rahul.kumar@okaxis", payeeWording = PayeeWording.TO)
        compose.setContent { AppTheme(dark = false) { ExpenseAddedScreen(state = state, onEvent = {}) } }

        compose.onNodeWithText("₹450 to rahul.kumar@okaxis is in your expenses.").assertIsDisplayed()
        // The row under it is a FlatRow, which reads the plain ID too.
        compose.onNodeWithText("rahul.kumar@okaxis", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun addToExpensesReadsThePlainRule() {
        val state = (PaymentSamples.addToExpenses as AddToExpensesUiState.Form).copy(payee = "rahul.kumar@okaxis")
        compose.setContent { AppTheme(dark = false) { AddToExpensesScreen(state = state, onEvent = {}) } }

        compose.onNodeWithText("rahul.kumar@okaxis is always Food", useUnmergedTree = true).assertExists()
    }

    @Test fun expenseDetailAndSplitItReadThePlainPayee() {
        val detail = PaymentSamples.expenseDetail as ExpenseDetailUiState.Shown
        val split = SplitSamples.equally as SplitUiState.Form
        var showSplit by mutableStateOf(false)
        compose.setContent {
            AppTheme(dark = false) {
                if (showSplit) {
                    SplitScreen(state = split.copy(header = split.header.copy(title = "rahul.kumar@okaxis")), onEvent = {})
                } else {
                    ExpenseDetailScreen(state = detail.copy(details = detail.details.copy(merchant = "rahul.kumar@okaxis")), onEvent = {})
                }
            }
        }

        // "Paid to" on Expense detail and the payment row of Split it are FlatRows.
        compose.onNodeWithText("rahul.kumar@okaxis", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { showSplit = true }
        compose.onNodeWithText("rahul.kumar@okaxis", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun sameSplitAndSplitSortedReadThePlainSentences() {
        val offer = (SplitSamples.sameSplitBefore as SameSplitUiState.Shown).copy(payee = "rahul.kumar@okaxis")
        val added = (SplitSamples.added as SplitAddedUiState.Shown).copy(rememberedFor = "rahul.kumar@okaxis")
        var showAdded by mutableStateOf(false)
        compose.setContent {
            AppTheme(dark = false) {
                if (showAdded) SplitAddedScreen(state = added, onEvent = {}) else SameSplitScreen(state = offer, onEvent = {})
            }
        }

        compose.onNodeWithText("rahul.kumar@okaxis again. Same split?").assertExists()
        compose.onNodeWithText("You've split rahul.kumar@okaxis with Flat 302 before. Want it to split itself every month?").assertExists()
        compose.runOnIdle { showAdded = true }
        compose.onNodeWithText("Your next rahul.kumar@okaxis order can split the same way.", useUnmergedTree = true).assertExists()
    }

    @Test fun moneyReceivedReadsTheWholeCaption() {
        compose.setContent { AppTheme(dark = false) { PaymentMatchScreen(state = SettleSamples.paymentMatchUpiAccount, onEvent = {}) } }

        // The time is kept whole with no-break spaces.
        compose.onNodeWithText("arjun.mehta.payments.2026@okhdfcbank · 12 min ago", useUnmergedTree = true).assertExists()
    }
}
