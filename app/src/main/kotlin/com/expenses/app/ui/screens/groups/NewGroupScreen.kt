package com.expenses.app.ui.screens.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.expenses.app.R
import com.expenses.app.groups.data.GroupText
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppSwitch
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space

/**
 * What New group shows.
 *
 * @param name the group name being typed.
 * @param people everyone who can be put in the group: the people the person already splits with, then the friends
 * added on Add friend, each with its switch.
 * @param creating true while the group is being made, so a second tap does nothing.
 */
@Immutable
data class NewGroupUiState(
    val name: String = "",
    val people: List<NewGroupPerson> = emptyList(),
    val creating: Boolean = false,
) {
    /** True when "Create group" can be used: the name is not blank once cleaned. */
    val canCreate: Boolean get() = GroupText.clean(name, GroupText.MAX_GROUP_NAME).isNotEmpty() && !creating
}

/**
 * One person on New group.
 *
 * @param key who it is: `Person.key` for someone known, or a key made on this screen for a friend just added.
 * @param name their name.
 * @param onApp true for an app user ("On SumBuddy"); false for a friend without the app, who gets a WhatsApp link.
 * @param selected true when the switch is on: they are put in the group.
 */
@Immutable
data class NewGroupPerson(val key: String, val name: String, val onApp: Boolean, val selected: Boolean)

/** What the person does on New group. */
sealed interface NewGroupEvent {
    /** The name field changed. */
    data class NameChanged(val name: String) : NewGroupEvent

    /** The switch of the person [key] was turned to [selected]. */
    data class PersonSwitched(val key: String, val selected: Boolean) : NewGroupEvent

    /** "Add someone": open Add friend. */
    data object AddSomeone : NewGroupEvent

    /** "Create group". */
    data object Create : NewGroupEvent

    /** "Import from Splitwise instead": open Import from Splitwise. */
    data object ImportFromSplitwise : NewGroupEvent

    /** The back button. */
    data object Back : NewGroupEvent
}

/**
 * New group (design 02-everyday/26-new-group): the group name, "Add friends" with a switch for each person the person
 * already splits with, the "Add someone" row (from Group settings, 25) for someone new, the note that friends do not
 * need the app, and "Create group" with "Import from Splitwise instead" under it. Opened from a split (Choose people's
 * "Make a group", 142), the import is not offered: the person is in the middle of splitting a payment.
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param modifier layout changes from the caller.
 * @param showImport true to offer "Import from Splitwise instead" (New group opened from the Groups tab, 26).
 * @param focusName true to put the cursor in the name field when the screen opens, as the design draws it.
 * @param onNameFocused called once the cursor was put there, so it is not put there again when the person comes back.
 */
@Composable
fun NewGroupScreen(
    state: NewGroupUiState,
    onEvent: (NewGroupEvent) -> Unit,
    modifier: Modifier = Modifier,
    showImport: Boolean = false,
    focusName: Boolean = false,
    onNameFocused: () -> Unit = {},
) {
    val nameFocus = remember { FocusRequester() }
    if (focusName) {
        LaunchedEffect(Unit) {
            nameFocus.requestFocus()
            onNameFocused()
        }
    }
    val appName = stringResource(R.string.app_name)
    PageFrame(
        title = stringResource(R.string.groups_new_group_title),
        onBack = { onEvent(NewGroupEvent.Back) },
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.groups_new_group_create),
                onClick = { onEvent(NewGroupEvent.Create) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Primary,
                size = ButtonSize.Large,
                enabled = state.canCreate,
            )
            if (showImport) {
                AppButton(
                    label = stringResource(R.string.groups_new_group_import),
                    onClick = { onEvent(NewGroupEvent.ImportFromSplitwise) },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Ghost,
                    size = ButtonSize.Medium,
                )
            }
        },
    ) {
        AppInput(
            value = state.name,
            onValueChange = { onEvent(NewGroupEvent.NameChanged(it)) },
            label = stringResource(R.string.groups_new_group_name),
            modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        )
        SectionLabel(text = stringResource(R.string.groups_new_group_add_friends))
        // The design's Friends list: rows 8dp apart. The "Add someone" row of Group settings ends it.
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            state.people.forEach { person ->
                SettingRow(
                    title = person.name,
                    subtitle = if (person.onApp) {
                        stringResource(R.string.groups_person_on_app, appName)
                    } else {
                        stringResource(R.string.groups_person_gets_link, appName)
                    },
                    icon = AppIcon.User,
                    checked = person.selected,
                    onCheckedChange = { onEvent(NewGroupEvent.PersonSwitched(person.key, it)) },
                    trailing = { AppSwitch(checked = person.selected, onCheckedChange = null) },
                )
            }
            SettingRow(
                title = stringResource(R.string.groups_add_someone_title),
                subtitle = stringResource(R.string.groups_add_someone_body),
                icon = AppIcon.Plus,
                onClick = { onEvent(NewGroupEvent.AddSomeone) },
            )
        }
        InfoRow(
            title = stringResource(R.string.groups_new_group_info_title),
            subtitle = stringResource(R.string.groups_new_group_info_body),
            icon = AppIcon.Message,
        )
    }
}
