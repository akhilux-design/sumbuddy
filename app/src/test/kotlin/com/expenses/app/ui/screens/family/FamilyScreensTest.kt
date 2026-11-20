package com.expenses.app.ui.screens.family

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.screens.home.HomeEvent
import com.expenses.app.ui.screens.home.HomeSamples
import com.expenses.app.ui.screens.home.HomeScreen
import com.expenses.app.ui.screens.home.HomeUiState
import com.expenses.app.ui.screens.join.JoinGroupEvent
import com.expenses.app.ui.screens.join.JoinGroupScreen
import com.expenses.app.ui.screens.join.JoinGroupUiState
import com.expenses.app.ui.screens.join.JoinSamples
import com.expenses.app.ui.screens.signin.SignInScreen
import com.expenses.app.ui.screens.signin.SignInUiState
import com.expenses.app.ui.shell.MainScaffold
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The family screens as drawn (design/spec/screens/06-family; docs/family.md 9): Set up Family, Join Family, Family
 * settings, Home's family sections, Month view (Family) and the family Sign in. Each frame is checked in both themes:
 * its words, what its controls do, every text at least 14sp (font scale 1.0), every control at least 48dp, and states
 * that carry words (Tags, banners), never colour alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h2400dp")
class FamilyScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private var dark by mutableStateOf(false)
    private val events = mutableListOf<Any>()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { AppTheme(dark = dark) { content() } }
    }

    /** Each of [texts] is on screen (a Stat Tile's text is there twice: [com.expenses.app.ui.components.StatTilePair] measures it first). */
    private fun exists(vararg texts: String) = texts.forEach {
        assertTrue("\"$it\" is not shown", compose.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    /** Something screen readers call [description] is on screen. */
    private fun described(vararg descriptions: String) = descriptions.forEach {
        assertTrue("\"$it\" is not shown", compose.onAllNodesWithContentDescription(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    /** Nothing screen readers call [description] is on screen. */
    private fun notDescribed(description: String) =
        compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).assertCountEquals(0)

    private fun absent(vararg texts: String) = texts.forEach {
        compose.onAllNodesWithText(it, substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    /** The frame in the light theme, then the dark one: [check], every text at least 14sp, every control at least 48dp. */
    private fun inBothThemes(check: () -> Unit = {}) {
        for (theme in listOf(false, true)) {
            dark = theme
            compose.waitForIdle()
            check()
            assertNoTextUnder14sp()
            assertControlsAtLeast48dp()
        }
    }

    private fun assertNoTextUnder14sp() {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        for (node in nodes) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.forEach {
                assertTrue("${it.layoutInput.text} at ${it.layoutInput.style.fontSize}", it.layoutInput.style.fontSize.value >= MIN_TEXT_SP)
            }
        }
    }

    private fun assertControlsAtLeast48dp() {
        val enabled = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and
            SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled)
        val nodes = compose.onAllNodes(enabled).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        val density = compose.density.density
        for (node in nodes) {
            // Where it reacts to touch: Compose widens a smaller control's touch area to 48dp around it.
            val bounds = node.touchBoundsInRoot
            val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            assertTrue("$label is ${bounds.width / density} x ${bounds.height / density} dp", bounds.width / density >= MIN_TOUCH_DP && bounds.height / density >= MIN_TOUCH_DP)
        }
    }

    // ---- Set up Family ----

    private fun setUp(state: SetUpFamilyUiState) = show { SetUpFamilyScreen(state = state, onEvent = { events += it }) }

    @Test fun setUpFamily00() {
        setUp(FamilySamples.setUp)
        inBothThemes {
            exists(
                "Family", "One month for the two of you", "Pool what you both spend into one monthly total. Nobody owes anybody.",
                "Family name", "Home", "Invite your partner", "They join with a link and need the app.", "Counts in both",
                "What you pay for the family also shows in your own month.", "Personal stays private",
                "Only expenses marked Family are shared.", "SMS stays on each phone", "Only the expense entry is shared.",
            )
            absent("Earlier Family expenses", "That does not look right.")
        }
        compose.onNodeWithText("Invite partner").assertIsEnabled().performClick()
        assertEquals(listOf<Any>(SetUpFamilyEvent.Invite), events)
    }

    @Test fun setUpFamilyEarlierExpenses09() {
        setUp(FamilySamples.setUpEarlier)
        inBothThemes { exists("Earlier Family expenses", "Your 12 Family expenses on this phone are shared too.") }
    }

    @Test fun setUpFamilyNameBlank10() {
        setUp(FamilySamples.setUpNameBlank)
        inBothThemes { exists("That does not look right.") }
        compose.onNodeWithText("Invite partner").assertIsNotEnabled()
    }

    @Test fun setUpFamilyOffline11() {
        setUp(FamilySamples.setUpOffline)
        inBothThemes { exists("You're offline", "Check your connection and try again. Nothing is lost.") }
    }

    @Test fun setUpFamilyCouldNotDoThat12() {
        setUp(FamilySamples.setUpFailed)
        inBothThemes { exists("Could not do that now", "Try again in a moment.") }
    }

    // ---- Join Family ----

    private fun join(state: JoinGroupUiState) = show { JoinGroupScreen(state = state, onEvent = { events += it }) }

    @Test fun joinFamily01() {
        join(JoinSamples.family)
        inBothThemes {
            exists(
                "Arjun invited you to Home", "One monthly total for the two of you. Nobody owes anybody.", "Counts in both",
                "Personal stays private", "Only expenses you mark Family are shared.",
            )
            // A family shows no money and no group row.
            absent("Earlier Family expenses", "₹", "You owe")
        }
        compose.onNodeWithText("Join Family").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(listOf<Any>(JoinGroupEvent.Join, JoinGroupEvent.Leave), events)
    }

    @Test fun joinFamilyEarlierExpenses13() {
        join(JoinSamples.familyEarlier)
        inBothThemes { exists("Earlier Family expenses", "Your 12 Family expenses on this phone are shared too.") }
    }

    @Test fun joinFamilyOffline14() {
        join(JoinSamples.familyOffline)
        inBothThemes { exists("You're offline", "Check your connection and try again. Nothing is lost.", "Arjun invited you to Home") }
    }

    @Test fun joinFamilyCouldNotDoThat15() {
        join(JoinSamples.familyFailed)
        inBothThemes { exists("Could not do that now", "Try again in a moment.") }
    }

    @Test fun joinFamilyAlreadyInAFamily16() {
        join(JoinGroupUiState.AlreadyInFamily)
        inBothThemes {
            exists(
                "You're already in a family",
                "You can be in one family at a time. Leave your family on the You tab first, then open this link again.",
            )
            absent("Join Family")
        }
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf<Any>(JoinGroupEvent.Leave), events)
    }

    // ---- Family settings ----

    private fun settings(state: FamilySettingsUiState) = show { FamilySettingsScreen(state = state, onEvent = { events += it }) }

    @Test fun familySettings17() {
        settings(FamilySamples.settings)
        inBothThemes {
            exists("Family", "Family name", "Home", "Arjun Mehta", "You", "Meera Iyer", "Partner", "Leave Family")
            exists("Your Family expenses leave Home and stay on this phone.")
            described("People")
            described("Leaving")
            // No Add someone and no Delete: a family ends when its last member leaves.
            absent("Add someone", "Delete")
        }
        // The person's own row does nothing; the owner taps the partner to remove them.
        compose.onNode(hasText("Arjun Mehta") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Meera Iyer") and hasClickAction()).performClick()
        compose.onNode(hasText("Leave Family") and hasClickAction()).performClick()
        assertEquals(listOf<Any>(FamilySettingsEvent.PartnerTapped, FamilySettingsEvent.Leave), events)
    }

    @Test fun onlyTheOwnerCanTapThePartner() {
        settings(FamilySamples.settings.copy(partner = FamilyPartnerRow.Joined("m", "Meera Iyer", "Meera", canRemove = false)))
        compose.onNodeWithText("Meera Iyer", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Meera Iyer") and hasClickAction()).assertDoesNotExist()
    }

    @Test fun familySettingsInviteSent18() {
        settings(FamilySamples.settingsInviteSent)
        inBothThemes { exists("Your partner", "Invite sent · not joined yet") }
        compose.onNode(hasText("Your partner") and hasClickAction()).performClick()
        assertEquals(listOf<Any>(FamilySettingsEvent.PartnerTapped), events)
    }

    @Test fun familySettingsNoPartner19() {
        settings(FamilySamples.settingsNoPartner)
        inBothThemes { exists("Invite partner", "They join with a link and need the app.") }
        compose.onNode(hasText("Invite partner") and hasClickAction()).performClick()
        assertEquals(listOf<Any>(FamilySettingsEvent.PartnerTapped), events)
    }

    @Test fun familySettingsInviteSheet20() {
        settings(FamilySamples.settingsInviteSheet)
        inBothThemes {
            exists(
                "Link is on", "Anyone with it can join Home as your partner.",
                "Send it again if it got lost. Cancel invite turns the link off.",
            )
        }
        compose.onNodeWithText("Send link again").performClick()
        compose.onNodeWithText("Cancel invite").performClick()
        compose.onNodeWithText("Close").performClick()
        assertEquals(
            listOf<Any>(FamilySettingsEvent.SendLinkAgain, FamilySettingsEvent.CancelInvite, FamilySettingsEvent.CloseSheet),
            events,
        )
    }

    @Test fun familySettingsLeave21() {
        settings(FamilySamples.settingsLeave)
        inBothThemes {
            exists("Leave Home?", "Your Family expenses leave Home and stay on this phone as yours. You can join again with a new link.", "Stay")
        }
        compose.onNodeWithText("Stay").performClick()
        assertEquals(listOf<Any>(FamilySettingsEvent.CloseConfirm), events)
    }

    @Test fun familySettingsRemovePartner22() {
        settings(FamilySamples.settingsRemove)
        inBothThemes { exists("Remove Meera from Home?", "Meera's Family expenses leave Home. They stay on Meera's phone.", "Keep") }
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf<Any>(FamilySettingsEvent.Confirm), events)
    }

    @Test fun familySettingsOffline23() {
        settings(FamilySamples.settingsOffline)
        inBothThemes { exists("You're offline", "Check your connection and try again. Nothing is lost.") }
    }

    @Test fun familySettingsCouldNotDoThat24() {
        settings(FamilySamples.settingsFailed)
        inBothThemes { exists("Could not do that now", "Try again in a moment.") }
    }

    // ---- Home, Family side ----

    private fun home(state: HomeUiState) = show {
        MainScaffold(active = MainTab.Home, onSelect = {}) { padding ->
            HomeScreen(state = state, contentPadding = padding, onEvent = { events += it })
        }
    }

    @Test fun homeFamilyMonth02() {
        home(HomeSamples.familyMonth)
        inBothThemes {
            exists("FAMILY SPENT IN NOVEMBER", "₹42,300", "Arjun and Meera · 64 expenses")
            exists("Arjun (you)", "38 expenses", "₹24,100", "Meera", "26 expenses", "₹18,200")
            exists("BigBasket", "Groceries · Meera paid", "₹2,340", "Swiggy", "Food · You paid", "₹450")
            described("By person")
            described("Top categories")
            described("Rent and bills")
            described("Groceries")
            // Kind=Shared rows say "Family" in words; no payment card, no split tiles, no "+ Add".
            compose.onAllNodesWithText("Family", useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.size >= 2) }
            absent("Payment detected", "Owed to you", "+ Add", "Set up Family")
        }
        // The partner's row is not tappable; the person's own opens Expense detail.
        compose.onNode(hasText("BigBasket") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Swiggy") and hasClickAction()).performClick()
        assertEquals(listOf<Any>(HomeEvent.FamilyRowTapped(7)), events)
    }

    @Test fun homeFamilyMonthWaitingForPartner03() {
        home(HomeSamples.familyWaiting)
        inBothThemes {
            exists("Just you · 12 expenses", "Waiting for your partner", "They join with the link you sent. Send it again if it got lost.")
            // The Tag says the state in words.
            compose.onNode(hasContentDescription("Invite sent") and hasText("INVITE SENT")).assertExists()
            exists("Arjun (you)", "12 expenses", "₹6,840", "Groceries · You paid")
        }
        compose.onNodeWithText("Send link again").performClick()
        assertEquals(listOf<Any>(HomeEvent.SendFamilyLink), events)
    }

    @Test fun homeFamilyMonthJustYou04() {
        home(HomeSamples.familyAlone)
        inBothThemes {
            exists("Invite your partner", "Home has just you now. Send a link to share it again.", "Just you · 12 expenses")
            compose.onNode(hasContentDescription("Family") and hasText("FAMILY")).assertExists()
        }
        compose.onNodeWithText("Invite partner").performClick()
        assertEquals(listOf<Any>(HomeEvent.SendFamilyLink), events)
    }

    @Test fun homeFamilyMonthNothingYet05() {
        home(HomeSamples.familyNothingYet)
        inBothThemes {
            exists(
                "Arjun and Meera · 0 expenses", "Nothing in the family month yet",
                "When you add an expense, choose Family under Count it in.",
            )
            compose.onAllNodesWithText("0 expenses", useUnmergedTree = true).assertCountEquals(2)
            notDescribed("Top categories")
        }
    }

    @Test fun homeFamilyMonthLoading06() {
        home(HomeSamples.familyLoading)
        inBothThemes {
            described("Loading the family month")
            described("By person")
            described("Top categories")
            absent("Arjun (you)", "₹0", "Invite sent", "Waiting for your partner")
        }
    }

    @Test fun homeFamilyMonthOffline07() {
        home(HomeSamples.familyOffline)
        inBothThemes {
            exists("You're offline", "Your own expenses still work. The family month syncs when you are back online.")
            absent("Splits sync")
        }
    }

    @Test fun homeFamilyWithNoFamily51() {
        home(HomeSamples.familyNoFamily)
        inBothThemes {
            exists(
                "SPENT IN NOVEMBER", "your Family expenses on this phone", "Share this month with your partner",
                "Set up Family to see one total for the two of you. Only expenses marked Family are shared.",
            )
            compose.onNode(hasContentDescription("Family") and hasText("FAMILY")).assertExists()
            // As 51 draws: the payment card, the split tiles and Latest stay.
            exists("₹450 at Swiggy", "BigBasket")
        }
        compose.onNodeWithText("Set up Family").performClick()
        assertEquals(listOf<Any>(HomeEvent.SetUpFamily), events)
    }

    // ---- Month view (Family) and Sign in ----

    @Test fun monthViewFamily27() {
        show { FamilyMonthScreen(state = FamilySamples.month, onEvent = { events += it }) }
        inBothThemes {
            exists("Home · November 2026", "₹42,300", "₹38,100", "Arjun (you)", "Meera", "Rent and bills", "1 expense", "57%", "23%")
            exists("BigBasket", "Groceries · Meera paid", "Swiggy", "Food · You paid")
            described("Family spent")
            described("Today")
        }
        compose.onNodeWithText("Next month").assertIsNotEnabled()
        compose.onNode(hasText("BigBasket") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Swiggy") and hasClickAction()).assertHasClickAction().performClick()
        compose.onNodeWithText("Previous month").performClick()
        assertEquals(listOf<Any>(FamilyMonthEvent.RowTapped(7), FamilyMonthEvent.PreviousMonth), events)
    }

    @Test fun signInFamily08() {
        show { SignInScreen(state = SignInUiState(forFamily = true), onEvent = { events += it }) }
        inBothThemes {
            exists(
                "Sign in", "Share a family month", "Sign in to share a family month. Your SMS stay on this phone.", "Family month",
                "Kept in your account, so a new phone gets it back.", "Personal stays private",
                "Only expenses you mark Family are shared. Never your SMS.", "Not now",
            )
            absent("Split with friends", "Join a group")
        }
    }

    @Test fun theFamilyCardButtonIsAButton() {
        home(HomeSamples.familyWaiting)
        compose.onNodeWithText("Send link again").assertHasClickAction()
        compose.onNodeWithText("Waiting for your partner", useUnmergedTree = true).assertHasNoClickAction()
    }

    private companion object {
        const val MIN_TEXT_SP = 14f
        const val MIN_TOUCH_DP = 47.5f
    }
}
