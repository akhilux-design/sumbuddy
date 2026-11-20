package com.expenses.app.ui.screens.split

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import com.expenses.app.R
import com.expenses.app.ui.motion.FadeThroughContent
import com.expenses.app.ui.motion.RevealValue
import androidx.compose.runtime.key
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppChip
import com.expenses.app.ui.components.AppSwitch
import com.expenses.app.ui.components.Avatar
import com.expenses.app.ui.components.BalanceRow
import com.expenses.app.ui.components.BalanceRowDirection
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.ExpenseRow
import com.expenses.app.ui.components.ExpenseRowKind
import com.expenses.app.ui.components.FlatRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.flatRowSurface
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.screens.payment.merchantText
import com.expenses.app.ui.screens.payment.monthName
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space
import com.expenses.splits.SplitMethod
import java.time.YearMonth

/**
 * Split it (design 02-everyday/09-split-details, 10-amounts, 11-amounts-do-not-add-up): the payment as an Expense
 * Row, WITH (the groups as chips, "+ New" for Choose people), HOW (Equally, Amounts, %, Shares) and the people as
 * Balance Rows. With Amounts, % or Shares each person's part is typed in place, at the end of their row, with the
 * system number keyboard; the note under the people says what is still left and "Split ₹450" stays off until the
 * parts add up. "Do this every time" keeps the split for the next payment to the same merchant.
 *
 * @param state what to show; see [SplitUiState].
 * @param onEvent called with what the person did; see [SplitEvent].
 * @param modifier outer modifier. The screen fills the space it is given and keeps clear of the system bars.
 */
@Composable
fun SplitScreen(state: SplitUiState, onEvent: (SplitEvent) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.split_title)
    when (state) {
        SplitUiState.Loading -> PageFrame(title = title, onBack = { onEvent(SplitEvent.Back) }, modifier = modifier) {}
        is SplitUiState.CannotSplit -> CannotSplit(state.reason, onBack = { onEvent(SplitEvent.Back) }, modifier = modifier)
        is SplitUiState.Form -> SplitForm(state, onEvent, modifier)
    }
}

@Composable
private fun SplitForm(state: SplitUiState.Form, onEvent: (SplitEvent) -> Unit, modifier: Modifier) {
    PageFrame(
        title = stringResource(R.string.split_title),
        onBack = { onEvent(SplitEvent.Back) },
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.split_button, formatRupees(state.header.amountMinor)),
                onClick = { onEvent(SplitEvent.Split) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSplit,
            )
        },
        // 09 to 11 put the Expense Row right under the top bar: their Body has no top padding.
        bodyTopPadding = Space.s0,
    ) {
        PaymentHeader(state.header)
        SectionLabel(text = stringResource(R.string.split_with))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s12),
        ) {
            state.groups.forEach { group ->
                AppChip(
                    label = group.name,
                    selected = group.id == state.chosenGroupId,
                    onClick = { onEvent(SplitEvent.GroupPicked(group.id)) },
                )
            }
            if (state.canChangeGroup) {
                AppChip(
                    label = stringResource(R.string.split_new),
                    selected = false,
                    onClick = { onEvent(SplitEvent.NewGroup) },
                    role = Role.Button,
                )
            }
        }
        if (state.chosenGroupId != null) {
            SectionLabel(text = stringResource(R.string.split_how))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(Space.s4),
                verticalArrangement = Arrangement.spacedBy(Space.s12),
            ) {
                SplitMethod.entries.forEach { method ->
                    AppChip(
                        label = methodChipText(method),
                        selected = method == state.method,
                        onClick = { onEvent(SplitEvent.MethodPicked(method)) },
                    )
                }
            }
            People(state, onEvent)
            // The note and the rule open and close like Reveal when they come and go after the screen is shown.
            RevealValue(state.note) { note ->
                AppText(
                    text = splitNoteText(note, state.month),
                    style = AppTheme.type.caption,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = if (note.isProblem) AppTheme.colors.statusNegative else AppTheme.colors.textBrand,
                )
            }
            RevealValue(state.rememberRule) { on ->
                SettingRow(
                    title = stringResource(R.string.split_rule_title),
                    subtitle = stringResource(R.string.split_rule_subtitle, state.chosenGroupName, methodRuleText(state.method)),
                    icon = AppIcon.Repeat,
                    checked = on,
                    onCheckedChange = { onEvent(SplitEvent.RememberChanged(it)) },
                    trailing = { AppSwitch(checked = on, onCheckedChange = null) },
                )
            }
        }
    }
}

/** The payment as an Expense Row Kind=Shared: who was paid, the day and account, the amount and who paid. */
@Composable
private fun PaymentHeader(header: SplitHeader) {
    ExpenseRow(
        title = header.title ?: if (header.typed) stringResource(header.category.label) else merchantText(null),
        subtitle = splitRowSubtitle(header),
        amount = formatRupees(header.amountMinor),
        meta = header.payerName?.let { stringResource(R.string.split_someone_paid, it) } ?: stringResource(R.string.split_you_paid),
        icon = header.category.icon,
        kind = ExpenseRowKind.Shared,
    )
}

/** The chosen group's people, 8dp apart with 4dp above and below, as the design's People frame. */
@Composable
private fun People(state: SplitUiState.Form, onEvent: (SplitEvent) -> Unit) {
    val payer = state.people.firstOrNull { it.isPayer }
    val iPaid = payer?.isMe != false
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        state.people.forEach { person ->
            val look = rowLook(person, iPaid)
            val detail = detailText(person, payer, state.method, state.month)
            val name = if (person.isMe) stringResource(R.string.split_you) else person.name
            // Equal shares and typed parts swap with a fade-through, keyed by equal or not (never by typed text).
            key(person.memberId) {
                FadeThroughContent(targetState = state.method, contentKey = { it == SplitMethod.EQUAL }) { method ->
                    if (method == SplitMethod.EQUAL) {
                        BalanceRow(
                            name = name,
                            detail = detail,
                            amount = look.sign + formatRupees(person.shareMinor),
                            initial = person.initial,
                            direction = look.direction,
                            avatarColor = person.avatar,
                        )
                    } else {
                        PartRow(
                            person = person,
                            name = name,
                            detail = detail,
                            method = method,
                            look = look,
                            onChange = { onEvent(SplitEvent.PartChanged(person.memberId, it)) },
                        )
                    }
                }
            }
        }
    }
}

/** How a person's amount reads: its sign and colour (the Balance Row's direction). */
private data class RowLook(val sign: String, val direction: BalanceRowDirection)

private fun rowLook(person: SplitPerson, iPaid: Boolean): RowLook = when {
    // The design: the person's own share is grey with no sign; what a friend will owe is green with "+".
    person.isMe && iPaid -> RowLook("", BalanceRowDirection.Settled)
    iPaid -> RowLook("+", BalanceRowDirection.OwesYou)
    // Not in the design: someone else paid. The person owes their share ("-", red); the others are plain.
    person.isMe && person.shareMinor > 0 -> RowLook("-", BalanceRowDirection.YouOwe)
    person.isMe -> RowLook("", BalanceRowDirection.Settled)
    else -> RowLook("", BalanceRowDirection.Plain)
}

/** The line under a person's name. With % and Shares the money is here, as the row's end holds the part. */
@Composable
private fun detailText(person: SplitPerson, payer: SplitPerson?, method: SplitMethod, month: YearMonth): String {
    val withMoney = method == SplitMethod.PERCENT || method == SplitMethod.SHARES
    val share = formatRupees(person.shareMinor)
    val monthName = monthName(month)
    val payerIsOther = payer != null && !payer.isMe
    return when {
        person.isMe && withMoney -> stringResource(R.string.split_detail_your_share_amount, share, monthName)
        person.isMe -> stringResource(R.string.split_detail_your_share, monthName)
        !payerIsOther && withMoney -> stringResource(R.string.split_detail_will_owe_you_amount, share)
        !payerIsOther -> stringResource(R.string.split_detail_will_owe_you)
        person.isPayer && withMoney -> stringResource(R.string.split_detail_paid_amount, share)
        person.isPayer -> stringResource(R.string.split_detail_paid)
        withMoney -> stringResource(R.string.split_detail_will_owe_amount, payer!!.name, share)
        else -> stringResource(R.string.split_detail_will_owe, payer!!.name)
    }
}

/**
 * A Balance Row whose amount is typed in place: the part sits where the design draws the amount, in Amount/M and
 * the same colour, and the system number keyboard opens when the row is tapped (not designed: the design draws
 * the amounts but not how they are typed).
 */
@Composable
private fun PartRow(
    person: SplitPerson,
    name: String,
    detail: String,
    method: SplitMethod,
    look: RowLook,
    onChange: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val colors = AppTheme.colors
    val color = when (look.direction) {
        BalanceRowDirection.OwesYou -> colors.statusPositive
        BalanceRowDirection.YouOwe -> colors.statusNegative
        BalanceRowDirection.Settled -> colors.textTertiary
        BalanceRowDirection.Plain -> colors.textPrimary
    }
    val shares = person.input.toLongOrNull()?.toInt() ?: 0
    val (prefix, suffix) = when (method) {
        SplitMethod.AMOUNTS -> look.sign + stringResource(R.string.payment_manual_currency) to ""
        SplitMethod.PERCENT -> "" to stringResource(R.string.split_percent_value, "")
        SplitMethod.SHARES -> "" to pluralStringResource(R.plurals.split_shares_value, shares, "")
        SplitMethod.EQUAL -> "" to ""
    }
    val description = if (person.isMe) stringResource(R.string.split_field_mine) else stringResource(R.string.split_field_other, person.name)
    FlatRow(
        title = name,
        subtitle = detail,
        leading = { Avatar(initial = person.initial, color = person.avatar) },
        modifier = Modifier
            .flatRowSurface(colors.bgSurface)
            // A tap anywhere on the row puts the cursor in the part, so the touch area is the whole row.
            .pointerInput(focus) { detectTapGestures { focus.requestFocus() } }
            .padding(Space.s12),
        trailing = {
            PartField(
                text = person.input,
                onChange = onChange,
                prefix = prefix,
                suffix = suffix,
                color = color,
                decimal = method != SplitMethod.SHARES,
                description = description,
                focusRequester = focus,
            )
        },
    )
}

/** The typed part: plain text in Amount/M, with the rupee sign or the unit drawn around it but not typed. */
@Composable
private fun PartField(
    text: String,
    onChange: (String) -> Unit,
    prefix: String,
    suffix: String,
    color: Color,
    decimal: Boolean,
    description: String,
    focusRequester: FocusRequester,
) {
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text) value = value.copy(text = text, selection = TextRange(text.length))
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            value = next
            if (next.text != text) onChange(next.text)
        },
        modifier = Modifier
            .width(IntrinsicSize.Min)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                // Taking the field selects what is in it, so typing replaces the part.
                if (state.isFocused && !focused) value = value.copy(selection = TextRange(0, value.text.length))
                focused = state.isFocused
            }
            .semantics { contentDescription = description },
        textStyle = AppTheme.type.amountM.copy(color = color, textAlign = TextAlign.End),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        visualTransformation = AroundTransformation(prefix, suffix),
        cursorBrush = SolidColor(AppTheme.colors.brandPrimary),
    )
}

/** Draws [prefix] before and [suffix] after the typed text without making them part of it: "+₹150", "40%". */
private class AroundTransformation(private val prefix: String, private val suffix: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        text = AnnotatedString(prefix) + text + AnnotatedString(suffix),
        offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset + prefix.length
            override fun transformedToOriginal(offset: Int): Int = (offset - prefix.length).coerceIn(0, text.length)
        },
    )

    override fun equals(other: Any?): Boolean =
        other is AroundTransformation && other.prefix == prefix && other.suffix == suffix

    override fun hashCode(): Int = prefix.hashCode() * PRIME + suffix.hashCode()

    private companion object {
        const val PRIME = 31
    }
}

/** Not designed: Split it for a payment that is gone, already split or not a rupee payment. */
@Composable
private fun CannotSplit(reason: CannotSplitReason, onBack: () -> Unit, modifier: Modifier) {
    PageFrame(title = stringResource(R.string.split_title), onBack = onBack, modifier = modifier) {
        EmptyState(
            icon = if (reason == CannotSplitReason.GONE) AppIcon.Search else AppIcon.Split,
            title = stringResource(R.string.split_cannot_title),
            body = stringResource(
                when (reason) {
                    CannotSplitReason.GONE -> R.string.split_cannot_gone
                    CannotSplitReason.ALREADY_SPLIT -> R.string.split_cannot_already
                    CannotSplitReason.NOT_SPLITTABLE -> R.string.split_cannot_other
                },
            ),
            modifier = Modifier.padding(top = Space.s16),
        ) {
            AppButton(
                label = stringResource(R.string.split_go_back),
                onClick = onBack,
                style = ButtonStyle.Secondary,
                size = ButtonSize.Medium,
            )
        }
    }
}
