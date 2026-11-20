package com.expenses.app.ui.screens.grouppage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.ui.motion.AddedRow
import com.expenses.app.ui.motion.FadeThroughContent
import com.expenses.app.ui.motion.RevealValue
import com.expenses.app.ui.motion.rememberFirstKeys
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.BalanceCard
import com.expenses.app.ui.components.BalanceCardTone
import com.expenses.app.ui.components.BalanceRow
import com.expenses.app.ui.components.BalanceRowDirection
import com.expenses.app.ui.components.Banner
import com.expenses.app.ui.components.BannerTone
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.ExpenseRow
import com.expenses.app.ui.components.ExpenseRowKind
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.PlaceholderCard
import com.expenses.app.ui.components.PlaceholderRow
import com.expenses.app.ui.components.PlaceholderRowCount
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space
import com.expenses.splits.SplitMethod
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Space between the rows of one list (`space/8`); the body puts 12 between its other parts. */
private val RowGap = Space.s8

/**
 * The Group page (design 02-everyday/24-group-page): the group's name with "Add", the Balance Card, Balances, This
 * month and the Group settings row, with "Settle up" and "Remind" at the bottom. Also its states: 22's placeholders,
 * 23 "Could not load this group", 40's joined banner, 41's join undone, 42 "You owe", 43's "Not saved" row.
 *
 * @param state what to show; see [GroupPageUiState].
 * @param onEvent called with what the person did; see [GroupPageEvent].
 * @param modifier layout changes from the caller.
 */
@Composable
fun GroupPageScreen(state: GroupPageUiState, onEvent: (GroupPageEvent) -> Unit, modifier: Modifier = Modifier) {
    // Gone: keep drawing the last shown page while the screen leaves, instead of sliding out an empty frame.
    val lastShown = remember { LastPage() }
    if (state is GroupPageUiState.Shown) lastShown.page = state
    val drawn = if (state == GroupPageUiState.Gone) lastShown.page ?: state else state
    // Loading, could not load and shown swap with a fade-through, keyed by the kind of state.
    FadeThroughContent(targetState = drawn, contentKey = { it::class }) { page -> GroupPageBody(page, onEvent, modifier) }
}

/** The page for one kind of [state]; see [GroupPageScreen]. */
@Composable
private fun GroupPageBody(state: GroupPageUiState, onEvent: (GroupPageEvent) -> Unit, modifier: Modifier) {
    val back = { onEvent(GroupPageEvent.Back) }
    when (state) {
        is GroupPageUiState.Loading -> PageFrame(title = state.title, onBack = back, modifier = modifier) {
            PlaceholderCard(description = stringResource(R.string.grouppage_loading_description))
            repeat(PlaceholderRowCount) { PlaceholderRow() }
        }
        is GroupPageUiState.CouldNotLoad -> PageFrame(title = state.title, onBack = back, modifier = modifier) {
            EmptyState(
                icon = AppIcon.Alert,
                title = stringResource(R.string.grouppage_error_title),
                body = stringResource(R.string.grouppage_error_body),
                action = {
                    AppButton(
                        label = stringResource(R.string.grouppage_try_again),
                        onClick = { onEvent(GroupPageEvent.Reload) },
                        size = ButtonSize.Medium,
                    )
                },
            )
        }
        // Drawn for the moment it takes to leave.
        GroupPageUiState.Gone -> PageFrame(title = "", onBack = back, modifier = modifier) {}
        is GroupPageUiState.Shown -> ShownPage(state, onEvent, modifier)
    }
}

@Composable
private fun ShownPage(state: GroupPageUiState.Shown, onEvent: (GroupPageEvent) -> Unit, modifier: Modifier) {
    val footer = state.footer
    // A result banner (Reminder sent, offline, could not do that) shows at the top of the body, while Remind and the
    // other actions sit lower down: bring the top back into view so it is seen (a jump with Remove animations on).
    val scroll = rememberScrollState()
    val reduced = LocalReducedMotion.current
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            if (reduced) scroll.scrollTo(0) else scroll.animateScrollTo(0, Motion.Spring.Default.spec())
        }
    }
    val firstBalances = rememberFirstKeys(state.balances.map { it.memberId })
    val firstRows = rememberFirstKeys(state.notSaved.map { "refused-${it.id}" } + state.expenses.map { it.id })
    val firstJoins = rememberFirstKeys(state.joins.map { it.memberId })
    val firstUndone = rememberFirstKeys(state.undone)
    PageFrame(
        title = state.title,
        onBack = { onEvent(GroupPageEvent.Back) },
        modifier = modifier,
        actionLabel = stringResource(R.string.grouppage_add),
        onAction = { onEvent(GroupPageEvent.Add) },
        footer = footer?.let { { Footer(it, onEvent) } },
        scrollState = scroll,
    ) {
        RevealValue(state.notice) { NoticeBanner(it) }
        state.joins.forEach { join ->
            key(join.memberId) {
                AddedRow(added = join.memberId !in firstJoins) {
                    Banner(
                        title = stringResource(R.string.grouppage_joined_title, join.joiner),
                        subtitle = stringResource(R.string.grouppage_joined_body, join.placeName),
                        icon = AppIcon.UserPlus,
                    ) {
                        AppButton(
                            label = stringResource(R.string.grouppage_undo),
                            onClick = { onEvent(GroupPageEvent.UndoJoin(join.memberId)) },
                            style = ButtonStyle.Secondary,
                            size = ButtonSize.Medium,
                        )
                    }
                }
            }
        }
        state.undone.forEach { friend ->
            key(friend) {
                AddedRow(added = friend !in firstUndone) {
                    Banner(
                        title = stringResource(R.string.grouppage_undone_title),
                        subtitle = stringResource(R.string.grouppage_undone_body, friend),
                        icon = AppIcon.CheckCircle,
                        tone = BannerTone.Plain,
                    )
                }
            }
        }
        GroupBalanceCard(state.card)
        if (state.balances.isNotEmpty()) {
            SectionLabel(text = stringResource(R.string.grouppage_balances))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RowGap)) {
                state.balances.forEach { row ->
                    key(row.memberId) { AddedRow(added = row.memberId !in firstBalances) { MemberBalance(row, onEvent) } }
                }
            }
        }
        if (state.notSaved.isNotEmpty() || state.expenses.isNotEmpty()) {
            SectionLabel(text = stringResource(R.string.grouppage_this_month))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RowGap)) {
                state.notSaved.forEach { row ->
                    val rowKey = "refused-${row.id}"
                    key(rowKey) { AddedRow(added = rowKey !in firstRows) { NotSaved(row, state, onEvent) } }
                }
                state.expenses.forEach { row ->
                    key(row.id) { AddedRow(added = row.id !in firstRows) { MonthExpense(row, state.today, onEvent) } }
                }
            }
        }
        SettingRow(
            title = stringResource(R.string.grouppage_settings_title),
            subtitle = stringResource(if (state.isDirect) R.string.grouppage_settings_row_body_direct else R.string.grouppage_settings_row_body),
            icon = AppIcon.Users,
            onClick = { onEvent(GroupPageEvent.OpenSettings) },
        )
    }
}

/** "FLAT 302 OWES YOU ₹2,350" / "YOU OWE GOA TRIP ₹800" with "3 people · 12 expenses in Nov". */
@Composable
private fun GroupBalanceCard(card: GroupCard) {
    val (label, tone) = when {
        card.netMinor > 0 -> stringResource(R.string.grouppage_card_owes_you, card.name) to BalanceCardTone.Positive
        card.netMinor < 0 -> stringResource(R.string.grouppage_card_you_owe, card.name) to BalanceCardTone.Negative
        else -> stringResource(R.string.grouppage_card_settled, card.name) to BalanceCardTone.Neutral
    }
    val locale = LocalConfiguration.current.locales[0]
    val monthFormat = remember(locale) { DateTimeFormatter.ofPattern("MMM", locale) }
    BalanceCard(
        label = label,
        amount = formatRupees(abs(card.netMinor)),
        caption = stringResource(
            R.string.grouppage_card_caption,
            pluralStringResource(R.plurals.grouppage_card_people, card.people, card.people),
            pluralStringResource(R.plurals.grouppage_card_expenses, card.expensesThisMonth, card.expensesThisMonth, card.month.format(monthFormat)),
        ),
        tone = tone,
    )
}

@Composable
private fun MemberBalance(row: MemberBalanceRow, onEvent: (GroupPageEvent) -> Unit) {
    val (detail, direction) = when (row.kind) {
        BalanceKind.OwesYou -> R.string.grouppage_balance_owes_you to BalanceRowDirection.OwesYou
        BalanceKind.YouOwe -> R.string.grouppage_balance_you_owe to BalanceRowDirection.YouOwe
        BalanceKind.AllSettled -> R.string.grouppage_balance_settled to BalanceRowDirection.Settled
        BalanceKind.SettlesWithOthers -> R.string.grouppage_balance_settles_with_others to BalanceRowDirection.Settled
    }
    val amount = when (row.kind) {
        BalanceKind.OwesYou -> "+" + formatRupees(row.amountMinor)
        BalanceKind.YouOwe -> formatRupees(-row.amountMinor)
        else -> formatRupees(0)
    }
    BalanceRow(
        name = row.name,
        detail = stringResource(detail),
        amount = amount,
        initial = initialOf(row.name),
        direction = direction,
        avatarColor = row.avatarColor,
        onClick = row.target?.let { target -> { onEvent(GroupPageEvent.Settle(target)) } },
    )
}

@Composable
private fun MonthExpense(row: MonthExpenseRow, today: LocalDate, onEvent: (GroupPageEvent) -> Unit) {
    ExpenseRow(
        title = row.description,
        subtitle = stringResource(R.string.grouppage_row_subtitle, dayText(row.date, today), row.method.text()),
        amount = formatRupees(row.amountMinor),
        meta = if (row.paidByMe) stringResource(R.string.grouppage_paid_by_you) else stringResource(R.string.grouppage_paid_by, row.payerName),
        icon = row.category.icon,
        kind = ExpenseRowKind.Shared,
        onClick = if (row.opensDetail) ({ onEvent(GroupPageEvent.OpenExpense(row.id)) }) else null,
    )
}

/**
 * A refused change (43, 90, 91): the Review row, then its note with Try again and Dismiss, 8 apart as drawn. A change
 * that cannot be tried again has no Try again; its note says why instead ([notSavedNote]), so the row never shows
 * neither.
 */
@Composable
private fun NotSaved(row: NotSavedRow, state: GroupPageUiState.Shown, onEvent: (GroupPageEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RowGap)) {
        ExpenseRow(
            title = row.description,
            subtitle = stringResource(R.string.grouppage_row_subtitle, dayText(row.date, state.today), row.method.text()),
            amount = formatRupees(row.amountMinor),
            meta = stringResource(R.string.grouppage_not_saved),
            icon = row.category.icon,
            kind = ExpenseRowKind.Review,
        )
        AppText(
            text = notSavedNote(row, state.title),
            style = AppTheme.type.caption,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
            if (row.canRetry) {
                AppButton(
                    label = stringResource(R.string.grouppage_try_again),
                    onClick = { onEvent(GroupPageEvent.RetryChange(row.id)) },
                    style = ButtonStyle.Secondary,
                    size = ButtonSize.Medium,
                )
            }
            AppButton(
                label = stringResource(R.string.grouppage_dismiss),
                onClick = { onEvent(GroupPageEvent.DismissChange(row.id)) },
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        }
    }
}

/**
 * What a "Not saved" row says under it: what was refused and why. A change that can be tried again reads as drawn
 * ("Not added to Flat 302. The group changed before it was sent."); one that cannot says why there is no Try again.
 *
 * @param row the refused change.
 * @param groupName the group's name; for a direct split, the friend's name.
 */
@Composable
private fun notSavedNote(row: NotSavedRow, groupName: String): String = when (row.noRetry) {
    null -> when (row.op) {
        OutboxOp.UPDATE -> stringResource(R.string.grouppage_not_saved_edit)
        OutboxOp.DELETE -> stringResource(R.string.grouppage_not_saved_delete)
        else -> stringResource(R.string.grouppage_not_saved_create, groupName)
    }
    NoRetryReason.SomeoneLeft -> when (row.op) {
        OutboxOp.UPDATE -> stringResource(R.string.grouppage_not_saved_edit_someone_left)
        else -> stringResource(R.string.grouppage_not_saved_create_someone_left, groupName)
    }
    NoRetryReason.Other -> when (row.op) {
        OutboxOp.UPDATE -> stringResource(R.string.grouppage_not_saved_edit_no_retry)
        OutboxOp.DELETE -> stringResource(R.string.grouppage_not_saved_delete_no_retry)
        else -> stringResource(R.string.grouppage_not_saved_create_no_retry, groupName)
    }
}

/**
 * "Settle up" and, when someone owes the person, "Remind", side by side and equally wide.
 *
 * The screen frames (24, 40 to 43 and the later Group page states) let both Button instances hug their height: a 52
 * face and the 5 kept for its edge, 57 in all, the same for Primary and Secondary. With the footer's 16 above and 8
 * below, the footer is 81 high, like the other two-button footers.
 */
@Composable
private fun Footer(footer: GroupFooter, onEvent: (GroupPageEvent) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
        AppButton(
            label = stringResource(R.string.grouppage_settle_up),
            onClick = { onEvent(GroupPageEvent.Settle(footer.settleUp)) },
            modifier = Modifier.weight(1f),
        )
        if (footer.canRemind) {
            AppButton(
                label = stringResource(R.string.grouppage_remind),
                onClick = { onEvent(GroupPageEvent.Remind) },
                modifier = Modifier.weight(1f),
                style = ButtonStyle.Secondary,
            )
        }
    }
}

/** "Today", "1 Nov", or "1 Nov 2025" for another year. */
@Composable
private fun dayText(date: LocalDate, today: LocalDate): String {
    if (date == today) return stringResource(R.string.grouppage_row_today)
    val locale = LocalConfiguration.current.locales[0]
    val pattern = if (date.year == today.year) "d MMM" else "d MMM yyyy"
    val format = remember(locale, pattern) { DateTimeFormatter.ofPattern(pattern, locale) }
    return date.format(format)
}

/** "equally", "amounts", "%", "shares", or "splits itself". */
@Composable
private fun RowMethod.text(): String = when (this) {
    RowMethod.Auto -> stringResource(R.string.grouppage_method_auto)
    is RowMethod.Split -> stringResource(
        when (method) {
            SplitMethod.EQUAL -> R.string.grouppage_method_equal
            SplitMethod.AMOUNTS -> R.string.grouppage_method_amounts
            SplitMethod.PERCENT -> R.string.grouppage_method_percent
            SplitMethod.SHARES -> R.string.grouppage_method_shares
        },
    )
}

/** The last shown page, drawn while the screen leaves after the group is gone; a plain holder. */
private class LastPage {
    var page: GroupPageUiState.Shown? = null
}
