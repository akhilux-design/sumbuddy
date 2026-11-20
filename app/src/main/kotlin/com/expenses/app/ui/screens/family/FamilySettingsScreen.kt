package com.expenses.app.ui.screens.family

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
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.AnimatedOverlay
import com.expenses.app.ui.motion.OverlayScope
import com.expenses.app.ui.motion.RevealValue
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/** Space between the rows under People (`space/8`); the body puts 12 between its other parts. */
private val PeopleRowGap = Space.s8

/**
 * Family settings (design 06-family/17): "Family name", People with the person and the partner, and Leaving with Leave
 * Family. The partner's row is "Your partner" / "Invite sent · not joined yet" while an invite is out (18), or "Invite
 * partner" when there is neither (19). The invite sheet (20), the leave (21) and remove (22) confirms, and the Offline
 * (23) and Problem (24) banners. No Add someone and no Delete: a family ends when its last member leaves.
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param modifier layout changes from the caller.
 */
@Composable
fun FamilySettingsScreen(state: FamilySettingsUiState, onEvent: (FamilySettingsEvent) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.family_title)
    val back = { onEvent(FamilySettingsEvent.Back) }
    when (state) {
        FamilySettingsUiState.Loading, FamilySettingsUiState.Gone, FamilySettingsUiState.Left ->
            PageFrame(title = title, onBack = back, modifier = modifier) {}
        is FamilySettingsUiState.Shown -> Box(modifier = modifier.fillMaxSize()) {
            PageFrame(
                title = title,
                onBack = back,
                // While the invite sheet is open, screen readers see only the sheet.
                modifier = if (state.inviteSheet) Modifier.clearAndSetSemantics {} else Modifier,
            ) { SettingsBody(state, onEvent) }
            if (state.inviteSheet) BackHandler { onEvent(FamilySettingsEvent.CloseSheet) }
            AnimatedOverlay(state.familyName.takeIf { state.inviteSheet }) { familyName -> InviteSheet(familyName, onEvent, overlay = this) }
            AnimatedOverlay(state.confirm?.let { it to state.familyName }) { (confirm, familyName) ->
                when (confirm) {
                    FamilyConfirm.Leave -> ChoiceDialog(
                        icon = AppIcon.Logout,
                        title = stringResource(R.string.family_leave_confirm_title, familyName),
                        body = stringResource(R.string.family_leave_confirm_body, familyName),
                        confirmLabel = stringResource(R.string.family_leave_confirm),
                        dismissLabel = stringResource(R.string.family_leave_stay),
                        onConfirm = { onEvent(FamilySettingsEvent.Confirm) },
                        onDismiss = { onEvent(FamilySettingsEvent.CloseConfirm) },
                        overlay = this,
                    )
                    is FamilyConfirm.Remove -> ChoiceDialog(
                        icon = AppIcon.User,
                        title = stringResource(R.string.family_remove_confirm_title, confirm.firstName, familyName),
                        body = stringResource(R.string.family_remove_confirm_body, confirm.firstName, familyName),
                        confirmLabel = stringResource(R.string.family_remove_confirm),
                        dismissLabel = stringResource(R.string.family_remove_keep),
                        onConfirm = { onEvent(FamilySettingsEvent.Confirm) },
                        onDismiss = { onEvent(FamilySettingsEvent.CloseConfirm) },
                        overlay = this,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsBody(state: FamilySettingsUiState.Shown, onEvent: (FamilySettingsEvent) -> Unit) {
    RevealValue(state.problem) { problem -> FamilyProblemBanner(problem) }
    NameField(state, onEvent)
    SectionLabel(text = stringResource(R.string.family_people))
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PeopleRowGap)) {
        SettingRow(title = state.me, subtitle = stringResource(R.string.family_you), icon = AppIcon.User)
        val tap = { onEvent(FamilySettingsEvent.PartnerTapped) }
        when (val partner = state.partner) {
            is FamilyPartnerRow.Joined -> SettingRow(
                title = partner.name,
                subtitle = stringResource(R.string.family_partner),
                icon = AppIcon.User,
                // Only the owner removes; for the partner the row only shows who is in.
                onClick = if (partner.canRemove) tap else null,
            )
            FamilyPartnerRow.Invited -> SettingRow(
                title = stringResource(R.string.family_invited_title),
                subtitle = stringResource(R.string.family_invited_subtitle),
                icon = AppIcon.UserPlus,
                onClick = tap,
            )
            FamilyPartnerRow.None -> SettingRow(
                title = stringResource(R.string.family_invite_row_title),
                subtitle = stringResource(R.string.family_invite_row_subtitle),
                icon = AppIcon.Plus,
                onClick = tap,
            )
        }
    }
    SectionLabel(text = stringResource(R.string.family_leaving))
    SurfaceCard {
        InfoRow(
            title = stringResource(R.string.family_leave_title),
            subtitle = stringResource(R.string.family_leave_subtitle, state.familyName),
            icon = AppIcon.Logout,
            onClick = { onEvent(FamilySettingsEvent.Leave) },
        )
    }
}

/** "Family name": saved when the person leaves the field (focus moves away, or Done on the keyboard) or the screen. */
@Composable
private fun NameField(state: FamilySettingsUiState.Shown, onEvent: (FamilySettingsEvent) -> Unit) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    AppInput(
        value = state.nameField,
        onValueChange = { onEvent(FamilySettingsEvent.NameChanged(it)) },
        label = stringResource(R.string.family_name_label),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
            if (focused && !focus.isFocused) onEvent(FamilySettingsEvent.NameDone)
            focused = focus.isFocused
        },
        helper = stringResource(R.string.family_name_invalid),
        state = if (state.nameError) AppInputState.Error else AppInputState.Default,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
    )
}

/**
 * The invite sheet (06-family/20): "Your partner", "Link is on" / "Anyone with it can join Home as your partner.", the
 * note, then "Send link again", "Cancel invite" and "Close".
 */
@Composable
private fun InviteSheet(familyName: String, onEvent: (FamilySettingsEvent) -> Unit, overlay: OverlayScope) {
    BottomSheet(
        title = stringResource(R.string.family_invited_title),
        onDismiss = { onEvent(FamilySettingsEvent.CloseSheet) },
        overlay = overlay,
    ) {
        SurfaceCard {
            InfoRow(
                title = stringResource(R.string.family_sheet_link_title),
                subtitle = stringResource(R.string.family_sheet_link_body, familyName),
                icon = AppIcon.Link,
            )
        }
        AppText(
            text = stringResource(R.string.family_sheet_note),
            style = AppTheme.type.bodyM,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
        )
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            AppButton(
                label = stringResource(R.string.family_sheet_send),
                onClick = { onEvent(FamilySettingsEvent.SendLinkAgain) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Primary,
                size = ButtonSize.Large,
            )
            AppButton(
                label = stringResource(R.string.family_sheet_cancel_invite),
                onClick = { onEvent(FamilySettingsEvent.CancelInvite) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Secondary,
                size = ButtonSize.Large,
            )
            AppButton(
                label = stringResource(R.string.family_sheet_close),
                onClick = { onEvent(FamilySettingsEvent.CloseSheet) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        }
    }
}
