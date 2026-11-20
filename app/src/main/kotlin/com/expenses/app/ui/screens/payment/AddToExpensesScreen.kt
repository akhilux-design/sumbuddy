package com.expenses.app.ui.screens.payment

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.expenses.app.R
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppSwitch
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.components.TagTone
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.format.upiIdLineBreaks
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space

/**
 * Add to expenses (design 02-everyday/05-add-to-expenses): the person confirms a payment read from SMS. The amount,
 * when and from which account are shown; "Paid to", the category (guessed) and the note can be changed, and Just me
 * or Family chosen. "Add to <month>" counts it in its month; "Split it instead" opens Split it for the payment
 * (signed out, Sign in first).
 *
 * @param state what to show; see [AddToExpensesUiState]. [AddToExpensesUiState.Added] keeps the last form drawn, with
 * its taps ignored, so it fades out under Expense added as the caller moves on ("Result arrival").
 * @param onEvent called with what the person did; see [AddToExpensesEvent].
 * @param modifier outer modifier. The screen fills the space it is given and keeps clear of the system bars.
 */
@Composable
fun AddToExpensesScreen(
    state: AddToExpensesUiState,
    onEvent: (AddToExpensesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Added: the form stays drawn (the same composition, so its Add press finishes) while Expense added arrives.
    val last = remember { LastForm() }
    if (state is AddToExpensesUiState.Form) last.form = state
    val added = state is AddToExpensesUiState.Added
    val shown = if (added) last.form ?: state else state
    val events: (AddToExpensesEvent) -> Unit = if (added) ({}) else onEvent
    when (shown) {
        AddToExpensesUiState.Loading, is AddToExpensesUiState.Added -> PaymentLoading()
        AddToExpensesUiState.Gone -> PaymentGone(onBack = { events(AddToExpensesEvent.Back) })
        is AddToExpensesUiState.AlreadySorted -> AlreadySorted(shown, events, modifier)
        is AddToExpensesUiState.Form -> AddToExpensesForm(shown, events, modifier)
    }
}

/** The last form drawn, kept for the frames of leaving. Not state: it never redraws anything. */
private class LastForm {
    var form: AddToExpensesUiState.Form? = null
}

/** The form for a payment that waits for a decision. */
@Composable
private fun AddToExpensesForm(
    state: AddToExpensesUiState.Form,
    onEvent: (AddToExpensesEvent) -> Unit,
    modifier: Modifier,
) {
    val category = stringResource(state.category.label)
    PaymentPage(
        title = stringResource(R.string.payment_add_title),
        onBack = { onEvent(AddToExpensesEvent.Back) },
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.payment_add_to_month, monthName(state.month)),
                onClick = { onEvent(AddToExpensesEvent.Add) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.saving,
            )
            AppButton(
                label = stringResource(R.string.payment_add_split_instead),
                onClick = { onEvent(AddToExpensesEvent.Split) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        AmountHeader(
            tag = stringResource(R.string.payment_tag_from_sms),
            tagTone = TagTone.Blue,
            amount = formatRupees(state.amountMinor),
            caption = whenAndSourceText(state.time, state.account, state.paidWith),
        )
        AppInput(
            value = state.payee,
            onValueChange = { onEvent(AddToExpensesEvent.PayeeChanged(it)) },
            label = stringResource(R.string.payment_input_paid_to),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
        )
        CategoryPicker(
            selected = state.category,
            onSelect = { onEvent(AddToExpensesEvent.CategoryChosen(it)) },
            onMore = { onEvent(AddToExpensesEvent.MoreCategories) },
        )
        ScopePicker(selected = state.scope, onSelect = { onEvent(AddToExpensesEvent.ScopeChosen(it)) })
        AppInput(
            value = state.note,
            onValueChange = { onEvent(AddToExpensesEvent.NoteChanged(it)) },
            label = stringResource(R.string.payment_input_note),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
        )
        val payee = state.payee.trim()
        SettingRow(
            title = stringResource(R.string.payment_add_always_title),
            subtitle = if (payee.isEmpty()) {
                stringResource(R.string.payment_add_always_subtitle_no_merchant)
            } else {
                // A payee known only by its UPI ID breaks only after its "@" (the row reads it without the marks). With
                // Family chosen the rule remembers that too: "Swiggy is always Food · Family" (06-family/25).
                stringResource(
                    if (state.scope == ExpenseScope.FAMILY) R.string.payment_add_always_subtitle_family else R.string.payment_add_always_subtitle,
                    upiIdLineBreaks(payee),
                    category,
                )
            },
            icon = AppIcon.Repeat,
            checked = state.alwaysThisCategory,
            onCheckedChange = { onEvent(AddToExpensesEvent.AlwaysThisCategoryChanged(it)) },
            trailing = { AppSwitch(checked = state.alwaysThisCategory, onCheckedChange = null) },
        )
    }
}

/** The payment was added or ignored before this screen opened, for example from the notification. */
@Composable
private fun AlreadySorted(
    state: AddToExpensesUiState.AlreadySorted,
    onEvent: (AddToExpensesEvent) -> Unit,
    modifier: Modifier,
) {
    PaymentPage(
        title = stringResource(R.string.payment_add_title),
        onBack = { onEvent(AddToExpensesEvent.Back) },
        modifier = modifier,
    ) {
        EmptyState(
            icon = AppIcon.CheckCircle,
            title = stringResource(R.string.payment_add_sorted_title),
            body = stringResource(
                if (state.added) R.string.payment_add_sorted_body_added else R.string.payment_add_sorted_body_ignored,
            ),
            modifier = Modifier.padding(top = Space.s16),
        ) {
            if (state.added) {
                AppButton(
                    label = stringResource(R.string.payment_add_see_expense),
                    onClick = { onEvent(AddToExpensesEvent.SeeExpense) },
                    style = ButtonStyle.Secondary,
                    size = ButtonSize.Medium,
                )
            }
        }
    }
}
