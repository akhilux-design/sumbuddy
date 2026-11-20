package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.screens.home.HomeScreen
import com.expenses.app.ui.screens.home.HomeSamples
import com.expenses.app.ui.screens.home.HomeUiState
import com.expenses.app.ui.shell.MainScaffold

/**
 * Previews of the Home tab, one per design state in `design/spec/screens/02-everyday/`:
 * "screen-home" (00-home), "screen-home-start-fresh" (01), "screen-home-manual" (02) and
 * "screen-home-offline" (03) and "screen-home-several-waiting" (33), and "groups-home-home-no-splits" (00 for
 * someone who splits with nobody: no split tiles and no "Last time you split" line). Each shows the screen inside the
 * main scaffold with Home active.
 */
val homeScreenPreviews: List<PreviewEntry> = listOf(
    homePreview(name = "screen-home", state = HomeSamples.default),
    homePreview(name = "screen-home-start-fresh", state = HomeSamples.startFresh),
    homePreview(name = "screen-home-manual", state = HomeSamples.manualMode),
    homePreview(name = "screen-home-offline", state = HomeSamples.offline),
    homePreview(name = "screen-home-several-waiting", state = HomeSamples.severalWaiting),
    homePreview(name = "groups-home-home-no-splits", state = HomeSamples.noSplits),
    // Not drawn: UPI-ID payees shown whole, broken only after the "@", and ₹0 tiles in the neutral colour.
    homePreview(name = "screen-home-upi-payees", state = HomeSamples.upiPayees),
)

/** A full-screen preview of [state] inside the main scaffold with the Home tab active. */
private fun homePreview(name: String, state: HomeUiState): PreviewEntry = PreviewEntry(name = name, fullScreen = true) {
    MainScaffold(active = MainTab.Home, onSelect = {}) { padding ->
        HomeScreen(state = state, contentPadding = padding)
    }
}
