package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.screens.signin.ActionProblem
import com.expenses.app.ui.screens.signin.SignInNameScreen
import com.expenses.app.ui.screens.signin.SignInNameUiState
import com.expenses.app.ui.screens.signin.SignInProblem
import com.expenses.app.ui.screens.signin.SignInScreen
import com.expenses.app.ui.screens.signin.SignInUiState

/** "Your name (after sign-in)" as drawn in 01-onboarding/03: Arjun Mehta, his phone number and UPI ID. */
private val yourName = SignInNameUiState(name = "Arjun Mehta", phone = "+91 98765 43210", upiId = "arjun@okhdfc")

/**
 * Previews of the sign-in screens, one per design in `design/spec/screens/01-onboarding/`: "signin" (01-sign-in),
 * "signin-could-not-sign-in" (02) and "signin-your-name" (03-your-name-after-sign-in); and
 * "you-join-signin-for-join" (02-everyday/49-join-group-sign-in-first). Also states the design does not draw:
 * "signin-not-configured", "signin-dev" (with the debug-only Dev sign-in), "signin-your-name-invalid",
 * "you-join-your-name-upi-invalid" and "you-join-your-name-offline".
 */
val signInScreenPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "signin", fullScreen = true) {
        SignInScreen(state = SignInUiState(), onEvent = {})
    },
    PreviewEntry(name = "signin-could-not-sign-in", fullScreen = true) {
        SignInScreen(state = SignInUiState(problem = SignInProblem.FAILED), onEvent = {})
    },
    PreviewEntry(name = "you-join-signin-for-join", fullScreen = true) {
        SignInScreen(state = SignInUiState(forJoin = true), onEvent = {})
    },
    PreviewEntry(name = "signin-not-configured", fullScreen = true) {
        SignInScreen(state = SignInUiState(problem = SignInProblem.NOT_CONFIGURED, devSignIn = true), onEvent = {})
    },
    PreviewEntry(name = "signin-dev", fullScreen = true) {
        SignInScreen(state = SignInUiState(devSignIn = true), onEvent = {})
    },
    PreviewEntry(name = "signin-your-name", fullScreen = true) {
        SignInNameScreen(state = yourName, onEvent = {}, onBack = {})
    },
    PreviewEntry(name = "signin-your-name-invalid", fullScreen = true) {
        SignInNameScreen(state = yourName.copy(phone = "98765", phoneInvalid = true), onEvent = {}, onBack = {})
    },
    PreviewEntry(name = "you-join-your-name-upi-invalid", fullScreen = true) {
        SignInNameScreen(state = yourName.copy(upiId = "arjunokhdfc", upiInvalid = true), onEvent = {}, onBack = {})
    },
    PreviewEntry(name = "you-join-your-name-offline", fullScreen = true) {
        SignInNameScreen(state = yourName.copy(problem = ActionProblem.OFFLINE), onEvent = {}, onBack = {})
    },
)
