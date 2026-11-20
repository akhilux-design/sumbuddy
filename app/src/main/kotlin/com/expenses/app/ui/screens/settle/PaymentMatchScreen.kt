package com.expenses.app.ui.screens.settle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.BalanceRow
import com.expenses.app.ui.components.BalanceRowDirection
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.Tag
import com.expenses.app.ui.components.TagTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.format.readAs
import com.expenses.app.ui.format.upiIdLineBreaks
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/** Under an hour, "N min ago"; under this many minutes, "just now". */
private const val JUST_NOW_MINUTES = 2L
private const val MINUTES_PER_HOUR = 60L

/** A space a line never breaks at. */
private const val NO_BREAK_SPACE = '\u00A0'

/**
 * Money received (design 03-getting-paid-back/02-payment-match): the money in ("From SMS", "+₹1,200", "HDFC Bank
 * ••4521 · just now"), "Was this Rahul paying you back?", the friend's Balance Row ("owes you for Flat 302"), "Same
 * amount", and "Yes, clear it" / "No, something else".
 *
 * @param state what to show; [PaymentMatchUiState.Gone] says there is nothing to clear any more.
 * @param onEvent called with what the person did.
 * @param modifier outer modifier.
 */
@Composable
fun PaymentMatchScreen(state: PaymentMatchUiState, onEvent: (PaymentMatchEvent) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.settle_match_title)
    val back = { onEvent(PaymentMatchEvent.Back) }
    when (state) {
        PaymentMatchUiState.Loading, is PaymentMatchUiState.Cleared -> PageFrame(title = title, onBack = back, modifier = modifier) {}
        PaymentMatchUiState.Gone -> PageFrame(title = title, onBack = back, modifier = modifier) {
            // Design 03-getting-paid-back/11: the Body's top padding is 24, PageFrame gives 8 of it.
            EmptyState(
                icon = AppIcon.Search,
                title = stringResource(R.string.settle_match_gone_title),
                body = stringResource(R.string.settle_match_gone_body),
                modifier = Modifier.padding(top = Space.s16),
            ) {
                AppButton(
                    label = stringResource(R.string.settle_match_gone_back),
                    onClick = back,
                    style = ButtonStyle.Secondary,
                    size = ButtonSize.Medium,
                )
            }
        }
        is PaymentMatchUiState.Shown -> PaymentMatchContent(state, title, onEvent, modifier)
    }
}

@Composable
private fun PaymentMatchContent(state: PaymentMatchUiState.Shown, title: String, onEvent: (PaymentMatchEvent) -> Unit, modifier: Modifier) {
    PageFrame(
        title = title,
        onBack = { onEvent(PaymentMatchEvent.Back) },
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.settle_match_yes),
                onClick = { onEvent(PaymentMatchEvent.Yes) },
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                label = stringResource(R.string.settle_match_no),
                onClick = { onEvent(PaymentMatchEvent.No) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Secondary,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(vertical = Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s8),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Tag(label = stringResource(R.string.settle_match_tag), tone = TagTone.Blue)
            AppText(
                text = stringResource(R.string.settle_match_amount, formatRupees(state.amountMinor)),
                style = AppTheme.type.amountXL,
                color = AppTheme.colors.statusPositive,
                maxLines = 1,
            )
            // Never cut off: a long caption wraps, an account known only by its UPI ID breaks only after its "@", and
            // "· 12 min ago" stays whole. Screen readers read it without the line break marks.
            val source = sourceText(state, shownName = state.accountName)
            val shownSource = sourceText(state, shownName = state.accountName?.let(::upiIdLineBreaks))
            AppText(
                text = shownSource,
                style = AppTheme.type.caption,
                modifier = if (shownSource != source) Modifier.readAs(source) else Modifier,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        AppText(
            text = stringResource(R.string.settle_match_question, state.friendName),
            style = AppTheme.type.headingL,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        BalanceRow(
            name = state.friendName,
            detail = when (val owed = state.owedFor) {
                is OwedFor.Group -> stringResource(R.string.settle_match_owes_for, owed.name)
                OwedFor.Direct -> stringResource(R.string.settle_match_owes)
                is OwedFor.Several -> pluralStringResource(R.plurals.settle_match_owes_for_groups, owed.count, owed.count)
            },
            amount = stringResource(R.string.settle_match_amount, formatRupees(state.owedMinor)),
            initial = state.friendInitial,
            direction = BalanceRowDirection.OwesYou,
            avatarColor = state.friendAvatar,
        )
        InfoRow(
            title = stringResource(R.string.settle_match_same_title),
            subtitle = stringResource(R.string.settle_match_same_body, state.friendName),
            icon = AppIcon.Target,
        )
    }
}

/**
 * "HDFC Bank ••4521 · just now"; only the time when the account is not known. The time never breaks inside ("12 min
 * ago" has no-break spaces, as has "· " before it in settle_match_source), so a long caption moves it whole to the
 * next line.
 *
 * @param shownName the account's name as it is shown: [PaymentMatchUiState.Shown.accountName], with a UPI ID's line
 * break marks ([upiIdLineBreaks]) for the text drawn, without them for screen readers.
 */
@Composable
private fun sourceText(state: PaymentMatchUiState.Shown, shownName: String?): String {
    val minutes = state.minutesAgo
    val time = when {
        minutes < JUST_NOW_MINUTES -> stringResource(R.string.settle_match_just_now)
        minutes < MINUTES_PER_HOUR -> pluralStringResource(R.plurals.settle_match_minutes_ago, minutes.toInt(), minutes.toInt())
        else -> {
            val hours = (minutes / MINUTES_PER_HOUR).toInt()
            pluralStringResource(R.plurals.settle_match_hours_ago, hours, hours)
        }
    }.replace(' ', NO_BREAK_SPACE)
    val account = when {
        shownName != null && state.accountLast4 != null -> stringResource(R.string.settle_match_account, shownName, state.accountLast4)
        else -> shownName
    }
    return if (account != null) stringResource(R.string.settle_match_source, account, time) else time
}
