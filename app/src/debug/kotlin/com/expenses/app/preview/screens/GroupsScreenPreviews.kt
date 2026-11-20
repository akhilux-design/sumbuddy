package com.expenses.app.preview.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.screens.groups.AddFriendScreen
import com.expenses.app.ui.screens.groups.GroupsSamples
import com.expenses.app.ui.screens.groups.GroupsScreen
import com.expenses.app.ui.screens.groups.GroupsUiState
import com.expenses.app.ui.screens.groups.NewGroupScreen
import com.expenses.app.ui.shell.MainScaffold
import kotlinx.coroutines.awaitCancellation

/**
 * The Groups tab, one entry per design state in design/spec/screens/02-everyday: "screen-groups" (20),
 * "screen-groups-empty" (21), "screen-groups-loading" (22), "groups-home-could-not-load" (the card of 23; not drawn
 * for this tab), "groups-home-not-signed-in" (39) and "groups-home-you-owe" (44); New group "groups-home-new-group"
 * (26, with "Import from Splitwise instead"), "groups-home-new-group-nobody" (no one known yet: only the "Add someone"
 * row) and "screen-new-group-from-split" (142: from a split, no import), 26 and 142 with the Group name field focused
 * as drawn; Add friend
 * "groups-home-add-friend" (27), "groups-home-add-friend-typed-number" (the same number typed without "+91" and
 * spaces, shown the same) and "groups-home-add-friend-phone-error".
 */
val groupsScreenPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "screen-groups", fullScreen = true) { GroupsInScaffold(GroupsSamples.default) },
    PreviewEntry(name = "screen-groups-empty", fullScreen = true) { GroupsInScaffold(GroupsSamples.empty) },
    PreviewEntry(name = "screen-groups-loading", fullScreen = true) { GroupsInScaffold(GroupsSamples.loading) },
    PreviewEntry(name = "groups-home-could-not-load", fullScreen = true) { GroupsInScaffold(GroupsSamples.couldNotLoad) },
    PreviewEntry(name = "groups-home-not-signed-in", fullScreen = true) { GroupsInScaffold(GroupsSamples.notSignedIn) },
    PreviewEntry(name = "groups-home-you-owe", fullScreen = true) { GroupsInScaffold(GroupsSamples.youOweOverall) },
    PreviewEntry(name = "groups-home-new-group", fullScreen = true) {
        FocusedWithoutKeyboard { NewGroupScreen(state = GroupsSamples.newGroup, onEvent = {}, showImport = true, focusName = true) }
    },
    PreviewEntry(name = "groups-home-new-group-nobody", fullScreen = true) {
        NewGroupScreen(state = GroupsSamples.newGroupNobodyKnown, onEvent = {}, showImport = true)
    },
    // 142: New group from a split (Choose people's "Make a group"): the people filled in, no Import from Splitwise.
    PreviewEntry(name = "screen-new-group-from-split", fullScreen = true) {
        FocusedWithoutKeyboard {
            NewGroupScreen(state = GroupsSamples.newGroup.copy(name = ""), onEvent = {}, showImport = false, focusName = true)
        }
    },
    PreviewEntry(name = "groups-home-add-friend", fullScreen = true) {
        AddFriendScreen(state = GroupsSamples.addFriend, onEvent = {})
    },
    // The number typed bare ("9876543210"): shown grouped as 27 draws it.
    PreviewEntry(name = "groups-home-add-friend-typed-number", fullScreen = true) {
        AddFriendScreen(state = GroupsSamples.addFriend.copy(phone = "9876543210"), onEvent = {})
    },
    PreviewEntry(name = "groups-home-add-friend-phone-error", fullScreen = true) {
        AddFriendScreen(state = GroupsSamples.addFriendPhoneInvalid, onEvent = {})
    },
)

/**
 * [content] with its text field focused the way 26 and 142 draw it (Input "State=Focused", no keyboard in the frame):
 * the field takes the focus and shows its green outline, but no input session starts, so the phone's keyboard stays
 * out of the capture.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FocusedWithoutKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }, content = content)
}

/** The Groups screen in [state], framed by the main scaffold with the Groups tab active. */
@Composable
private fun GroupsInScaffold(state: GroupsUiState) {
    MainScaffold(active = MainTab.Groups, onSelect = {}) { padding ->
        GroupsScreen(state = state, contentPadding = padding)
    }
}
