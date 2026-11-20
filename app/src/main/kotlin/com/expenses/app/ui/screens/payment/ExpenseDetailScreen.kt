package com.expenses.app.ui.screens.payment

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.expenses.app.R
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppInputState
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.ChoiceDialog
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.components.SurfaceCardStyle
import com.expenses.app.ui.components.TagTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.AnimatedOverlay
import com.expenses.app.ui.motion.FadeThroughContent
import com.expenses.app.ui.motion.OverlayScope
import com.expenses.app.ui.motion.keptWhileLeaving
import com.expenses.app.ui.motion.shakeOn
import com.expenses.app.ui.theme.AppTheme
import com.expenses.parser.Direction
import com.expenses.splits.SplitMethod

/**
 * Expense detail (design 02-everyday/08-expense-detail): the amount with where it came from, then who was paid,
 * the category, who it counts for and the note, and the bank's original message when there is one (shown on this
 * phone only). "Edit" in the top bar turns the rows into fields; the amount can be changed only for an expense
 * typed in by hand. "Delete expense" asks first and then stops the expense counting in its month.
 *
 * A split (47-expense-detail-delete-split) shows its group and the person's share ("Flat 302" / "Split equally · your
 * share ₹150") in place of who it counts for, and no "Split this expense"; its delete question names the group.
 *
 * @param state what to show; see [ExpenseDetailUiState]. [ExpenseDetailUiState.Removed] keeps the last page drawn,
 * without acting on taps, as the caller goes back.
 * @param onEvent called with what the person did; see [ExpenseDetailEvent].
 * @param modifier outer modifier. The screen fills the space it is given and keeps clear of the system bars.
 */
@Composable
fun ExpenseDetailScreen(
    state: ExpenseDetailUiState,
    onEvent: (ExpenseDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val leaving = state == ExpenseDetailUiState.Removed
    val events: (ExpenseDetailEvent) -> Unit = if (leaving) ({ }) else onEvent
    when (val drawn = keptWhileLeaving(state, leaving) { it is ExpenseDetailUiState.Shown }) {
        ExpenseDetailUiState.Loading, ExpenseDetailUiState.Removed -> PaymentLoading()
        ExpenseDetailUiState.Gone -> PaymentGone(onBack = { onEvent(ExpenseDetailEvent.Back) })
        // Viewing and editing swap with a fade-through, keyed by the mode (never by typed text).
        is ExpenseDetailUiState.Shown -> FadeThroughContent(targetState = drawn, contentKey = { it.edit == null }) { shown ->
            val edit = shown.edit
            if (edit == null) {
                DetailView(shown, events, modifier)
            } else {
                val current = transition.targetState == EnterExitState.Visible
                DetailEdit(shown.details, shown.split, edit, events, modifier, backEnabled = current)
            }
        }
    }
    // Outside the state's branches, so the question leaves with motion also when the expense is removed.
    AnimatedOverlay((state as? ExpenseDetailUiState.Shown)?.takeIf { it.confirmingDelete }) { shown ->
        DeleteQuestion(shown.details, shown.split, onEvent, overlay = this)
    }
}

/** The expense as rows, with the actions that fit its status at the bottom. */
@Composable
private fun DetailView(
    state: ExpenseDetailUiState.Shown,
    onEvent: (ExpenseDetailEvent) -> Unit,
    modifier: Modifier,
) {
    val details = state.details
    PaymentPage(
        title = stringResource(R.string.payment_detail_title),
        onBack = { onEvent(ExpenseDetailEvent.Back) },
        modifier = modifier,
        actionLabel = stringResource(R.string.payment_detail_edit),
        onAction = { onEvent(ExpenseDetailEvent.Edit) },
        footer = { DetailActions(state, onEvent) },
    ) {
        DetailHeader(details, state.split)
        val category = stringResource(details.category.label)
        SurfaceCard {
            InfoRow(
                title = merchantText(details.merchant),
                subtitle = stringResource(
                    if (details.direction == Direction.CREDIT) R.string.payment_detail_from else R.string.payment_detail_paid_to,
                ),
                icon = details.category.icon,
            )
            InfoRow(
                title = category,
                subtitle = stringResource(R.string.payment_detail_category),
                icon = details.category.icon,
                onClick = { onEvent(ExpenseDetailEvent.ChangeCategory) },
            )
            val split = state.split
            if (split != null) {
                SplitRow(split, onOpenRule = { onEvent(ExpenseDetailEvent.OpenSplitRule) })
            } else {
                InfoRow(
                    title = stringResource(details.scope.label),
                    subtitle = countedText(details),
                    icon = details.scope.icon,
                )
            }
            InfoRow(
                title = details.note?.takeIf(String::isNotBlank) ?: stringResource(R.string.payment_detail_no_note),
                subtitle = stringResource(R.string.payment_detail_note),
                icon = AppIcon.Edit,
            )
        }
        details.originalMessage?.takeIf(String::isNotBlank)?.let { OriginalMessage(it) }
    }
}

/**
 * The tag, the amount and when and how it was paid, above the rows. A friend's expense mirrored here (not designed)
 * reads "From Flat 302" and "12 Nov · Rahul paid": it has no time of day and no account of the person's.
 */
@Composable
private fun DetailHeader(details: ExpenseDetails, split: ExpenseSplit?) {
    val friendPaid = details.source == ExpenseSource.GROUP && split != null
    val (tag, tone) = when {
        details.status == ExpenseStatus.IGNORED -> stringResource(R.string.payment_tag_not_counted) to TagTone.Neutral
        details.direction == Direction.CREDIT -> stringResource(R.string.payment_tag_money_in) to TagTone.Brand
        details.source == ExpenseSource.SMS -> stringResource(R.string.payment_tag_from_sms) to TagTone.Blue
        friendPaid -> stringResource(R.string.payment_tag_from_group, split.groupName) to TagTone.Neutral
        else -> stringResource(R.string.payment_tag_by_hand) to TagTone.Purple
    }
    val payer = split?.paidByName
    AmountHeader(
        tag = tag,
        tagTone = tone,
        amount = amountText(details.amountMinor, details.currency),
        caption = if (friendPaid && payer != null) {
            stringResource(
                R.string.payment_when_with_source,
                dayText(details.time.at.toLocalDate(), details.time.today),
                stringResource(R.string.payment_detail_someone_paid, payer),
            )
        } else {
            whenAndSourceText(details.time, details.account, details.paidWith)
        },
    )
}

/**
 * The split row (design 02-everyday/47): the group and "Split equally · your share ₹150". When the payment's merchant
 * splits itself with this group the line ends "· splits itself" and a tap opens Same split?, where that can be turned
 * off (not designed). Otherwise it only informs.
 */
@Composable
private fun SplitRow(split: ExpenseSplit, onOpenRule: () -> Unit) {
    val share = formatRupees(split.myShareMinor)
    val how = stringResource(
        when (split.method) {
            SplitMethod.EQUAL -> R.string.payment_detail_split_equally
            SplitMethod.AMOUNTS -> R.string.payment_detail_split_amounts
            SplitMethod.PERCENT -> R.string.payment_detail_split_percent
            SplitMethod.SHARES -> R.string.payment_detail_split_shares
        },
        share,
    )
    InfoRow(
        title = split.groupName,
        subtitle = if (split.splitsItself) stringResource(R.string.payment_detail_splits_itself, how) else how,
        icon = AppIcon.Users,
        onClick = if (split.splitsItself) onOpenRule else null,
    )
}

/**
 * The buttons at the bottom: what can be done depends on whether the expense counts, waits or was deleted. A split
 * cannot be split again.
 */
@Composable
private fun ColumnScope.DetailActions(state: ExpenseDetailUiState.Shown, onEvent: (ExpenseDetailEvent) -> Unit) {
    when (state.details.status) {
        ExpenseStatus.DETECTED -> AppButton(
            label = stringResource(R.string.payment_detail_review),
            onClick = { onEvent(ExpenseDetailEvent.Review) },
            modifier = Modifier.fillMaxWidth(),
        )
        ExpenseStatus.IGNORED -> AppButton(
            label = stringResource(R.string.payment_detail_add_back),
            onClick = { onEvent(ExpenseDetailEvent.CountAgain) },
            modifier = Modifier.fillMaxWidth(),
            style = ButtonStyle.Secondary,
        )
        ExpenseStatus.ADDED -> {
            if (state.canSplit) {
                AppButton(
                    label = stringResource(R.string.payment_detail_split),
                    onClick = { onEvent(ExpenseDetailEvent.Split) },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Secondary,
                )
            }
            AppButton(
                label = stringResource(R.string.payment_detail_delete),
                onClick = { onEvent(ExpenseDetailEvent.Delete) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        }
    }
}

/** The bank's message the payment was read from, with the promise that it stays on this phone. */
@Composable
private fun OriginalMessage(message: String) {
    SectionLabel(text = stringResource(R.string.payment_detail_original_message))
    SurfaceCard(style = SurfaceCardStyle.Note) {
        AppText(
            text = stringResource(R.string.payment_review_quote, message),
            style = AppTheme.type.bodyM,
            color = AppTheme.colors.textPrimary,
        )
    }
    AppText(
        text = stringResource(R.string.payment_detail_original_message_privacy),
        style = AppTheme.type.caption,
        color = AppTheme.colors.textSecondary,
    )
}

/** The edit form: fields in place of the rows, and "Save changes". */
@Composable
private fun DetailEdit(
    details: ExpenseDetails,
    split: ExpenseSplit?,
    edit: ExpenseEdit,
    onEvent: (ExpenseDetailEvent) -> Unit,
    modifier: Modifier,
    backEnabled: Boolean = true,
) {
    // Back while editing leaves the edit, not the screen, so typed changes are never dropped silently with the page.
    // Not while this edit is leaving (fading out after Save or Cancel).
    BackHandler(enabled = backEnabled) { onEvent(ExpenseDetailEvent.CancelEdit) }
    PaymentPage(
        title = stringResource(R.string.payment_detail_title),
        onBack = { onEvent(ExpenseDetailEvent.CancelEdit) },
        modifier = modifier,
        actionLabel = stringResource(R.string.payment_detail_cancel),
        onAction = { onEvent(ExpenseDetailEvent.CancelEdit) },
        footer = {
            AppButton(
                label = stringResource(R.string.payment_detail_save),
                onClick = { onEvent(ExpenseDetailEvent.SaveEdit) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !edit.saving,
            )
        },
    ) {
        if (edit.amountText == null) {
            DetailHeader(details, split)
        } else {
            AppInput(
                value = edit.amountText,
                onValueChange = { onEvent(ExpenseDetailEvent.AmountChanged(it)) },
                label = stringResource(R.string.payment_input_amount),
                modifier = Modifier
                    .fillMaxWidth()
                    .shakeOn(edit.refusedSubmits),
                helper = stringResource(R.string.payment_error_amount),
                state = if (edit.amountError) AppInputState.Error else AppInputState.Default,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            )
        }
        AppInput(
            value = edit.payee,
            onValueChange = { onEvent(ExpenseDetailEvent.PayeeChanged(it)) },
            label = stringResource(
                if (details.direction == Direction.CREDIT) R.string.payment_detail_from else R.string.payment_input_paid_to,
            ),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
        )
        CategoryPicker(
            selected = edit.category,
            onSelect = { onEvent(ExpenseDetailEvent.CategoryChosen(it)) },
            onMore = { onEvent(ExpenseDetailEvent.ChangeCategory) },
        )
        // A split counts the person's share in their own month; who it counts for is not chosen here.
        if (!details.linked) ScopePicker(selected = edit.scope, onSelect = { onEvent(ExpenseDetailEvent.ScopeChosen(it)) })
        AppInput(
            value = edit.note,
            onValueChange = { onEvent(ExpenseDetailEvent.NoteChanged(it)) },
            label = stringResource(R.string.payment_input_note),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
        )
    }
}

/**
 * The delete question, as the design's confirm dialog (47): for a split, "Delete Swiggy from Flat 302?" with "Delete
 * for everyone" and "Keep it"; otherwise "Delete this expense?" with "Delete" and "Keep it" in the same dialog.
 */
@Composable
private fun DeleteQuestion(
    details: ExpenseDetails,
    split: ExpenseSplit?,
    onEvent: (ExpenseDetailEvent) -> Unit,
    overlay: OverlayScope,
) {
    val month = monthName(details.month)
    if (split != null) {
        ChoiceDialog(
            icon = AppIcon.Trash,
            title = stringResource(R.string.payment_detail_confirm_split_title, merchantText(details.merchant), split.groupName),
            body = if (split.myShareMinor > 0) {
                stringResource(R.string.payment_detail_confirm_split_body, formatRupees(split.myShareMinor), month)
            } else {
                stringResource(R.string.payment_detail_confirm_split_body_no_share)
            },
            confirmLabel = stringResource(R.string.payment_detail_confirm_split_delete),
            dismissLabel = stringResource(R.string.payment_detail_confirm_keep),
            onConfirm = { onEvent(ExpenseDetailEvent.ConfirmDelete) },
            onDismiss = { onEvent(ExpenseDetailEvent.DismissDelete) },
            overlay = overlay,
        )
        return
    }
    ChoiceDialog(
        icon = AppIcon.Trash,
        title = stringResource(R.string.payment_detail_confirm_title),
        body = stringResource(
            when {
                // Shared in the family month: it leaves that too (06-family/26).
                details.inFamilyMonth -> R.string.payment_detail_delete_family_body
                details.source == ExpenseSource.SMS -> R.string.payment_detail_confirm_body
                else -> R.string.payment_detail_confirm_body_manual
            },
            month,
        ),
        confirmLabel = stringResource(R.string.payment_detail_confirm_delete),
        dismissLabel = stringResource(R.string.payment_detail_confirm_keep),
        onConfirm = { onEvent(ExpenseDetailEvent.ConfirmDelete) },
        onDismiss = { onEvent(ExpenseDetailEvent.DismissDelete) },
        overlay = overlay,
    )
}

/** The line under "Just me" or "Family": whether and where the expense counts. */
@Composable
private fun countedText(details: ExpenseDetails): String {
    val month = monthName(details.month)
    return when {
        details.direction == Direction.CREDIT -> stringResource(R.string.payment_detail_money_in)
        details.status == ExpenseStatus.DETECTED -> stringResource(R.string.payment_detail_waiting)
        details.status == ExpenseStatus.IGNORED -> stringResource(R.string.payment_detail_not_counted, month)
        details.currency != Expense.INR -> stringResource(R.string.payment_detail_foreign, month)
        details.scope == ExpenseScope.FAMILY -> stringResource(R.string.payment_detail_counted_family, month)
        else -> stringResource(R.string.payment_detail_counted_in, month)
    }
}

/** The 3D icon for Just me or Family. */
private val ExpenseScope.icon: AppIcon
    get() = when (this) {
        ExpenseScope.ME -> AppIcon.User
        ExpenseScope.FAMILY -> AppIcon.Users
    }

/** The amount in rupees ("₹450"), or with its currency code for a payment in another currency ("USD 12.50"). */
@Composable
private fun amountText(amountMinor: Long, currency: String): String =
    if (currency == Expense.INR) {
        formatRupees(amountMinor)
    } else {
        stringResource(R.string.payment_amount_foreign, currency, amountFieldText(amountMinor))
    }
