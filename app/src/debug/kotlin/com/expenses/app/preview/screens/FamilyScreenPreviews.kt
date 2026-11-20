package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.screens.family.FamilyMonthScreen
import com.expenses.app.ui.screens.family.FamilySamples
import com.expenses.app.ui.screens.family.FamilySettingsScreen
import com.expenses.app.ui.screens.family.FamilySettingsUiState
import com.expenses.app.ui.screens.family.SetUpFamilyScreen
import com.expenses.app.ui.screens.family.SetUpFamilyUiState
import com.expenses.app.ui.screens.home.HomeSamples
import com.expenses.app.ui.screens.home.HomeScreen
import com.expenses.app.ui.screens.home.HomeUiState
import com.expenses.app.ui.screens.join.JoinGroupScreen
import com.expenses.app.ui.screens.join.JoinGroupUiState
import com.expenses.app.ui.screens.join.JoinSamples
import com.expenses.app.ui.screens.payment.AddToExpensesScreen
import com.expenses.app.ui.screens.payment.ExpenseDetailScreen
import com.expenses.app.ui.screens.payment.PaymentSamples
import com.expenses.app.ui.screens.signin.SignInScreen
import com.expenses.app.ui.screens.signin.SignInUiState
import com.expenses.app.ui.screens.you.YouSamples
import com.expenses.app.ui.screens.you.YouScreen
import com.expenses.app.ui.screens.you.YouUiState
import com.expenses.app.ui.shell.MainScaffold

/**
 * Family (docs/family.md), one entry per frame of design/spec/screens/06-family, named "family-" and the frame's file
 * name without its number, for `design/tools/capture.sh`:
 *
 * - 00 "family-set-up-family", 09 "family-set-up-family-earlier-expenses", 10 "family-set-up-family-name-blank",
 *   11 "family-set-up-family-offline", 12 "family-set-up-family-could-not-do-that".
 * - 01 "family-join-family", 13 "family-join-family-earlier-expenses", 14 "family-join-family-offline",
 *   15 "family-join-family-could-not-do-that", 16 "family-join-family-already-in-a-family".
 * - 02 "family-home-family-month", 03 "family-home-family-month-waiting-for-partner", 04
 *   "family-home-family-month-just-you", 05 "family-home-family-month-nothing-yet", 06
 *   "family-home-family-month-loading", 07 "family-home-family-month-offline".
 * - 08 "family-sign-in-family".
 * - 17 "family-family-settings", 18 "family-family-settings-invite-sent", 19 "family-family-settings-no-partner",
 *   20 "family-family-settings-invite-sheet", 21 "family-family-settings-leave", 22
 *   "family-family-settings-remove-partner", 23 "family-family-settings-offline", 24
 *   "family-family-settings-could-not-do-that".
 * - 25 "family-add-to-expenses-family-every-time", 26 "family-expense-detail-delete-family", 27
 *   "family-month-view-family". 28 is the friends' web page (server/web), not the app.
 * - 02-everyday/51 "family-home-family" (no family on this phone, the Set up Family card); 04-you/00 "family-you" and
 *   04-you/06 "family-you-not-signed-in" (the FAMILY row), plus the row's other subtitles: "family-you-waiting",
 *   "family-you-just-you" and "family-you-no-family".
 */
val familyScreenPreviews: List<PreviewEntry> = listOf(
    setUp("family-set-up-family", FamilySamples.setUp),
    setUp("family-set-up-family-earlier-expenses", FamilySamples.setUpEarlier),
    setUp("family-set-up-family-name-blank", FamilySamples.setUpNameBlank),
    setUp("family-set-up-family-offline", FamilySamples.setUpOffline),
    setUp("family-set-up-family-could-not-do-that", FamilySamples.setUpFailed),
    join("family-join-family", JoinSamples.family),
    join("family-join-family-earlier-expenses", JoinSamples.familyEarlier),
    join("family-join-family-offline", JoinSamples.familyOffline),
    join("family-join-family-could-not-do-that", JoinSamples.familyFailed),
    join("family-join-family-already-in-a-family", JoinGroupUiState.AlreadyInFamily),
    home("family-home-family-month", HomeSamples.familyMonth),
    home("family-home-family-month-waiting-for-partner", HomeSamples.familyWaiting),
    home("family-home-family-month-just-you", HomeSamples.familyAlone),
    home("family-home-family-month-nothing-yet", HomeSamples.familyNothingYet),
    home("family-home-family-month-loading", HomeSamples.familyLoading),
    home("family-home-family-month-offline", HomeSamples.familyOffline),
    home("family-home-family", HomeSamples.familyNoFamily),
    PreviewEntry(name = "family-sign-in-family", fullScreen = true) {
        SignInScreen(state = SignInUiState(forFamily = true), onEvent = {})
    },
    settings("family-family-settings", FamilySamples.settings),
    settings("family-family-settings-invite-sent", FamilySamples.settingsInviteSent),
    settings("family-family-settings-no-partner", FamilySamples.settingsNoPartner),
    settings("family-family-settings-invite-sheet", FamilySamples.settingsInviteSheet),
    settings("family-family-settings-leave", FamilySamples.settingsLeave),
    settings("family-family-settings-remove-partner", FamilySamples.settingsRemove),
    settings("family-family-settings-offline", FamilySamples.settingsOffline),
    settings("family-family-settings-could-not-do-that", FamilySamples.settingsFailed),
    PreviewEntry(name = "family-add-to-expenses-family-every-time", fullScreen = true) {
        AddToExpensesScreen(state = PaymentSamples.addToExpensesFamilyEveryTime, onEvent = {})
    },
    PreviewEntry(name = "family-expense-detail-delete-family", fullScreen = true) {
        ExpenseDetailScreen(state = PaymentSamples.expenseDetailFamilyDelete, onEvent = {})
    },
    PreviewEntry(name = "family-month-view-family", fullScreen = true) { FamilyMonthScreen(state = FamilySamples.month) },
    you("family-you", YouSamples.default),
    you("family-you-not-signed-in", YouSamples.signedOut),
    you("family-you-waiting", YouSamples.familyWaiting),
    you("family-you-just-you", YouSamples.familyAlone),
    you("family-you-no-family", YouSamples.familyNone),
)

private fun setUp(name: String, state: SetUpFamilyUiState) = PreviewEntry(name = name, fullScreen = true) {
    SetUpFamilyScreen(state = state, onEvent = {})
}

private fun join(name: String, state: JoinGroupUiState) = PreviewEntry(name = name, fullScreen = true) {
    JoinGroupScreen(state = state, onEvent = {})
}

private fun settings(name: String, state: FamilySettingsUiState) = PreviewEntry(name = name, fullScreen = true) {
    FamilySettingsScreen(state = state, onEvent = {})
}

/** [state] inside the main scaffold with the Home tab active. */
private fun home(name: String, state: HomeUiState) = PreviewEntry(name = name, fullScreen = true) {
    MainScaffold(active = MainTab.Home, onSelect = {}) { padding -> HomeScreen(state = state, contentPadding = padding) }
}

/** [state] inside the main scaffold with the You tab active. */
private fun you(name: String, state: YouUiState) = PreviewEntry(name = name, fullScreen = true) {
    MainScaffold(active = MainTab.You, onSelect = {}) { padding -> YouScreen(state = state, contentPadding = padding) }
}
