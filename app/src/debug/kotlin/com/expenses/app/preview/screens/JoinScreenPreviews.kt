package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.screens.join.JoinGroupScreen
import com.expenses.app.ui.screens.join.JoinGroupUiState
import com.expenses.app.ui.screens.join.JoinSamples

/**
 * Previews of Join group, one entry per design state: "you-join-join-group" (02-everyday/32) and
 * "you-join-join-link-no-longer-works" (50); the Sign in first screen (49) is "you-join-signin-for-join". Also states
 * the design does not draw: "you-join-join-loading" (22's shapes), "you-join-join-could-not-load" (23's wording),
 * "you-join-join-offline" (the banner after Join group offline), "you-join-join-owed" (owed, a bigger group) and
 * "you-join-join-four-others" (four other members, all named, the sender first).
 */
val joinScreenPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "you-join-join-group", fullScreen = true) { JoinGroupScreen(state = JoinSamples.ready, onEvent = {}) },
    PreviewEntry(name = "you-join-join-link-no-longer-works", fullScreen = true) {
        JoinGroupScreen(state = JoinGroupUiState.LinkNoLongerWorks, onEvent = {})
    },
    PreviewEntry(name = "you-join-join-loading", fullScreen = true) {
        JoinGroupScreen(state = JoinGroupUiState.Loading, onEvent = {})
    },
    PreviewEntry(name = "you-join-join-could-not-load", fullScreen = true) {
        JoinGroupScreen(state = JoinSamples.couldNotLoad, onEvent = {})
    },
    PreviewEntry(name = "you-join-join-offline", fullScreen = true) {
        JoinGroupScreen(state = JoinSamples.joinOffline, onEvent = {})
    },
    PreviewEntry(name = "you-join-join-owed", fullScreen = true) { JoinGroupScreen(state = JoinSamples.owed, onEvent = {}) },
    PreviewEntry(name = "you-join-join-four-others", fullScreen = true) {
        JoinGroupScreen(state = JoinSamples.fourOthers, onEvent = {})
    },
)
