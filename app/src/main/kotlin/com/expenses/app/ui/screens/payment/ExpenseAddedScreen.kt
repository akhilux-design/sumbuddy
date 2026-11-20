package com.expenses.app.ui.screens.payment

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.expenses.app.R
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.ExpenseRow
import com.expenses.app.ui.components.StatTile
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.format.readAs
import com.expenses.app.ui.format.upiIdLineBreaks
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.keptWhileLeaving
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/**
 * Expense added (design 02-everyday/07-expense-added): a success page with the tick, "Added to <month>", what the
 * month adds up to now and the expense as a row. "Done" goes back to Home; "Undo" takes the Add back.
 *
 * @param state what to show; see [ExpenseAddedUiState]. [ExpenseAddedUiState.Undone] keeps the last page drawn,
 * without acting on taps, as the caller leaves the screen.
 * @param onEvent called with what the person did; see [ExpenseAddedEvent].
 * @param modifier outer modifier. The screen fills the space it is given and keeps clear of the system bars.
 */
@Composable
fun ExpenseAddedScreen(
    state: ExpenseAddedUiState,
    onEvent: (ExpenseAddedEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val leaving = state is ExpenseAddedUiState.Undone
    when (val drawn = keptWhileLeaving(state, leaving) { it is ExpenseAddedUiState.Shown }) {
        ExpenseAddedUiState.Loading, is ExpenseAddedUiState.Undone -> PaymentLoading()
        ExpenseAddedUiState.Gone -> PaymentGone(onBack = { onEvent(ExpenseAddedEvent.Done) })
        is ExpenseAddedUiState.Shown -> ExpenseAddedContent(drawn, if (leaving) ({ }) else onEvent, modifier)
    }
}

@Composable
private fun ExpenseAddedContent(
    state: ExpenseAddedUiState.Shown,
    onEvent: (ExpenseAddedEvent) -> Unit,
    modifier: Modifier,
) {
    val month = monthName(state.month)
    val amount = formatRupees(state.amountMinor)
    val merchant = state.merchant?.takeIf(String::isNotBlank)
    val category = stringResource(state.category.label)
    PaymentPage(
        title = null,
        onBack = null,
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.payment_added_done),
                onClick = { onEvent(ExpenseAddedEvent.Done) },
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                label = stringResource(R.string.payment_added_undo),
                onClick = { onEvent(ExpenseAddedEvent.Undo) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        // The design's tile starts 40dp below the status bar: 8 from the page, 20 here and the 12dp gap.
        Spacer(Modifier.height(Space.s20))
        StickerTile(icon = AppIcon.CheckCircle, tone = StickerTone.Brand, arrival = true)
        AppText(
            text = stringResource(R.string.payment_added_title, month),
            style = AppTheme.type.displayL,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() },
        )
        // A payee known only by its UPI ID is shown whole and breaks only after its "@", never in the middle; screen
        // readers read the plain sentence.
        val body = addedBody(state, amount, merchant)
        val shownBody = addedBody(state, amount, merchant?.let(::upiIdLineBreaks))
        AppText(
            text = shownBody,
            style = AppTheme.type.bodyL,
            modifier = Modifier.fillMaxWidth().then(if (shownBody != body) Modifier.readAs(body) else Modifier),
            color = AppTheme.colors.textSecondary,
        )
        StatTile(
            label = stringResource(R.string.payment_added_month_so_far, month),
            value = formatRupees(state.monthTotalMinor),
            modifier = Modifier.fillMaxWidth(),
        )
        ExpenseRow(
            title = merchant ?: category,
            subtitle = stringResource(R.string.payment_row_subtitle, category, sourceText(state.account, state.paidWith)),
            amount = amount,
            meta = stringResource(
                when (state.scope) {
                    ExpenseScope.ME -> R.string.payment_added_meta_me
                    ExpenseScope.FAMILY -> R.string.payment_added_meta_family
                },
            ),
            icon = state.category.icon,
            onClick = { onEvent(ExpenseAddedEvent.OpenExpense) },
        )
    }
}

/**
 * "₹450 at Swiggy is in your expenses."; only the amount when the payment names nobody.
 *
 * @param amount the amount as shown.
 * @param merchant the payee as it is to be written, or null for none.
 */
@Composable
private fun addedBody(state: ExpenseAddedUiState.Shown, amount: String, merchant: String?): String = stringResource(
    R.string.payment_added_body,
    if (merchant != null) stringResource(state.payeeWording.amountAndPayee, amount, merchant) else amount,
)
