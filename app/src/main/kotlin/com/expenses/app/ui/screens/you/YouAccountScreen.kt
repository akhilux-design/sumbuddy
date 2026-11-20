package com.expenses.app.ui.screens.you

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.expenses.app.R
import com.expenses.app.ui.motion.RevealValue
import com.expenses.app.ui.motion.AnimatedOverlay
import com.expenses.app.ui.motion.shakeOn
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppInputState
import com.expenses.app.ui.components.ChoiceDialog
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.screens.signin.ActionProblemBanner
import com.expenses.app.ui.screens.signin.ProfileRefusals
import com.expenses.app.ui.screens.signin.UpiIdField

/**
 * Account (design 04-you/02-account), opened from the name on You while signed in: Name, "Phone number (optional)",
 * "UPI ID (optional)" with its note, then Sign out and "Delete account and data" in a list card, and Save at the
 * bottom. The UPI ID field shows its error (07) when the text is not a UPI ID. Over it, the delete dialog (03) or the
 * unsent changes dialog (08).
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param modifier layout changes from the caller.
 * @param refusals refused saves per field: each new one shakes that field and plays the reject haptic.
 */
@Composable
fun YouAccountScreen(
    state: YouAccountUiState,
    onEvent: (YouAccountEvent) -> Unit,
    modifier: Modifier = Modifier,
    refusals: ProfileRefusals = ProfileRefusals(),
) {
    val notRight = stringResource(R.string.you_account_not_right)
    PageFrame(
        title = stringResource(R.string.you_account_title),
        onBack = { onEvent(YouAccountEvent.Back) },
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.you_account_save),
                onClick = { onEvent(YouAccountEvent.Save) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        RevealValue(state.problem) { problem ->
            ActionProblemBanner(
                problem = problem,
                offlineTitle = stringResource(R.string.you_account_offline_title),
                offlineBody = stringResource(R.string.you_account_offline_body),
                failedTitle = stringResource(R.string.you_account_failed_title),
                failedBody = stringResource(R.string.you_account_failed_body),
            )
        }
        AppInput(
            value = state.name,
            onValueChange = { onEvent(YouAccountEvent.NameChanged(it)) },
            label = stringResource(R.string.you_account_name_label),
            modifier = Modifier
                .fillMaxWidth()
                .shakeOn(refusals.name),
            helper = notRight,
            state = if (state.nameInvalid) AppInputState.Error else AppInputState.Default,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
        )
        AppInput(
            value = state.phone,
            onValueChange = { onEvent(YouAccountEvent.PhoneChanged(it)) },
            label = stringResource(R.string.you_account_phone_label),
            modifier = Modifier
                .fillMaxWidth()
                .shakeOn(refusals.phone),
            helper = notRight,
            state = if (state.phoneInvalid) AppInputState.Error else AppInputState.Default,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        )
        UpiIdField(
            value = state.upiId,
            onValueChange = { onEvent(YouAccountEvent.UpiIdChanged(it)) },
            invalid = state.upiInvalid,
            onDone = { onEvent(YouAccountEvent.Save) },
            refusedSubmits = refusals.upiId,
        )
        SurfaceCard {
            InfoRow(
                title = stringResource(R.string.you_account_sign_out_title),
                subtitle = stringResource(R.string.you_account_sign_out_body),
                icon = AppIcon.Logout,
                onClick = { onEvent(YouAccountEvent.SignOut) },
            )
            InfoRow(
                title = stringResource(R.string.you_account_delete_title),
                subtitle = stringResource(R.string.you_account_delete_body),
                icon = AppIcon.Trash,
                onClick = { onEvent(YouAccountEvent.Delete) },
            )
        }
    }
    AnimatedOverlay(state.dialog?.let { it to state.unsentCount }) { (dialog, unsentCount) ->
        when (dialog) {
            YouAccountDialog.DELETE -> ChoiceDialog(
                icon = AppIcon.Trash,
                title = stringResource(R.string.you_account_delete_confirm_title),
                body = stringResource(R.string.you_account_delete_confirm_body),
                confirmLabel = stringResource(R.string.you_account_delete_confirm),
                dismissLabel = stringResource(R.string.you_account_delete_keep),
                onConfirm = { onEvent(YouAccountEvent.ConfirmDelete) },
                onDismiss = { onEvent(YouAccountEvent.DismissDialog) },
                overlay = this,
            )
            YouAccountDialog.UNSENT_CHANGES -> {
                // Sign out could not send them, so at least one waits even before the count has caught up.
                val count = unsentCount.coerceAtLeast(1)
                ChoiceDialog(
                    icon = AppIcon.Logout,
                    title = pluralStringResource(R.plurals.you_account_unsent_title, count, count),
                    body = stringResource(R.string.you_account_unsent_body),
                    confirmLabel = stringResource(R.string.you_account_sign_out_anyway),
                    dismissLabel = stringResource(R.string.you_account_stay_signed_in),
                    onConfirm = { onEvent(YouAccountEvent.SignOutAnyway) },
                    onDismiss = { onEvent(YouAccountEvent.DismissDialog) },
                    overlay = this,
                )
            }
        }
    }
}
