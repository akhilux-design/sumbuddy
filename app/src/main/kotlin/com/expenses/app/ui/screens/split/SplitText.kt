package com.expenses.app.ui.screens.split

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.screens.payment.monthName
import com.expenses.app.ui.screens.payment.shortDate
import com.expenses.app.ui.screens.payment.sourceText
import com.expenses.splits.SplitMethod
import java.time.LocalDate
import java.time.YearMonth

// Text the split screens build from their state. Kept apart from the ViewModels, which hold no resources.

/** The day as "Today", "Yesterday" or "14 Nov", as the payment row on Split it writes it. */
@Composable
internal fun splitDayText(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.payment_day_today)
    today.minusDays(1) -> stringResource(R.string.payment_day_yesterday)
    else -> shortDate(date, today)
}

/** The payment row's second line: "Today · HDFC card ••4521". */
@Composable
internal fun splitRowSubtitle(header: SplitHeader): String =
    stringResource(R.string.split_row_subtitle, splitDayText(header.date, header.today), sourceText(header.account, header.paidWith))

/** "Equally", "Amounts", "%" or "Shares": the "How" chips. */
@Composable
internal fun methodChipText(method: SplitMethod): String = stringResource(
    when (method) {
        SplitMethod.EQUAL -> R.string.split_method_equally
        SplitMethod.AMOUNTS -> R.string.split_method_amounts
        SplitMethod.PERCENT -> R.string.split_method_percent
        SplitMethod.SHARES -> R.string.split_method_shares
    },
)

/** "equally", "by amounts", "by percentage" or "by shares", as in "Flat 302, equally". */
@Composable
internal fun methodRuleText(method: SplitMethod): String = stringResource(
    when (method) {
        SplitMethod.EQUAL -> R.string.split_rule_equally
        SplitMethod.AMOUNTS -> R.string.split_rule_amounts
        SplitMethod.PERCENT -> R.string.split_rule_percent
        SplitMethod.SHARES -> R.string.split_rule_shares
    },
)

/** The line under the people for [note]. */
@Composable
internal fun splitNoteText(note: SplitNote, month: YearMonth): String {
    val monthName = monthName(month)
    return when (note) {
        is SplitNote.YourShare -> stringResource(R.string.split_note_your_share, formatRupees(note.myShareMinor), monthName)
        is SplitNote.AmountsAddUp -> stringResource(
            R.string.split_note_amounts_add_up,
            formatRupees(note.totalMinor),
            formatRupees(note.myShareMinor),
            monthName,
        )
        is SplitNote.LeftToAssign -> stringResource(R.string.split_note_left, formatRupees(note.leftMinor), formatRupees(note.totalMinor))
        is SplitNote.TooMuch -> stringResource(R.string.split_note_too_much, formatRupees(note.sumMinor), formatRupees(note.totalMinor))
        is SplitNote.PercentLeft -> stringResource(R.string.split_note_percent_left, SplitParts.percentText(note.basisPoints))
        is SplitNote.PercentTooMuch -> stringResource(R.string.split_note_percent_too_much, SplitParts.percentText(note.basisPoints))
        SplitNote.ZeroPart -> stringResource(R.string.split_note_zero_part)
        SplitNote.NotRight -> stringResource(R.string.split_note_not_right)
    }
}

/** Names in a sentence: "Rahul", "Rahul and Priya", "Rahul, Priya and Sam". */
@Composable
internal fun namesText(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names.first()
    else -> {
        val head = names.dropLast(1).reduce { acc, name -> stringResource(R.string.split_names_comma, acc, name) }
        stringResource(R.string.split_names_and, head, names.last())
    }
}
