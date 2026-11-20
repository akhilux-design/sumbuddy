package com.expenses.app.ui.screens.split

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.expenses.app.R
import com.expenses.app.ui.motion.AddedRow
import com.expenses.app.ui.motion.rememberFirstKeys
import androidx.compose.runtime.key
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppSwitch
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space

/**
 * Choose people (design 02-everyday/12-choose-people, 48-choose-people-several-friends): a search by name or phone
 * number, then GROUPS and FRIENDS as switch rows. One group or one friend, then "Done". With two or more friends on,
 * the "2 friends picked" line explains that a split goes to one group or one friend, and the button becomes "Make a
 * group". The last row under Friends is Group settings' "Add someone" (not designed here), so a person who knows
 * nobody on the app yet can still split.
 *
 * @param state what to show; see [ChoosePeopleUiState].
 * @param onEvent called with what the person did; see [ChoosePeopleEvent].
 * @param modifier outer modifier. The screen fills the space it is given and keeps clear of the system bars.
 */
@Composable
fun ChoosePeopleScreen(state: ChoosePeopleUiState, onEvent: (ChoosePeopleEvent) -> Unit, modifier: Modifier = Modifier) {
    val appName = stringResource(R.string.app_name)
    PageFrame(
        title = stringResource(R.string.split_choose_people_title),
        onBack = { onEvent(ChoosePeopleEvent.Back) },
        modifier = modifier,
        footer = {
            if (state.severalFriends) {
                AppButton(
                    label = stringResource(R.string.split_choose_make_group),
                    onClick = { onEvent(ChoosePeopleEvent.MakeGroup) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.working,
                )
            } else {
                AppButton(
                    label = stringResource(R.string.split_choose_done),
                    onClick = { onEvent(ChoosePeopleEvent.Done) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canFinish,
                )
            }
        },
    ) {
        AppInput(
            value = state.query,
            onValueChange = { onEvent(ChoosePeopleEvent.QueryChanged(it)) },
            label = stringResource(R.string.split_choose_search),
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.split_choose_search_placeholder),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
        )
        // People added while this screen is open (Add friend) open in; the rest just show, also when a search filters.
        val firstGroups = rememberFirstKeys(state.groups.map { it.id })
        val firstFriends = rememberFirstKeys(state.friends.map { it.key })
        if (state.groups.isNotEmpty()) {
            SectionLabel(text = stringResource(R.string.split_choose_groups))
            Rows {
                state.groups.forEach { group ->
                    val on = group.id == state.selectedGroupId
                    key(group.id) { AddedRow(added = group.id !in firstGroups) {
                        SettingRow(
                            title = group.name,
                            subtitle = pluralStringResource(R.plurals.split_choose_people_count, group.memberCount, group.memberCount),
                            icon = AppIcon.Users,
                            checked = on,
                            onCheckedChange = { onEvent(ChoosePeopleEvent.GroupSwitched(group.id, it)) },
                            trailing = { AppSwitch(checked = on, onCheckedChange = null) },
                        )
                    } }
                }
            }
        }
        SectionLabel(text = stringResource(R.string.split_choose_friends))
        Rows {
            state.friends.forEach { friend ->
                val on = friend.key in state.selectedFriends
                key(friend.key) { AddedRow(added = friend.key !in firstFriends) {
                    SettingRow(
                        title = friend.name,
                        subtitle = if (friend.onApp) {
                            stringResource(R.string.split_choose_on_app, appName)
                        } else {
                            stringResource(R.string.split_choose_whatsapp)
                        },
                        icon = AppIcon.User,
                        checked = on,
                        onCheckedChange = { onEvent(ChoosePeopleEvent.FriendSwitched(friend.key, it)) },
                        trailing = { AppSwitch(checked = on, onCheckedChange = null) },
                    )
                } }
            }
            SettingRow(
                title = stringResource(R.string.split_choose_add_someone),
                subtitle = stringResource(R.string.split_choose_add_someone_subtitle),
                icon = AppIcon.Plus,
                onClick = { onEvent(ChoosePeopleEvent.AddSomeone) },
            )
        }
        if (state.severalFriends) {
            val count = state.selectedFriends.size
            InfoRow(
                title = pluralStringResource(R.plurals.split_choose_several_title, count, count),
                subtitle = stringResource(
                    if (count == 2) R.string.split_choose_several_body else R.string.split_choose_several_body_many,
                ),
                icon = AppIcon.Users,
            )
        }
    }
}

/** A list of rows 8dp apart, as the design's Groups and Friends frames. */
@Composable
private fun Rows(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) { content() }
}
