package com.expenses.app.ui.screens.activity

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.ui.motion.AnimatedItem
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.ExpenseWithAccount
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.model.payee
import com.expenses.app.groups.data.SplitLabel
import com.expenses.app.ui.components.ExpenseRow
import com.expenses.app.ui.components.ExpenseRowKind
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.theme.Space
import com.expenses.parser.Direction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The list is spaced 8dp apart, the gap between the rows of one day. Day headings add this much above and below
 * themselves to make the 12dp gaps the design has around them.
 */
internal val SectionExtraGap = Space.s12 - Space.s8

/**
 * The row for one stored expense, as Activity, Home and Month view list it. Credits become
 * [ActivityItemDetail.MoneyIn], ignored payments [ActivityItemDetail.Ignored], Family expenses
 * [ActivityItemDetail.Pooled], a payment linked to a group expense found in [splitLabels] [ActivityItemDetail.Split]
 * ("Travel · your share of ₹558", the group's name under the amount), everything else
 * [ActivityItemDetail.Personal]. The amount is the person's share of a debit, or the whole amount of a credit.
 *
 * @param splitLabels `GroupsRepository.observeSplitLabels()`, so every list that shows rows (Activity, Home, Month
 * view) shows a split the same way. Empty when the person is signed out or splits nothing.
 */
internal fun ExpenseWithAccount.toActivityItem(splitLabels: Map<String, SplitLabel> = emptyMap()): ActivityItem {
    val paidWith = paidWithLabel()
    val split = expense.groupExpenseId?.let(splitLabels::get)
    val detail = when {
        expense.direction == Direction.CREDIT -> ActivityItemDetail.MoneyIn(paidWith)
        expense.status == ExpenseStatus.IGNORED -> ActivityItemDetail.Ignored(expense.category, paidWith)
        expense.scope == ExpenseScope.FAMILY -> ActivityItemDetail.Pooled(expense.category, paidWith, groupName = null)
        split != null -> ActivityItemDetail.Split(expense.category, split.totalMinor, split.groupName)
        else -> ActivityItemDetail.Personal(expense.category, paidWith)
    }
    return ActivityItem(
        id = ActivityItemId.Expense(expense.id),
        title = expense.payee,
        icon = expense.category.icon,
        amountPaise = if (expense.direction == Direction.CREDIT) expense.amountMinor else expense.myShareMinor,
        detail = detail,
    )
}

/**
 * How the expense was paid, as the row names it: the account or card for card payments and bank transfers when it
 * is known ("HDFC Bank ••4521"), otherwise the way of paying ("UPI").
 */
internal fun ExpenseWithAccount.paidWithLabel(): PaidWithLabel {
    val account = account
    val method = expense.paidWith
    val namesAccount = method == PaidWith.CARD || method == PaidWith.BANK_TRANSFER || method == PaidWith.ATM ||
        method == PaidWith.OTHER
    val name = account?.displayName ?: account?.bank
    return if (account != null && namesAccount && name != null && account.type != AccountType.UPI_ID) {
        PaidWithLabel.Account(name, account.last4)
    } else {
        PaidWithLabel.Method(method)
    }
}

/** The heading for [date] seen on [today]: Today, Yesterday or the date itself. */
internal fun activityDateOf(date: LocalDate, today: LocalDate): ActivityDate = when (date) {
    today -> ActivityDate.Today
    today.minusDays(1) -> ActivityDate.Yesterday
    else -> ActivityDate.Earlier(date, showYear = date.year != today.year)
}

/**
 * Groups [items] by the day each happened, newest day first and newest item first in each day.
 *
 * @param items the rows, each with the moment it happened.
 * @param zone the time zone that decides the day.
 * @param today the current day in [zone].
 */
internal fun groupByDay(
    items: List<Pair<ActivityItem, Instant>>,
    zone: ZoneId,
    today: LocalDate,
): List<ActivityDay> = items
    .sortedByDescending { it.second }
    .groupBy { it.second.atZone(zone).toLocalDate() }
    .map { (date, dayItems) -> ActivityDay(activityDateOf(date, today), dayItems.map { it.first }) }

/** Adds each day to a list: its heading, then its rows 8dp apart. */
internal fun LazyListScope.activityDays(days: List<ActivityDay>, onItemClick: (ActivityItemId) -> Unit) {
    days.forEach { day ->
        item(key = "day-${day.date}") {
            AnimatedItem {
                SectionLabel(
                    text = day.date.label(),
                    modifier = Modifier.padding(vertical = SectionExtraGap),
                )
            }
        }
        items(items = day.items, key = { "item-${it.id}" }) { item ->
            AnimatedItem {
                ActivityItemRow(item = item, onClick = { onItemClick(item.id) })
            }
        }
    }
}

/**
 * One payment as an [ExpenseRow], with the second line and the note worked out from its detail. Money in that names
 * nobody is titled "Money in", so its second line names only the account or way it came in ("UPI"), not "Money in"
 * again.
 */
@Composable
internal fun ActivityItemRow(item: ActivityItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val detail = item.detail
    val text = detail.rowText()
    val amount = item.amountPaise?.let { formatRupees(it) } ?: stringResource(R.string.activity_row_amount_unknown)
    ExpenseRow(
        title = item.title ?: stringResource(detail.untitled()),
        subtitle = if (item.title == null && detail is ActivityItemDetail.MoneyIn) detail.paidWith.text() else text.subtitle,
        amount = if (detail is ActivityItemDetail.MoneyIn) {
            stringResource(R.string.activity_row_money_in_amount, amount)
        } else {
            amount
        },
        meta = text.meta,
        modifier = modifier,
        icon = item.icon,
        kind = text.kind,
        onClick = onClick,
    )
}

/** The second line, the note under the amount and the look of one payment row. */
@Immutable
private class ActivityRowText(val subtitle: String, val meta: String, val kind: ExpenseRowKind)

@Composable
private fun ActivityItemDetail.rowText(): ActivityRowText = when (this) {
    is ActivityItemDetail.Personal -> ActivityRowText(
        subtitle = stringResource(R.string.activity_row_paid_with, stringResource(category.label), paidWith.text()),
        meta = stringResource(R.string.activity_row_just_you),
        kind = ExpenseRowKind.Personal,
    )

    is ActivityItemDetail.Split -> ActivityRowText(
        subtitle = stringResource(
            R.string.activity_row_your_share,
            stringResource(category.label),
            formatRupees(totalPaise),
        ),
        meta = groupName,
        kind = ExpenseRowKind.Shared,
    )

    is ActivityItemDetail.Pooled -> ActivityRowText(
        subtitle = stringResource(R.string.activity_row_paid_with, stringResource(category.label), paidWith.text()),
        meta = groupName ?: stringResource(R.string.activity_row_family),
        kind = ExpenseRowKind.Shared,
    )

    is ActivityItemDetail.MoneyIn -> ActivityRowText(
        subtitle = stringResource(
            R.string.activity_row_paid_with,
            stringResource(R.string.activity_row_money_in),
            paidWith.text(),
        ),
        meta = stringResource(R.string.activity_row_not_spending),
        kind = ExpenseRowKind.Personal,
    )

    is ActivityItemDetail.Ignored -> ActivityRowText(
        subtitle = stringResource(R.string.activity_row_paid_with, stringResource(category.label), paidWith.text()),
        meta = stringResource(R.string.activity_row_ignored),
        kind = ExpenseRowKind.Personal,
    )

    ActivityItemDetail.NeedsReview -> ActivityRowText(
        subtitle = stringResource(R.string.activity_row_review_subtitle),
        meta = stringResource(R.string.activity_row_review_meta),
        kind = ExpenseRowKind.Review,
    )
}

/** The title of a row whose payment names nobody. */
@StringRes
private fun ActivityItemDetail.untitled(): Int =
    if (this is ActivityItemDetail.MoneyIn) R.string.activity_row_money_in else R.string.activity_row_untitled

/** How a payment was made, as words: "UPI" or "HDFC Bank ••4521". */
@Composable
internal fun PaidWithLabel.text(): String = when (this) {
    is PaidWithLabel.Method -> stringResource(method.label())
    is PaidWithLabel.Account -> if (last4 != null) {
        stringResource(R.string.activity_paid_with_account, name, last4)
    } else {
        name
    }
}

/** The name of a way of paying. */
@StringRes
private fun PaidWith.label(): Int = when (this) {
    PaidWith.CASH -> R.string.activity_paid_with_cash
    PaidWith.UPI -> R.string.activity_paid_with_upi
    PaidWith.CARD -> R.string.activity_paid_with_card
    PaidWith.BANK_TRANSFER -> R.string.activity_paid_with_bank_transfer
    PaidWith.WALLET -> R.string.activity_paid_with_wallet
    PaidWith.ATM -> R.string.activity_paid_with_atm
    PaidWith.OTHER -> R.string.activity_paid_with_other
}

/** The heading of a day as written: "Today", "Yesterday", "12 Nov" or "12 Nov 2025". */
@Composable
internal fun ActivityDate.label(): String = when (this) {
    ActivityDate.Today -> stringResource(R.string.activity_day_today)
    ActivityDate.Yesterday -> stringResource(R.string.activity_day_yesterday)
    is ActivityDate.Earlier -> {
        val locale = LocalConfiguration.current.locales[0]
        val pattern = if (showYear) "d MMM yyyy" else "d MMM"
        val formatter = remember(locale, pattern) { DateTimeFormatter.ofPattern(pattern, locale) }
        date.format(formatter)
    }
}
