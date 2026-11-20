package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.screens.grouppage.GroupPageSamples
import com.expenses.app.ui.screens.grouppage.GroupPageScreen
import com.expenses.app.ui.screens.grouppage.GroupSettingsScreen

/**
 * Previews of the Group page and Group settings (design 02-everyday/23 to 25, 40 to 43, 45, 46, and
 * "screen-group-settings-nobody-joined" for 143), one entry per design state, plus the states the design does not draw
 * (loading, the offline banner, the two confirms). Owned by the group-page area (docs/groups-build-plan.md).
 */
val groupPageScreenPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "group-page-owes-you", fullScreen = true) { GroupPageScreen(state = GroupPageSamples.page, onEvent = {}) },
    PreviewEntry(name = "group-page-loading", fullScreen = true) { GroupPageScreen(state = GroupPageSamples.loading, onEvent = {}) },
    PreviewEntry(name = "group-page-could-not-load", fullScreen = true) {
        GroupPageScreen(state = GroupPageSamples.couldNotLoad, onEvent = {})
    },
    PreviewEntry(name = "group-page-someone-joined", fullScreen = true) {
        GroupPageScreen(state = GroupPageSamples.someoneJoined, onEvent = {})
    },
    PreviewEntry(name = "group-page-join-undone", fullScreen = true) {
        GroupPageScreen(state = GroupPageSamples.joinUndone, onEvent = {})
    },
    PreviewEntry(name = "group-page-you-owe", fullScreen = true) { GroupPageScreen(state = GroupPageSamples.youOwe, onEvent = {}) },
    PreviewEntry(name = "group-page-change-not-saved", fullScreen = true) {
        GroupPageScreen(state = GroupPageSamples.changeNotSaved, onEvent = {})
    },
    PreviewEntry(name = "group-page-edit-not-saved", fullScreen = true) {
        GroupPageScreen(state = GroupPageSamples.editNotSaved, onEvent = {})
    },
    // Not designed: rows that cannot be tried again say why in place of Try again.
    PreviewEntry(name = "group-page-change-not-saved-someone-left", fullScreen = true) {
        GroupPageScreen(state = GroupPageSamples.changeNotSavedSomeoneLeft, onEvent = {})
    },
    PreviewEntry(name = "group-page-delete-not-saved", fullScreen = true) {
        GroupPageScreen(state = GroupPageSamples.deleteNotSaved, onEvent = {})
    },
    PreviewEntry(name = "group-page-offline", fullScreen = true) { GroupPageScreen(state = GroupPageSamples.offline, onEvent = {}) },
    PreviewEntry(name = "group-page-reminded", fullScreen = true) { GroupPageScreen(state = GroupPageSamples.reminded, onEvent = {}) },
    PreviewEntry(name = "group-page-all-settled", fullScreen = true) {
        GroupPageScreen(state = GroupPageSamples.allSettled, onEvent = {})
    },
    PreviewEntry(name = "group-page-direct", fullScreen = true) { GroupPageScreen(state = GroupPageSamples.direct, onEvent = {}) },
    PreviewEntry(name = "screen-group-settings-nobody-joined", fullScreen = true) {
        GroupSettingsScreen(state = GroupPageSamples.settingsNobodyJoined, onEvent = {})
    },
    PreviewEntry(name = "group-page-settings", fullScreen = true) {
        GroupSettingsScreen(state = GroupPageSamples.settings, onEvent = {})
    },
    PreviewEntry(name = "group-page-settings-not-owner", fullScreen = true) {
        GroupSettingsScreen(state = GroupPageSamples.settingsNotOwner, onEvent = {})
    },
    PreviewEntry(name = "group-page-settings-friends-link", fullScreen = true) {
        GroupSettingsScreen(state = GroupPageSamples.settingsFriendsLink, onEvent = {})
    },
    // Not designed, laid out as 46: a friend with no link sent yet.
    PreviewEntry(name = "group-page-settings-friend-no-link", fullScreen = true) {
        GroupSettingsScreen(state = GroupPageSamples.settingsFriendNoLink, onEvent = {})
    },
    PreviewEntry(name = "group-page-settings-friend-no-link-owes-nothing", fullScreen = true) {
        GroupSettingsScreen(state = GroupPageSamples.settingsFriendNoLinkOwesNothing, onEvent = {})
    },
    PreviewEntry(name = "group-page-settings-direct", fullScreen = true) {
        GroupSettingsScreen(state = GroupPageSamples.settingsDirect, onEvent = {})
    },
    PreviewEntry(name = "group-page-settings-leave", fullScreen = true) {
        GroupSettingsScreen(state = GroupPageSamples.settingsLeaveConfirm, onEvent = {})
    },
    PreviewEntry(name = "group-page-settings-delete", fullScreen = true) {
        GroupSettingsScreen(state = GroupPageSamples.settingsDeleteConfirm, onEvent = {})
    },
)
