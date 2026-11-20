package com.expenses.app.ui.screens.grouppage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the Group page writes for each case of docs/server.md 6.4 and the design's states (24, 42, 43), and which of
 * its rows can be tapped (docs/groups-build-plan.md 4.2 tasks 2 to 6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h2000dp")
class GroupPageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<GroupPageEvent>()

    private fun show(state: GroupPageUiState) {
        compose.setContent { AppTheme(dark = false) { GroupPageScreen(state = state, onEvent = { events += it }) } }
    }

    @Test fun owesYouAndAllSettled() {
        show(GroupPageSamples.page)

        compose.onNodeWithText("FLAT 302 OWES YOU", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("3 people · 12 expenses in Nov", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Rahul") and hasText("owes you") and hasText("+₹1,200")).assertHasClickAction()
        compose.onNode(hasText("Kiran") and hasText("all settled") and hasText("₹0")).assertHasNoClickAction()
        compose.onNodeWithText("Today · equally", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("1 Nov · splits itself", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Remind").assertExists()
    }

    @Test fun youOweAndSettlesWithOthers() {
        show(GroupPageSamples.youOwe)

        compose.onNodeWithText("YOU OWE GOA TRIP", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Kiran") and hasText("you owe") and hasText("-₹500")).assertHasClickAction()
        compose.onNode(hasText("Rahul") and hasText("settles with others") and hasText("₹0")).assertHasNoClickAction()
        compose.onNodeWithText("Kiran paid", useUnmergedTree = true).assertExists()
        // 42 has no Remind.
        compose.onNodeWithText("Remind").assertDoesNotExist()
    }

    @Test fun balanceRowsAndTheFooterOpenTheirScreens() {
        show(GroupPageSamples.youOwe)

        compose.onNode(hasText("Meera") and hasClickAction()).performClick()
        compose.onNodeWithText("Settle up").performClick()

        assertEquals(
            listOf(GroupPageEvent.Settle(SettleTarget.SettleUp("meera")), GroupPageEvent.Settle(SettleTarget.SettleUp("kiran"))),
            events,
        )
    }

    @Test fun theNotSavedRow() {
        show(GroupPageSamples.changeNotSaved)

        compose.onNodeWithText("Not saved", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Not added to Flat 302. The group changed before it was sent.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Dismiss").performClick()

        assertEquals(listOf(GroupPageEvent.RetryChange(1), GroupPageEvent.DismissChange(1)), events)
    }

    @Test fun tryAgainIsHiddenForARefusalThatWouldComeAgain() {
        show(GroupPageSamples.deleteNotSaved)

        compose.onNodeWithText("Dismiss").assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        // The row says why there is no Try again.
        compose.onNodeWithText("Not deleted. This cannot be tried again.").assertExists()
    }

    @Test fun aRowWithoutTryAgainSaysThatSomeoneLeft() {
        show(GroupPageSamples.changeNotSavedSomeoneLeft)

        compose.onNodeWithText("Not added to Flat 302. Someone in this expense has left the group. Add it again without them.").assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Dismiss").performClick()

        assertEquals(listOf<GroupPageEvent>(GroupPageEvent.DismissChange(1)), events)
    }

    /** What the note of a "Not saved" row reads for each kind of change and each reason, in Flat 302. */
    private val notes = mapOf(
        (OutboxOp.CREATE to null) to "Not added to Flat 302. The group changed before it was sent.",
        (OutboxOp.UPDATE to null) to "Your edit was not saved. The group changed before it was sent.",
        (OutboxOp.DELETE to null) to "Not deleted. The group changed before it was sent.",
        (OutboxOp.CREATE to NoRetryReason.SomeoneLeft) to
            "Not added to Flat 302. Someone in this expense has left the group. Add it again without them.",
        (OutboxOp.UPDATE to NoRetryReason.SomeoneLeft) to
            "Your edit was not saved. Someone in this expense has left the group. Edit it again without them.",
        (OutboxOp.CREATE to NoRetryReason.Other) to "Not added to Flat 302. This cannot be tried again.",
        (OutboxOp.UPDATE to NoRetryReason.Other) to "Your edit was not saved. This cannot be tried again.",
        (OutboxOp.DELETE to NoRetryReason.Other) to "Not deleted. This cannot be tried again.",
    )

    @Test fun everyNotSavedRowOffersTryAgainOrSaysWhyNot() {
        val sample = GroupPageSamples.changeNotSaved
        var shown by mutableStateOf(sample)
        compose.setContent { AppTheme(dark = false) { GroupPageScreen(state = shown, onEvent = { events += it }) } }

        notes.forEach { (kind, note) ->
            val (op, noRetry) = kind
            shown = sample.copy(notSaved = sample.notSaved.map { it.copy(op = op, noRetry = noRetry) })
            compose.waitForIdle()

            compose.onNodeWithText("Not saved", useUnmergedTree = true).assertExists()
            compose.onNodeWithText(note).assertExists()
            compose.onNodeWithText("Dismiss").assertExists()
            // Never a row with neither Try again nor a reason for its absence.
            if (noRetry == null) compose.onNodeWithText("Try again").assertExists() else compose.onNodeWithText("Try again").assertDoesNotExist()
        }
    }

    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    @Test fun theFooterButtonsHugTheirHeightAsDrawn() {
        show(GroupPageSamples.page)

        // 24: both Buttons hug their height, a 52 face and the 5 kept for its edge, and the footer keeps 8 under
        // them: 13 from each face to the bottom. Primary and Secondary are the same size, so the faces line up.
        val bottom = compose.onRoot().getUnclippedBoundsInRoot().bottom
        val settleUp = compose.onNodeWithText("Settle up").getUnclippedBoundsInRoot()
        val remind = compose.onNodeWithText("Remind").getUnclippedBoundsInRoot()
        (bottom - settleUp.bottom).assertIsEqualTo(13.dp, "under Settle up's face")
        (bottom - remind.bottom).assertIsEqualTo(13.dp, "under Remind's face")
        settleUp.top.assertIsEqualTo(remind.top, "the two tops")
        settleUp.height.assertIsEqualTo(52.dp, "Settle up's face")
        remind.height.assertIsEqualTo(52.dp, "Remind's face")
        settleUp.width.assertIsEqualTo(remind.width, "the two widths")
    }

    @Test fun theJoinBannerUndoes() {
        show(GroupPageSamples.someoneJoined)

        compose.onNodeWithText("Priya joined", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Not Priya?", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Undo").performClick()

        assertEquals(listOf(GroupPageEvent.UndoJoin("priya")), events)
    }

    @Test fun theGroupSettingsRowEndsTheBody() {
        show(GroupPageSamples.page)

        compose.onNodeWithText("Group settings", useUnmergedTree = true).performScrollTo()
        compose.onNode(hasText("Group settings") and hasText("People, name and leaving")).performClick()

        assertTrue(GroupPageEvent.OpenSettings in events)
    }

    @Test fun couldNotLoadTriesAgain() {
        show(GroupPageSamples.couldNotLoad)

        compose.onNodeWithText("Could not load this group", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(listOf(GroupPageEvent.Reload), events)
    }
}
