package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.screens.you.AccountsScreen
import com.expenses.app.ui.screens.you.TrackingStartScreen
import com.expenses.app.ui.screens.you.WhatWeReadScreen
import com.expenses.app.ui.screens.you.YouAccountScreen
import com.expenses.app.ui.screens.you.YouSamples
import com.expenses.app.ui.screens.you.YouScreen
import com.expenses.app.ui.shell.MainScaffold

/**
 * The You tab inside the main scaffold and the screens it opens, one entry per design state in
 * design/spec/screens/04-you: "screen-you" (00, signed in) and "you-join-you-not-signed-in" (06), "screen-accounts"
 * (01) with "screen-accounts-rename", "you-join-account" (02), "you-join-account-delete" (03),
 * "you-join-account-upi-not-valid" (07), "you-join-account-unsent" (08), "screen-what-we-read" (04), and
 * "screen-tracking-start" (05) with "screen-tracking-start-scanning". Not in the design: "you-join-account-offline".
 */
val youScreenPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "screen-you", fullScreen = true) {
        MainScaffold(active = MainTab.You, onSelect = {}) { padding ->
            YouScreen(state = YouSamples.default, contentPadding = padding)
        }
    },
    PreviewEntry(name = "you-join-you-not-signed-in", fullScreen = true) {
        MainScaffold(active = MainTab.You, onSelect = {}) { padding ->
            YouScreen(state = YouSamples.signedOut, contentPadding = padding)
        }
    },
    PreviewEntry(name = "you-join-account", fullScreen = true) { YouAccountScreen(state = YouSamples.account, onEvent = {}) },
    PreviewEntry(name = "you-join-account-delete", fullScreen = true) {
        YouAccountScreen(state = YouSamples.accountDelete, onEvent = {})
    },
    PreviewEntry(name = "you-join-account-upi-not-valid", fullScreen = true) {
        YouAccountScreen(state = YouSamples.accountUpiInvalid, onEvent = {})
    },
    PreviewEntry(name = "you-join-account-unsent", fullScreen = true) {
        YouAccountScreen(state = YouSamples.accountUnsent, onEvent = {})
    },
    PreviewEntry(name = "you-join-account-offline", fullScreen = true) {
        YouAccountScreen(state = YouSamples.accountOffline, onEvent = {})
    },
    PreviewEntry(name = "screen-accounts", fullScreen = true) { AccountsScreen(state = YouSamples.accounts) },
    PreviewEntry(name = "screen-accounts-rename", fullScreen = true) {
        AccountsScreen(state = YouSamples.accountsRenaming)
    },
    PreviewEntry(name = "screen-what-we-read", fullScreen = true) { WhatWeReadScreen(state = YouSamples.whatWeRead) },
    PreviewEntry(name = "screen-tracking-start", fullScreen = true) {
        TrackingStartScreen(state = YouSamples.trackingStart)
    },
    PreviewEntry(name = "screen-tracking-start-scanning", fullScreen = true) {
        TrackingStartScreen(state = YouSamples.trackingStartScanning)
    },
)
