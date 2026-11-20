package com.expenses.app.ui.screens.you

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.expenses.app.R
import com.expenses.app.ui.motion.FadeThroughContent
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppSwitch
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.screens.onboarding.AccountItem
import com.expenses.app.ui.screens.onboarding.accountDetail
import com.expenses.app.ui.screens.onboarding.accountKind
import com.expenses.app.ui.screens.onboarding.accountName
import com.expenses.app.ui.screens.onboarding.icon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/**
 * Accounts and cards (design 04-you/01-accounts-and-cards): a short explanation, then one row per account or card
 * with a switch for tracking it. Tapping a row renames it: the row turns into a name field with Save and Cancel.
 * Before any account was found, an empty state says when they appear.
 *
 * @param state what to show; see [AccountsUiState].
 * @param modifier outer modifier. The screen fills the space it is given.
 * @param onEvent called with what the person did; see [AccountsEvent].
 */
@Composable
fun AccountsScreen(
    state: AccountsUiState,
    modifier: Modifier = Modifier,
    onEvent: (AccountsEvent) -> Unit = {},
) {
    YouDetailFrame(
        title = stringResource(R.string.you_accounts_title),
        onBack = { onEvent(AccountsEvent.Back) },
        modifier = modifier,
    ) {
        if (state.accounts.isEmpty()) {
            EmptyState(
                icon = AppIcon.Bank,
                title = stringResource(R.string.you_accounts_empty_title),
                body = stringResource(R.string.you_accounts_empty_body),
            )
        } else {
            AppText(
                text = stringResource(R.string.you_accounts_intro),
                style = AppTheme.type.bodyM,
                modifier = Modifier.fillMaxWidth(),
                color = AppTheme.colors.textSecondary,
            )
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                state.accounts.forEach { account ->
                    // The row and its rename card swap with a fade-through, keyed by whether it is being renamed (never
                    // by the typed name, so the field keeps the keyboard).
                    val draft = state.renaming?.takeIf { it.accountId == account.id }
                    key(account.id) {
                        FadeThroughContent(targetState = draft, contentKey = { it != null }) { renaming ->
                            if (renaming != null) {
                                RenameCard(account = account, draft = renaming, onEvent = onEvent)
                            } else {
                                AccountRow(account = account, onEvent = onEvent)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One account: tapping the row renames it, the switch at the end turns tracking on or off. */
@Composable
private fun AccountRow(account: AccountItem, onEvent: (AccountsEvent) -> Unit) {
    val name = accountName(account)
    SettingRow(
        title = name,
        subtitle = accountDetail(account),
        icon = account.type.icon,
        onClick = { onEvent(AccountsEvent.StartRename(account.id)) },
        trailing = {
            AppSwitch(
                checked = account.tracked,
                onCheckedChange = { onEvent(AccountsEvent.SetTracked(account.id, it)) },
                contentDescription = stringResource(R.string.you_accounts_track, name),
            )
        },
    )
}

/**
 * The name field for one account, with Save and Cancel (design 04-you/10-accounts-and-cards-rename). A blank name goes
 * back to the bank's name, which the field shows as its placeholder, as drawn; there is no line under the field (the
 * Input has one only in its Error state, and the frame draws none).
 *
 * While the field has focus, the whole card is kept in view as the keyboard comes up, so Save and Cancel stay above
 * it; the list alone would bring only the field into view.
 */
@Composable
private fun RenameCard(account: AccountItem, draft: RenameDraft, onEvent: (AccountsEvent) -> Unit) {
    val card = remember { BringIntoViewRequester() }
    val field = remember { MutableInteractionSource() }
    val focused by field.collectIsFocusedAsState()
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, keyboard) {
        if (focused) {
            withFrameNanos { } // the list has its new height first
            card.bringIntoView()
        }
    }
    SurfaceCard(modifier = Modifier.bringIntoViewRequester(card)) {
        AppInput(
            value = draft.text,
            onValueChange = { onEvent(AccountsEvent.RenameChanged(it)) },
            label = stringResource(R.string.you_rename_label, accountDetail(account)),
            modifier = Modifier.fillMaxWidth(),
            placeholder = account.bank ?: accountKind(account.type),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onEvent(AccountsEvent.SaveRename) }),
            interactionSource = field,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
            AppButton(
                label = stringResource(R.string.you_rename_save),
                onClick = { onEvent(AccountsEvent.SaveRename) },
                modifier = Modifier.weight(1f),
                style = ButtonStyle.Primary,
                size = ButtonSize.Medium,
            )
            AppButton(
                label = stringResource(R.string.you_rename_cancel),
                onClick = { onEvent(AccountsEvent.CancelRename) },
                modifier = Modifier.weight(1f),
                style = ButtonStyle.Secondary,
                size = ButtonSize.Medium,
            )
        }
    }
}
