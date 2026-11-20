package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.screens.onboarding.AllowNotificationsScreen
import com.expenses.app.ui.screens.onboarding.ConfirmAccountsScreen
import com.expenses.app.ui.screens.onboarding.HowFarBackScreen
import com.expenses.app.ui.screens.onboarding.NothingFoundScreen
import com.expenses.app.ui.screens.onboarding.OnboardingSamples
import com.expenses.app.ui.screens.onboarding.PrivacyPromiseScreen
import com.expenses.app.ui.screens.onboarding.ScanningScreen
import com.expenses.app.ui.screens.onboarding.SmsBlockedScreen
import com.expenses.app.ui.screens.onboarding.WelcomeScreen
import com.expenses.app.ui.screens.onboarding.YourNameScreen

/**
 * Previews of the onboarding screens with sample state, one per design in `design/spec/screens/01-onboarding/`:
 * "onboarding-welcome" (00), "onboarding-your-name" (04), "onboarding-privacy-promise" (05),
 * "onboarding-allow-notifications" (07), "onboarding-how-far-back" (08), "onboarding-scanning-sms" (09),
 * "onboarding-nothing-found" (10) and "onboarding-confirm-accounts" (11). Also the states the design does not
 * draw: "onboarding-sms-blocked", "onboarding-scanning-sms-started" and "onboarding-rename-account".
 */
val onboardingScreenPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "onboarding-welcome", fullScreen = true) {
        WelcomeScreen(onGetStarted = {})
    },
    PreviewEntry(name = "onboarding-your-name", fullScreen = true) {
        YourNameScreen(state = OnboardingSamples.yourName, onEvent = {}, onBack = {})
    },
    PreviewEntry(name = "onboarding-privacy-promise", fullScreen = true) {
        PrivacyPromiseScreen(onAllow = {}, onTypeByHand = {})
    },
    PreviewEntry(name = "onboarding-sms-blocked", fullScreen = true) {
        SmsBlockedScreen(onOpenSettings = {}, onTypeByHand = {})
    },
    PreviewEntry(name = "onboarding-allow-notifications", fullScreen = true) {
        AllowNotificationsScreen(onAllow = {}, onNotNow = {})
    },
    PreviewEntry(name = "onboarding-how-far-back", fullScreen = true) {
        HowFarBackScreen(state = OnboardingSamples.howFarBack, onEvent = {}, onBack = {})
    },
    PreviewEntry(name = "onboarding-scanning-sms", fullScreen = true) {
        ScanningScreen(state = OnboardingSamples.scanning, onEvent = {})
    },
    PreviewEntry(name = "onboarding-scanning-sms-started", fullScreen = true) {
        ScanningScreen(state = OnboardingSamples.scanningStarted, onEvent = {})
    },
    PreviewEntry(name = "onboarding-nothing-found", fullScreen = true) {
        NothingFoundScreen(state = OnboardingSamples.nothingFound, onEvent = {})
    },
    PreviewEntry(name = "onboarding-confirm-accounts", fullScreen = true) {
        ConfirmAccountsScreen(state = OnboardingSamples.confirmAccounts, onEvent = {}, onBack = {})
    },
    PreviewEntry(name = "onboarding-rename-account", fullScreen = true) {
        ConfirmAccountsScreen(state = OnboardingSamples.renameAccount, onEvent = {}, onBack = {})
    },
)
