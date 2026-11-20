package com.expenses.app.ui.screens.split

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ExpenseRow
import com.expenses.app.ui.components.ExpenseRowKind
import com.expenses.app.ui.components.OptionCard
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.format.readAs
import com.expenses.app.ui.format.upiIdLineBreaks
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.screens.payment.merchantText
import com.expenses.app.ui.screens.payment.sourceText
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/**
 * Same split? (design 02-everyday/14-recurring-split-offer): the repeat tile, "Rent again. Same split?", the line
 * that says it was split before ("last month too" only when it was last month), the payment as a shared Expense Row,
 * and the two Option Cards "Yes, every month" and "Ask me each time". Save stores the choice.
 *
 * @param state what to show; see [SameSplitUiState]. Gone and Saved draw only the frame, as the caller leaves.
 * @param onEvent called with what the person did; see [SameSplitEvent].
 * @param modifier outer modifier. The screen fills the space it is given and keeps clear of the system bars.
 */
@Composable
fun SameSplitScreen(state: SameSplitUiState, onEvent: (SameSplitEvent) -> Unit, modifier: Modifier = Modifier) {
    BackHandler { onEvent(SameSplitEvent.Back) }
    val shown = state as? SameSplitUiState.Shown
    PageFrame(
        title = null,
        onBack = null,
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.split_same_save),
                onClick = { onEvent(SameSplitEvent.Save) },
                modifier = Modifier.fillMaxWidth(),
                enabled = shown != null && !shown.saving,
            )
        },
    ) {
        if (shown == null) return@PageFrame
        val payee = merchantText(shown.payee)
        // The design's tile starts 24dp below the status bar: 8 from the page, 4 here and the 12dp gap.
        Spacer(Modifier.height(Space.s4))
        StickerTile(icon = AppIcon.Repeat, tone = StickerTone.Yellow)
        // A payee known only by its UPI ID is shown whole and breaks only after its "@", never in the middle; screen
        // readers read the plain sentences.
        val shownPayee = upiIdLineBreaks(payee)
        val isUpiId = shownPayee != payee
        val title = stringResource(R.string.split_same_title, payee)
        AppText(
            text = stringResource(R.string.split_same_title, shownPayee),
            style = AppTheme.type.displayL,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() }
                .then(if (isUpiId) Modifier.readAs(title) else Modifier),
        )
        // "Last month too" only when it was last month (14); any other earlier split reads "before".
        val bodyText = if (shown.splitLastMonth) R.string.split_same_body else R.string.split_same_body_before
        val body = stringResource(bodyText, payee, shown.groupName)
        AppText(
            text = stringResource(bodyText, shownPayee, shown.groupName),
            style = AppTheme.type.bodyM,
            modifier = Modifier.fillMaxWidth().then(if (isUpiId) Modifier.readAs(body) else Modifier),
            color = AppTheme.colors.textSecondary,
        )
        ExpenseRow(
            title = payee,
            subtitle = stringResource(
                R.string.split_row_subtitle,
                splitDayText(shown.date, shown.today),
                sourceText(shown.account, shown.paidWith),
            ),
            amount = formatRupees(shown.amountMinor),
            meta = shown.groupName,
            icon = shown.category.icon,
            kind = ExpenseRowKind.Shared,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(Space.s12),
        ) {
            OptionCard(
                title = stringResource(R.string.split_same_yes),
                subtitle = stringResource(R.string.split_same_yes_body),
                icon = AppIcon.CheckCircle,
                selected = shown.everyMonth,
                onClick = { onEvent(SameSplitEvent.Chosen(everyMonth = true)) },
            )
            OptionCard(
                title = stringResource(R.string.split_same_ask),
                subtitle = stringResource(R.string.split_same_ask_body),
                icon = AppIcon.Pointer,
                selected = !shown.everyMonth,
                onClick = { onEvent(SameSplitEvent.Chosen(everyMonth = false)) },
            )
        }
    }
}
