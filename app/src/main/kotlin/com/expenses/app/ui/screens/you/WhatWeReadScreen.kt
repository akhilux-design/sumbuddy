package com.expenses.app.ui.screens.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.ui.components.BalanceCard
import com.expenses.app.ui.components.BalanceCardTone
import com.expenses.app.ui.components.ExpenseRow
import com.expenses.app.ui.components.ExpenseRowKind
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.format.formatCount
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What we've read (design 04-you/04-what-we-ve-read): a card that says how many messages left the phone (none)
 * and how many bank messages were used, the newest payments read from SMS, and how many other messages were
 * skipped without being kept. "See the code" is left out until the public link to the reading code exists.
 *
 * @param state what to show; see [WhatWeReadUiState].
 * @param modifier outer modifier. The screen fills the space it is given.
 * @param onEvent called with what the person tapped; see [WhatWeReadEvent].
 */
@Composable
fun WhatWeReadScreen(
    state: WhatWeReadUiState,
    modifier: Modifier = Modifier,
    onEvent: (WhatWeReadEvent) -> Unit = {},
) {
    YouDetailFrame(
        title = stringResource(R.string.you_what_we_read_title),
        onBack = { onEvent(WhatWeReadEvent.Back) },
        modifier = modifier,
    ) {
        BalanceCard(
            label = stringResource(R.string.you_read_sent_label),
            amount = state.sentOffPhone.toString(),
            caption = pluralStringResource(
                R.plurals.you_read_caption,
                state.bankMessagesRead,
                formatCount(state.bankMessagesRead),
            ),
            tone = BalanceCardTone.Neutral,
        )
        if (state.latestUsed.isNotEmpty()) {
            SectionLabel(text = stringResource(R.string.you_read_latest))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                state.latestUsed.forEach { message ->
                    UsedMessageRow(
                        message = message,
                        today = state.today,
                        onClick = { onEvent(WhatWeReadEvent.OpenPayment(message.expenseId)) },
                    )
                }
            }
        }
        SectionLabel(text = stringResource(R.string.you_read_skipped))
        InfoRow(
            title = stringResource(R.string.you_read_skipped_title),
            subtitle = pluralStringResource(R.plurals.you_read_skipped_count, state.skipped, formatCount(state.skipped)),
            icon = AppIcon.Ban,
        )
    }
}

/** One used message: the bank, the kind of alert and when it came, the amount and "Used". */
@Composable
private fun UsedMessageRow(message: UsedMessage, today: LocalDate, onClick: () -> Unit) {
    val kind = stringResource(
        when (message.kind) {
            UsedMessageKind.Debit -> R.string.you_read_kind_debit
            UsedMessageKind.Card -> R.string.you_read_kind_card
            UsedMessageKind.Credit -> R.string.you_read_kind_credit
        },
    )
    ExpenseRow(
        title = message.title ?: stringResource(R.string.you_read_unknown_bank),
        subtitle = stringResource(R.string.you_read_subtitle, kind, receivedLabel(message, today)),
        amount = formatRupees(message.amountMinor),
        meta = stringResource(R.string.you_read_used),
        icon = if (message.kind == UsedMessageKind.Card) AppIcon.Card else AppIcon.Bank,
        kind = ExpenseRowKind.Personal,
        onClick = onClick,
    )
}

/** When a message came, as the row writes it: "today 9:12", "yesterday" or "12 Nov". */
@Composable
private fun receivedLabel(message: UsedMessage, today: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val day = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    val date = message.receivedAt.toLocalDate()
    return when (date) {
        today -> stringResource(R.string.you_read_today, message.receivedAt.format(time))
        today.minusDays(1) -> stringResource(R.string.you_read_yesterday)
        else -> date.format(day)
    }
}
