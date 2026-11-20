package com.expenses.app.ui.screens.grouppage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.expenses.app.R
import com.expenses.app.ui.motion.AddedRow
import com.expenses.app.ui.motion.RevealValue
import com.expenses.app.ui.motion.rememberFirstKeys
import com.expenses.app.ui.motion.AnimatedOverlay
import com.expenses.app.ui.motion.OverlayScope
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppInputState
import com.expenses.app.ui.components.BottomSheet
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.ChoiceDialog
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.components.Tag
import com.expenses.app.ui.components.TagTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/** Space between the rows of one list (`space/8`); the body puts 12 between its other parts. */
private val RowGap = Space.s8

/**
 * Group settings (design 02-everyday/25-group-settings): the group's name, People with the owner's tag, Add someone,
 * and Leaving with Leave group and Delete group; 45 when someone else owns the group, 46 the friend's link sheet. The
 * leave and delete confirms are not designed: they are the shared [ChoiceDialog].
 *
 * @param state what to show; see [GroupSettingsUiState].
 * @param onEvent called with what the person did; see [GroupSettingsEvent].
 * @param modifier layout changes from the caller.
 */
@Composable
fun GroupSettingsScreen(state: GroupSettingsUiState, onEvent: (GroupSettingsEvent) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.grouppage_settings_title)
    val back = { onEvent(GroupSettingsEvent.Back) }
    when (state) {
        GroupSettingsUiState.Loading, GroupSettingsUiState.Gone -> PageFrame(title = title, onBack = back, modifier = modifier) {}
        is GroupSettingsUiState.Shown -> Box(modifier = modifier.fillMaxSize()) {
            PageFrame(
                title = title,
                onBack = back,
                // While the friend's sheet is open, screen readers see only the sheet (as on Review payments).
                modifier = if (state.sheet != null) Modifier.clearAndSetSemantics {} else Modifier,
            ) { SettingsBody(state, onEvent) }
            if (state.sheet != null) BackHandler { onEvent(GroupSettingsEvent.CloseSheet) }
            AnimatedOverlay(state.sheet) { sheet -> FriendLinkSheet(sheet, onEvent, overlay = this) }
            AnimatedOverlay(state.confirm?.let { it to state }) { (confirm, shown) ->
                when (confirm) {
                    SettingsConfirm.Leave -> ChoiceDialog(
                        icon = AppIcon.Logout,
                        title = stringResource(
                            if (shown.isDirect) R.string.grouppage_leave_confirm_title_direct else R.string.grouppage_leave_confirm_title,
                            shown.groupName,
                        ),
                        body = stringResource(R.string.grouppage_leave_confirm_body),
                        confirmLabel = stringResource(R.string.grouppage_settings_leave),
                        dismissLabel = stringResource(R.string.grouppage_leave_confirm_stay),
                        onConfirm = { onEvent(GroupSettingsEvent.Confirm) },
                        onDismiss = { onEvent(GroupSettingsEvent.CloseConfirm) },
                        overlay = this,
                    )
                    SettingsConfirm.Delete -> ChoiceDialog(
                        icon = AppIcon.Trash,
                        title = stringResource(R.string.grouppage_delete_confirm_title, shown.groupName),
                        body = stringResource(R.string.grouppage_delete_confirm_body),
                        confirmLabel = stringResource(R.string.grouppage_settings_delete),
                        dismissLabel = stringResource(R.string.grouppage_delete_confirm_keep),
                        onConfirm = { onEvent(GroupSettingsEvent.Confirm) },
                        onDismiss = { onEvent(GroupSettingsEvent.CloseConfirm) },
                        overlay = this,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsBody(state: GroupSettingsUiState.Shown, onEvent: (GroupSettingsEvent) -> Unit) {
    val appName = stringResource(R.string.app_name)
    RevealValue(state.notice) { NoticeBanner(it) }
    if (!state.isDirect) NameField(state, onEvent)
    SectionLabel(text = stringResource(R.string.grouppage_settings_people))
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RowGap)) {
        val firstPeople = rememberFirstKeys(state.people.map { it.memberId })
        state.people.forEach { person ->
            key(person.memberId) {
                AddedRow(added = person.memberId !in firstPeople) {
                    SettingRow(
                        title = person.name,
                        subtitle = when (person.kind) {
                            PersonKind.You -> stringResource(R.string.grouppage_settings_you)
                            PersonKind.OnApp -> stringResource(R.string.grouppage_settings_on_app, appName)
                            PersonKind.Friend -> stringResource(R.string.grouppage_settings_not_on_app, appName)
                            PersonKind.FriendLinkSent -> stringResource(R.string.grouppage_settings_link_sent, appName)
                        },
                        icon = AppIcon.User,
                        // A friend without the app opens their link sheet (46), with or without a link sent.
                        onClick = if (person.kind == PersonKind.Friend || person.kind == PersonKind.FriendLinkSent) {
                            { onEvent(GroupSettingsEvent.PersonTapped(person.memberId)) }
                        } else {
                            null
                        },
                        trailing = if (person.isOwner) ({ Tag(label = stringResource(R.string.grouppage_settings_owner), tone = TagTone.Brand) }) else null,
                    )
                }
            }
        }
        if (!state.isDirect) {
            SettingRow(
                title = stringResource(R.string.grouppage_settings_add_someone),
                subtitle = stringResource(R.string.grouppage_settings_add_someone_body),
                icon = AppIcon.Plus,
                onClick = { onEvent(GroupSettingsEvent.AddSomeone) },
            )
        }
    }
    SectionLabel(text = stringResource(R.string.grouppage_settings_leaving))
    SurfaceCard {
        // Not tappable while the balance is not 0: the subtitle says why.
        InfoRow(
            title = stringResource(R.string.grouppage_settings_leave),
            subtitle = stringResource(R.string.grouppage_settings_leave_body),
            icon = AppIcon.Logout,
            onClick = if (state.canLeave) ({ onEvent(GroupSettingsEvent.Leave) }) else null,
        )
        when (val delete = state.delete) {
            is DeleteRow.Owner -> InfoRow(
                title = stringResource(R.string.grouppage_settings_delete),
                subtitle = stringResource(
                    if (delete.nobodyElseJoined) R.string.grouppage_settings_delete_body_alone else R.string.grouppage_settings_delete_body,
                ),
                icon = AppIcon.Trash,
                onClick = if (delete.canDelete) ({ onEvent(GroupSettingsEvent.Delete) }) else null,
            )
            is DeleteRow.NotOwner -> InfoRow(
                title = stringResource(R.string.grouppage_settings_delete),
                subtitle = stringResource(R.string.grouppage_settings_delete_not_owner, delete.ownerName, firstNameOf(delete.ownerName)),
                icon = AppIcon.Lock,
            )
            null -> Unit
        }
    }
}

/** "Group name": saved when the person leaves the field (focus moves away, or Done on the keyboard) or the screen. */
@Composable
private fun NameField(state: GroupSettingsUiState.Shown, onEvent: (GroupSettingsEvent) -> Unit) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    AppInput(
        value = state.nameField,
        onValueChange = { onEvent(GroupSettingsEvent.NameChanged(it)) },
        label = stringResource(R.string.grouppage_settings_name),
        modifier = Modifier.onFocusChanged { focus ->
            if (focused && !focus.isFocused) onEvent(GroupSettingsEvent.NameDone)
            focused = focus.isFocused
        },
        helper = stringResource(R.string.grouppage_settings_name_error),
        state = if (state.nameError) AppInputState.Error else AppInputState.Default,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
    )
}

/**
 * The friend's link (46): who they are, that the link is on, the note, Turn off link and Cancel. For a friend with no
 * link from this phone (not designed) the same sheet says only that: another member, or the person on another phone,
 * may have sent one. Its main button is "Remind {name}", which opens Remind on WhatsApp to make the link and send it;
 * it is off, with the note saying why, while the friend owes the person nothing. Turn off link follows as a Secondary
 * button, as it stops every link of the friend, whoever made it.
 */
@Composable
private fun FriendLinkSheet(sheet: FriendSheet, onEvent: (GroupSettingsEvent) -> Unit, overlay: OverlayScope) {
    val appName = stringResource(R.string.app_name)
    val firstName = firstNameOf(sheet.name)
    BottomSheet(title = sheet.name, onDismiss = { onEvent(GroupSettingsEvent.CloseSheet) }, overlay = overlay) {
        SurfaceCard {
            InfoRow(
                title = stringResource(R.string.grouppage_settings_not_on_app, appName),
                subtitle = stringResource(R.string.grouppage_link_not_on_app_body, firstName),
                icon = AppIcon.User,
            )
            InfoRow(
                title = stringResource(if (sheet.linkOn) R.string.grouppage_link_on_title else R.string.grouppage_link_off_title),
                subtitle = stringResource(if (sheet.linkOn) R.string.grouppage_link_on_body else R.string.grouppage_link_off_body, firstName),
                icon = AppIcon.Link,
            )
        }
        AppText(
            text = stringResource(
                when {
                    sheet.linkOn -> R.string.grouppage_link_note
                    sheet.canRemind -> R.string.grouppage_link_off_note
                    else -> R.string.grouppage_link_off_note_owes_nothing
                },
                firstName,
            ),
            style = AppTheme.type.bodyM,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
        )
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            if (sheet.linkOn) {
                AppButton(
                    label = stringResource(R.string.grouppage_link_turn_off),
                    onClick = { onEvent(GroupSettingsEvent.TurnOffLink) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                AppButton(
                    label = stringResource(R.string.grouppage_link_remind, firstName),
                    onClick = { onEvent(GroupSettingsEvent.Remind(sheet.memberId)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = sheet.canRemind,
                )
                AppButton(
                    label = stringResource(R.string.grouppage_link_turn_off),
                    onClick = { onEvent(GroupSettingsEvent.TurnOffLink) },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Secondary,
                )
            }
            AppButton(
                label = stringResource(R.string.grouppage_link_cancel),
                onClick = { onEvent(GroupSettingsEvent.CloseSheet) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        }
    }
}
