package com.expenses.app.ui.screens.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppChip
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppSwitch
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.icons.LineIcon
import com.expenses.app.ui.motion.Reveal
import com.expenses.app.ui.motion.shakeOn
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/**
 * Add expense (design 02-everyday/15-add-expense): the amount in large type with the number keyboard, what it was
 * for, who paid, how it was paid and "Split this", as drawn; then the category (guessed from what it was for), the
 * day (system date picker) and Just me or Family. "Add to <month>" stores it; with "Split this" on, "Next: choose
 * split" opens Split it.
 *
 * Not designed: the category, the day and Just me or Family, below the designed parts (the design draws only the split
 * case, without them); Just me or Family is left out while "Split this" is on (a split counts the person's share in
 * their own month), and "Paid with" while someone else paid.
 *
 * @param state what to show; see [AddExpenseUiState].
 * @param onEvent called with what the person did; see [AddExpenseEvent].
 * @param modifier outer modifier. The screen fills the space it is given and keeps clear of the system bars.
 * @param focusAmount true opens the screen with the amount field focused and the keyboard up; previews pass false.
 */
@Composable
fun AddExpenseScreen(
    state: AddExpenseUiState,
    onEvent: (AddExpenseEvent) -> Unit,
    modifier: Modifier = Modifier,
    focusAmount: Boolean = true,
) {
    PaymentPage(
        title = stringResource(R.string.payment_manual_title),
        onBack = { onEvent(AddExpenseEvent.Back) },
        modifier = modifier,
        footer = {
            if (state.splitThis) {
                AppButton(
                    label = stringResource(R.string.payment_manual_next),
                    onClick = { onEvent(AddExpenseEvent.Next) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.saving,
                )
            } else {
                AppButton(
                    label = stringResource(R.string.payment_add_to_month, monthName(state.month)),
                    onClick = { onEvent(AddExpenseEvent.Add) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.saving,
                )
            }
        },
    ) {
        AmountField(
            text = state.amountText,
            showError = state.amountError,
            refusedSubmits = state.refusedSubmits,
            onChange = { onEvent(AddExpenseEvent.AmountChanged(it)) },
            focusOnStart = focusAmount,
        )
        AppInput(
            value = state.whatFor,
            onValueChange = { onEvent(AddExpenseEvent.WhatForChanged(it)) },
            label = stringResource(R.string.payment_manual_what_for),
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.payment_manual_what_for_placeholder),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
        )
        SectionLabel(text = stringResource(R.string.payment_manual_paid_by))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s12),
        ) {
            AppChip(
                label = stringResource(R.string.payment_manual_you),
                selected = state.paidBy == null,
                onClick = { onEvent(AddExpenseEvent.PaidByChosen(null)) },
            )
            state.payers.forEach { payer ->
                AppChip(
                    label = payer.name,
                    selected = payer.memberId == state.paidBy,
                    onClick = { onEvent(AddExpenseEvent.PaidByChosen(payer.memberId)) },
                )
            }
        }
        // Sections that come and go with a choice open and close in place (Reveal); one there at first just shows.
        Reveal(visible = !state.someoneElsePaid) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                SectionLabel(text = stringResource(R.string.payment_manual_paid_with))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(Space.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ManualPaidWith.forEach { paidWith ->
                        AppChip(
                            label = stringResource(paidWith.label),
                            selected = paidWith == state.paidWith,
                            onClick = { onEvent(AddExpenseEvent.PaidWithChosen(paidWith)) },
                        )
                    }
                }
            }
        }
        SettingRow(
            title = stringResource(R.string.payment_manual_split_title),
            subtitle = stringResource(R.string.payment_manual_split_subtitle),
            icon = AppIcon.Users,
            checked = state.splitThis,
            onCheckedChange = { onEvent(AddExpenseEvent.SplitThisChanged(it)) },
            trailing = { AppSwitch(checked = state.splitThis, onCheckedChange = null) },
        )
        // Not designed: below what the design draws, the category, the day and who it counts for.
        CategoryPicker(
            selected = state.category,
            onSelect = { onEvent(AddExpenseEvent.CategoryChosen(it)) },
            onMore = null,
        )
        SectionLabel(text = stringResource(R.string.payment_manual_date))
        SettingRow(
            title = dayText(state.date, state.today),
            subtitle = stringResource(R.string.payment_manual_date_change),
            icon = AppIcon.Calendar,
            onClick = { onEvent(AddExpenseEvent.PickDate) },
        )
        Reveal(visible = !state.splitThis) {
            ScopePicker(selected = state.scope, onSelect = { onEvent(AddExpenseEvent.ScopeChosen(it)) })
        }
    }
    if (state.pickingDate) {
        SystemDatePicker(
            initial = state.date,
            latest = state.today,
            onPicked = { onEvent(AddExpenseEvent.DatePicked(it)) },
            onDismiss = { onEvent(AddExpenseEvent.DatePickerClosed) },
        )
    }
}

/**
 * The AMOUNT label and the amount in `Amount/XL`, centred, typed with the number keyboard. When the amount is wrong
 * an alert mark and "That does not look right." show under it, so the error is not shown by colour alone. The message
 * opens down (Reveal), and each refused Add shakes the amount and plays the reject haptic ([shakeOn]).
 */
@Composable
private fun AmountField(
    text: String,
    showError: Boolean,
    refusedSubmits: Int,
    onChange: (String) -> Unit,
    focusOnStart: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val label = stringResource(R.string.payment_manual_amount_label)
    val errorText = stringResource(R.string.payment_error_amount)
    val colors = AppTheme.colors
    val amountStyle = AppTheme.type.amountXL.copy(color = colors.textPrimary, textAlign = TextAlign.Center)
    val currency = stringResource(R.string.payment_manual_currency)
    val placeholder = currency + stringResource(R.string.payment_manual_amount_placeholder)
    if (focusOnStart) {
        LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.s8),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionLabel(text = label)
        BasicTextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .shakeOn(refusedSubmits)
                .focusRequester(focusRequester)
                .semantics {
                    contentDescription = label
                    if (showError) error(errorText)
                },
            textStyle = amountStyle,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            visualTransformation = if (text.isEmpty()) VisualTransformation.None else PrefixTransformation(currency),
            cursorBrush = SolidColor(colors.brandPrimary),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (text.isEmpty()) {
                        AppText(text = placeholder, style = amountStyle, color = colors.textTertiary)
                    }
                    inner()
                }
            },
        )
        Reveal(visible = showError) {
            Row(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(Space.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LineIcon(icon = AppIcon.Alert, size = Space.s20, tint = colors.statusNegative)
                AppText(text = errorText, style = AppTheme.type.caption, color = colors.statusNegative)
            }
        }
    }
}

/** Draws [prefix] before the typed text without making it part of the value, so "600" shows as "₹600". */
private class PrefixTransformation(private val prefix: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        text = AnnotatedString(prefix) + text,
        offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset + prefix.length
            override fun transformedToOriginal(offset: Int): Int = (offset - prefix.length).coerceIn(0, text.length)
        },
    )
}
