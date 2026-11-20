package com.expenses.app.ui.screens.settle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.Banner
import com.expenses.app.ui.components.BannerTone
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.OptionCard
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space

/**
 * Settle up (design 03-getting-paid-back/00-settle-up; 07-settle-up-no-upi-id when the friend has no UPI ID or is not
 * on the app): the friend's avatar, "You owe Rahul", the amount and "4 expenses · Flat 302"; the Option Cards "Pay by
 * UPI" and "Paid another way"; "It clears itself"; and "Pay ₹1,200" or "Mark ₹1,200 as paid" in the footer.
 *
 * @param state what to show; [SettleUpUiState.Gone] and [SettleUpUiState.Done] draw the empty frame while the
 * caller leaves.
 * @param onEvent called with what the person did.
 * @param modifier outer modifier.
 */
@Composable
fun SettleUpScreen(state: SettleUpUiState, onEvent: (SettleUpEvent) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.settle_title)
    val back = { onEvent(SettleUpEvent.Back) }
    when (state) {
        SettleUpUiState.Loading, SettleUpUiState.Gone, SettleUpUiState.Done -> PageFrame(title = title, onBack = back, modifier = modifier) {}
        is SettleUpUiState.Shown -> SettleUpContent(state, title, onEvent, modifier)
    }
}

@Composable
private fun SettleUpContent(state: SettleUpUiState.Shown, title: String, onEvent: (SettleUpEvent) -> Unit, modifier: Modifier) {
    val amount = formatRupees(state.amountMinor)
    val payByUpi = state.upiId != null
    PageFrame(
        title = title,
        onBack = { onEvent(SettleUpEvent.Back) },
        modifier = modifier,
        footer = {
            AppButton(
                label = if (state.choice == SettleChoice.Upi) {
                    stringResource(R.string.settle_pay, amount)
                } else {
                    stringResource(R.string.settle_mark_paid, amount)
                },
                onClick = { onEvent(SettleUpEvent.Confirm) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        if (state.noUpiApp) {
            Banner(
                title = stringResource(R.string.settle_no_upi_app_title),
                subtitle = stringResource(R.string.settle_no_upi_app_body, state.name),
                icon = AppIcon.Phone,
                tone = BannerTone.Notice,
            )
        }
        SettleAmountHeader(
            initial = state.initial,
            avatarColor = state.avatar,
            avatarSize = SettleLargeAvatar,
            heading = stringResource(R.string.settle_you_owe, state.name),
            amount = amount,
            tag = expensesTag(state),
        )
        if (!payByUpi) {
            // 07: no UPI ID for the friend, so only "Paid another way".
            InfoRow(
                title = stringResource(R.string.settle_no_upi_title, state.name),
                subtitle = stringResource(R.string.settle_no_upi_body, state.name),
                icon = AppIcon.Phone,
            )
        }
        Column(modifier = Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            if (payByUpi) {
                OptionCard(
                    title = stringResource(R.string.settle_upi_title),
                    subtitle = stringResource(R.string.settle_upi_subtitle),
                    icon = AppIcon.Phone,
                    selected = state.choice == SettleChoice.Upi,
                    onClick = { onEvent(SettleUpEvent.Choose(SettleChoice.Upi)) },
                )
            }
            OptionCard(
                title = stringResource(R.string.settle_other_title),
                subtitle = if (state.friendOnApp) {
                    stringResource(R.string.settle_other_subtitle, state.name)
                } else {
                    stringResource(R.string.settle_other_subtitle_no_app)
                },
                icon = AppIcon.Cash,
                selected = state.choice == SettleChoice.Other,
                onClick = { onEvent(SettleUpEvent.Choose(SettleChoice.Other)) },
            )
        }
        if (payByUpi) {
            InfoRow(
                title = stringResource(R.string.settle_clears_title),
                subtitle = stringResource(R.string.settle_clears_body),
                icon = AppIcon.Sparkle,
            )
        }
    }
}

/** "4 expenses · Flat 302"; "4 expenses" for a direct split; only the group when no expense is counted. */
@Composable
private fun expensesTag(state: SettleUpUiState.Shown): String? = when {
    state.expenseCount == 0 -> state.groupName
    state.groupName == null -> pluralStringResource(R.plurals.settle_expenses, state.expenseCount, state.expenseCount)
    else -> pluralStringResource(R.plurals.settle_expenses_in_group, state.expenseCount, state.expenseCount, state.groupName)
}
