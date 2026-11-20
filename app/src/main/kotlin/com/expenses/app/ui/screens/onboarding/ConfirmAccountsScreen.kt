package com.expenses.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.expenses.app.R
import com.expenses.app.ui.motion.AddedRow
import com.expenses.app.ui.motion.rememberFirstKeys
import androidx.compose.runtime.key
import com.expenses.app.ui.motion.AnimatedOverlay
import com.expenses.app.ui.motion.OverlayScope
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppSwitch
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.DialogCard
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.components.TopBar
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.faceHeightOnly
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/**
 * The account being renamed on Confirm accounts.
 *
 * @param account the account.
 * @param text the new name typed so far.
 */
@Immutable
data class RenameDraft(val account: AccountItem, val text: String)

/**
 * What Confirm accounts shows.
 *
 * @param accounts every account the scan found, oldest first.
 * @param renaming the account being renamed, or null when the rename dialog is closed.
 */
@Immutable
data class ConfirmAccountsUiState(
    val accounts: List<AccountItem> = emptyList(),
    val renaming: RenameDraft? = null,
)

/** What the person does on Confirm accounts. */
sealed interface ConfirmAccountsEvent {
    /** They switched tracking of account [id] to [tracked]. */
    data class SetTracked(val id: Long, val tracked: Boolean) : ConfirmAccountsEvent

    /** They tapped account [id] to rename it. */
    data class StartRename(val id: Long) : ConfirmAccountsEvent

    /** They typed in the rename dialog. */
    data class RenameChanged(val text: String) : ConfirmAccountsEvent

    /** Save in the rename dialog. A blank name goes back to the bank's name. */
    data object SaveRename : ConfirmAccountsEvent

    /** Cancel in the rename dialog, or the dialog was dismissed. */
    data object CancelRename : ConfirmAccountsEvent

    /** Looks good: onboarding is finished. */
    data object Done : ConfirmAccountsEvent
}

/**
 * Confirm accounts (design 01-onboarding/11-confirm-accounts), step 3 of 3: the accounts and cards found in the
 * SMS, each with a switch to stop tracking it. Tapping an account opens the rename dialog (15), typed with the system
 * keyboard.
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param onBack called by the back button.
 * @param modifier layout changes from the caller.
 */
@Composable
fun ConfirmAccountsScreen(
    state: ConfirmAccountsUiState,
    onEvent: (ConfirmAccountsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingFrame(
        modifier = modifier,
        topBar = { TopBar(title = stringResource(R.string.onboarding_step_3), onBack = onBack) },
        footer = {
            AppButton(
                label = stringResource(R.string.onboarding_accounts_done),
                onClick = { onEvent(ConfirmAccountsEvent.Done) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        OnboardingHeadline(
            text = pluralStringResource(R.plurals.onboarding_accounts_title, state.accounts.size, state.accounts.size),
        )
        OnboardingBody(text = stringResource(R.string.onboarding_accounts_body))
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s8),
        ) {
val firstAccounts = rememberFirstKeys(state.accounts.map { it.id })
            state.accounts.forEach { account ->
                val name = accountName(account)
                key(account.id) { AddedRow(added = account.id !in firstAccounts) {
                    SettingRow(
                        title = name,
                        subtitle = accountDetail(account),
                        icon = account.type.icon,
                        onClick = { onEvent(ConfirmAccountsEvent.StartRename(account.id)) },
                        trailing = {
                            AppSwitch(
                                checked = account.tracked,
                                onCheckedChange = { onEvent(ConfirmAccountsEvent.SetTracked(account.id, it)) },
                                contentDescription = name,
                            )
                        },
                    )
                } }
            }
        }
    }
    // A Save refused by validation keeps renaming set, so the dialog simply stays (no callback waits for its exit).
    AnimatedOverlay(state.renaming) { draft -> RenameAccountDialog(draft = draft, onEvent = onEvent, overlay = this) }
}

/**
 * The dialog that renames one account (design 01-onboarding/15-confirm-accounts-rename): on the design's dialog card,
 * the account's name with its last digits, a name field typed with the system keyboard, Save and Cancel. No line
 * under the field: the frame draws none, and the Input has one only in its Error state.
 */
@Composable
private fun RenameAccountDialog(draft: RenameDraft, onEvent: (ConfirmAccountsEvent) -> Unit, overlay: OverlayScope) {
    val heading = accountNameWithDigits(draft.account)
    val save = overlay.guard { onEvent(ConfirmAccountsEvent.SaveRename) }
    val cancel = overlay.guard { onEvent(ConfirmAccountsEvent.CancelRename) }
    DialogCard(
        title = heading,
        onDismiss = cancel,
        horizontalAlignment = Alignment.Start,
        overlay = overlay,
    ) {
        AppText(
            text = heading,
            style = AppTheme.type.headingM,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
            color = AppTheme.colors.textPrimary,
        )
        AppInput(
            value = draft.text,
            onValueChange = { onEvent(ConfirmAccountsEvent.RenameChanged(it)) },
            label = stringResource(R.string.onboarding_accounts_rename_label, accountName(draft.account)),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
        // The frame fixes the two Button instances at the height of their faces (52 and 44), so Save's edge hangs
        // into the 16 under it and the card ends 24 under Cancel's face.
        AppButton(
            label = stringResource(R.string.onboarding_accounts_rename_save),
            onClick = save,
            modifier = Modifier.fillMaxWidth().faceHeightOnly(),
        )
        AppButton(
            label = stringResource(R.string.onboarding_accounts_rename_cancel),
            onClick = cancel,
            modifier = Modifier.fillMaxWidth().faceHeightOnly(),
            style = ButtonStyle.Ghost,
            size = ButtonSize.Medium,
        )
    }
}
