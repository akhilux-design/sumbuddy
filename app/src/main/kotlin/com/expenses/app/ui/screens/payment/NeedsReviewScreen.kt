package com.expenses.app.ui.screens.payment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppInputState
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.ExpenseRow
import com.expenses.app.ui.components.ExpenseRowKind
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.components.SurfaceCardStyle
import com.expenses.app.ui.components.Tag
import com.expenses.app.ui.components.TagTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.shakeOn
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/** Outline of the card being checked, as in the design (1.5, `color/accent/yellow`). */
private val CheckCardStroke = 1.5.dp

/**
 * Needs review (design 02-everyday/19-needs-review): bank messages that could not be read for sure. The open one
 * is a card with the message, the amount (filled in when the text has one), "Paid to", Save and Not a spend; the
 * others are folded rows marked "Check this" that open when tapped. With nothing left, an empty state says so.
 *
 * @param state what to show; see [NeedsReviewUiState].
 * @param onEvent called with what the person did; see [NeedsReviewEvent].
 * @param modifier outer modifier. The screen fills the space it is given and keeps clear of the system bars.
 */
@Composable
fun NeedsReviewScreen(
    state: NeedsReviewUiState,
    onEvent: (NeedsReviewEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PaymentPage(
        title = stringResource(R.string.payment_review_title),
        onBack = { onEvent(NeedsReviewEvent.Back) },
        modifier = modifier,
    ) {
        if (state !is NeedsReviewUiState.Shown) return@PaymentPage
        state.added?.let { added ->
            SurfaceCard(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = SurfaceCardStyle.Note,
            ) {
                AppText(
                    text = stringResource(
                        when (added.outcome) {
                            ReviewOutcome.Spend -> R.string.payment_review_added
                            ReviewOutcome.MoneyIn -> R.string.payment_review_added_money_in
                            ReviewOutcome.AlreadyCounted -> R.string.payment_review_already_counted
                        },
                        formatRupees(added.amountMinor),
                        monthName(added.month),
                    ),
                    style = AppTheme.type.bodyM,
                    color = AppTheme.colors.textPrimary,
                )
            }
        }
        if (state.items.isEmpty()) {
            EmptyState(
                icon = AppIcon.CheckCircle,
                title = stringResource(R.string.payment_review_empty_title),
                body = stringResource(R.string.payment_review_empty_body),
                modifier = Modifier.padding(top = Space.s40),
            )
            return@PaymentPage
        }
        AppText(
            text = pluralStringResource(R.plurals.payment_review_intro, state.items.size, state.items.size),
            style = AppTheme.type.bodyM,
            color = AppTheme.colors.textSecondary,
        )
        state.items.forEach { entry ->
            val form = state.form
            if (form != null && form.itemId == entry.id) {
                CheckCard(entry = entry, form = form, onEvent = onEvent)
            } else {
                ExpenseRow(
                    title = stringResource(R.string.payment_unknown_merchant),
                    subtitle = stringResource(
                        R.string.payment_review_row_subtitle,
                        entry.sender,
                        shortDate(entry.date, state.today),
                    ),
                    amount = entry.amountMinor?.let(::formatRupees)
                        ?: stringResource(R.string.payment_review_amount_unknown),
                    meta = stringResource(R.string.payment_review_check_this),
                    icon = AppIcon.Help,
                    kind = ExpenseRowKind.Review,
                    onClick = { onEvent(NeedsReviewEvent.Open(entry.id)) },
                )
            }
        }
    }
}

/** The open message: the tag, the text in quotes, the two fields and Save / Not a spend. */
@Composable
private fun CheckCard(entry: ReviewEntry, form: ReviewForm, onEvent: (NeedsReviewEvent) -> Unit) {
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppTheme.colors.bgSurface)
            .border(CheckCardStroke, AppTheme.colors.accentYellow, shape)
            .padding(Space.s16),
        verticalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        Tag(label = stringResource(R.string.payment_review_check_this), tone = TagTone.Yellow)
        AppText(
            text = stringResource(R.string.payment_review_quote, entry.body),
            style = AppTheme.type.caption,
            color = AppTheme.colors.textSecondary,
        )
        AppInput(
            value = form.amountText,
            onValueChange = { onEvent(NeedsReviewEvent.AmountChanged(it)) },
            label = stringResource(R.string.payment_input_amount),
            // A refused Save shakes the field and plays the reject haptic.
            modifier = Modifier
                .fillMaxWidth()
                .shakeOn(form.refusedSubmits),
            placeholder = stringResource(R.string.payment_review_amount_placeholder),
            helper = stringResource(R.string.payment_error_amount),
            state = if (form.amountError) AppInputState.Error else AppInputState.Default,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        )
        AppInput(
            value = form.payee,
            onValueChange = { onEvent(NeedsReviewEvent.PayeeChanged(it)) },
            label = stringResource(R.string.payment_input_paid_to),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
            AppButton(
                label = stringResource(R.string.payment_review_save),
                onClick = { onEvent(NeedsReviewEvent.Save) },
                modifier = Modifier.weight(1f),
                size = ButtonSize.Medium,
                enabled = !form.saving,
            )
            AppButton(
                label = stringResource(R.string.payment_review_not_a_spend),
                onClick = { onEvent(NeedsReviewEvent.NotASpend) },
                modifier = Modifier.weight(1f),
                style = ButtonStyle.Secondary,
                size = ButtonSize.Medium,
                enabled = !form.saving,
            )
        }
    }
}
