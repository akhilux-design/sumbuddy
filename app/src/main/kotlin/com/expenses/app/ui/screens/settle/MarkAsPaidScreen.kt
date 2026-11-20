package com.expenses.app.ui.screens.settle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.OptionCard
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space

/**
 * Mark as paid (design 03-getting-paid-back/01-mark-as-paid): the friend's avatar, "Rahul paid you", the amount and
 * the group; the Option Cards "Cash" and "Bank transfer or other"; "Rahul can confirm" when the friend is on the app;
 * and "Mark ₹1,200 as paid" in the footer.
 *
 * @param state what to show; [MarkAsPaidUiState.Gone] and [MarkAsPaidUiState.Done] draw the empty frame while the
 * caller leaves.
 * @param onEvent called with what the person did.
 * @param modifier outer modifier.
 */
@Composable
fun MarkAsPaidScreen(state: MarkAsPaidUiState, onEvent: (MarkAsPaidEvent) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.settle_mark_paid_title)
    when (state) {
        MarkAsPaidUiState.Loading, MarkAsPaidUiState.Gone, MarkAsPaidUiState.Done ->
            PageFrame(title = title, onBack = { onEvent(MarkAsPaidEvent.Back) }, modifier = modifier) {}
        is MarkAsPaidUiState.Shown -> MarkAsPaidContent(state, title, onEvent, modifier)
    }
}

@Composable
private fun MarkAsPaidContent(state: MarkAsPaidUiState.Shown, title: String, onEvent: (MarkAsPaidEvent) -> Unit, modifier: Modifier) {
    val amount = formatRupees(state.amountMinor)
    PageFrame(
        title = title,
        onBack = { onEvent(MarkAsPaidEvent.Back) },
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.settle_mark_paid, amount),
                onClick = { onEvent(MarkAsPaidEvent.Confirm) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        SettleAmountHeader(
            initial = state.initial,
            avatarColor = state.avatar,
            avatarSize = SettleSmallAvatar,
            heading = stringResource(R.string.settle_paid_you, state.name),
            amount = amount,
            tag = state.groupName,
        )
        Column(modifier = Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            OptionCard(
                title = stringResource(R.string.settle_cash_title),
                subtitle = stringResource(R.string.settle_cash_subtitle, state.name),
                icon = AppIcon.Cash,
                selected = state.how == PaidHow.Cash,
                onClick = { onEvent(MarkAsPaidEvent.Choose(PaidHow.Cash)) },
            )
            OptionCard(
                title = stringResource(R.string.settle_bank_title),
                subtitle = stringResource(R.string.settle_bank_subtitle),
                icon = AppIcon.Bank,
                selected = state.how == PaidHow.Other,
                onClick = { onEvent(MarkAsPaidEvent.Choose(PaidHow.Other)) },
            )
        }
        if (state.canConfirm) {
            InfoRow(
                title = stringResource(R.string.settle_can_confirm_title, state.name),
                subtitle = stringResource(R.string.settle_can_confirm_body),
                icon = AppIcon.CheckCircle,
            )
        }
    }
}
