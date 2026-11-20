package com.expenses.app.ui.screens.settle

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.BalanceRow
import com.expenses.app.ui.components.BalanceRowDirection
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.keptWhileLeaving
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/** Everything the person can do on All square. */
sealed interface BalanceClearedEvent {
    /** "Nice": back to Home. */
    data object Nice : BalanceClearedEvent
}

/**
 * All square (design 03-getting-paid-back/03-balance-cleared): the tick, "All square with Rahul", "The ₹1,200 arrived
 * by UPI and the balance closed by itself.", the friend's Balance Row over every group ("all settled ₹0"), "Nothing to
 * tick", and "Nice" back to Home. No top bar, as in the design.
 *
 * @param state what to show; [BalanceClearedUiState.Gone] keeps the last page drawn, without acting on taps, while the
 * caller goes to Home (nothing when there was none).
 * @param onEvent called with what the person did.
 * @param modifier outer modifier.
 */
@Composable
fun BalanceClearedScreen(state: BalanceClearedUiState, onEvent: (BalanceClearedEvent) -> Unit, modifier: Modifier = Modifier) {
    val leaving = state == BalanceClearedUiState.Gone
    val shown = keptWhileLeaving(state, leaving) { it is BalanceClearedUiState.Shown } as? BalanceClearedUiState.Shown
    if (shown == null) {
        PageFrame(title = null, onBack = null, modifier = modifier) {}
        return
    }
    val amount = formatRupees(shown.amountMinor)
    PageFrame(
        title = null,
        onBack = null,
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.settle_cleared_nice),
                onClick = { if (!leaving) onEvent(BalanceClearedEvent.Nice) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        // The design's tile starts 40dp below the status bar: 8 from the page, 20 here and the 12dp gap.
        Spacer(Modifier.height(Space.s20))
        StickerTile(icon = AppIcon.CheckCircle, tone = StickerTone.Brand, arrival = true)
        AppText(
            text = stringResource(R.string.settle_cleared_title, shown.name),
            style = AppTheme.type.displayL,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        AppText(
            text = stringResource(if (shown.byUpi) R.string.settle_cleared_body_upi else R.string.settle_cleared_body, amount),
            style = AppTheme.type.bodyL,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
        )
        val net = shown.friendNetMinor
        BalanceRow(
            name = shown.name,
            detail = stringResource(
                when {
                    net > 0 -> R.string.settle_cleared_owes_you
                    net < 0 -> R.string.settle_cleared_you_owe
                    else -> R.string.settle_cleared_settled
                },
            ),
            // "+₹1,200" as on Money received; formatRupees writes "-₹800" and "₹0" itself.
            amount = if (net > 0) stringResource(R.string.settle_match_amount, formatRupees(net)) else formatRupees(net),
            initial = shown.initial,
            direction = when {
                net > 0 -> BalanceRowDirection.OwesYou
                net < 0 -> BalanceRowDirection.YouOwe
                else -> BalanceRowDirection.Settled
            },
            avatarColor = shown.avatar,
        )
        InfoRow(
            title = stringResource(R.string.settle_cleared_nothing_title),
            subtitle = stringResource(R.string.settle_cleared_nothing_body, shown.name),
            icon = AppIcon.Sparkle,
        )
    }
}
