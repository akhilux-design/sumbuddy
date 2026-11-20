package com.expenses.app.ui.screens.you

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseWithAccount
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.model.ReadOutcome
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.data.repository.ReadSmsRepository
import com.expenses.parser.Direction
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Holds What we've read: how many bank messages were used, the newest payments read from SMS (added or still
 * waiting), and how many other messages were skipped. The message text itself is never shown here.
 *
 * @param expenses the expenses, for the newest payments read from SMS.
 * @param readSms the counts of messages read, by outcome.
 * @param clock gives today and the time zone.
 */
class WhatWeReadViewModel(
    expenses: ExpenseRepository,
    readSms: ReadSmsRepository,
    private val clock: Clock,
) : ViewModel() {
    /** What the screen shows; null until the first read from the database. */
    val state: StateFlow<WhatWeReadUiState?> = combine(
        expenses.observeLatest(LATEST_READ),
        expenses.observeDetected(),
        readSms.observeCounts(),
    ) { added, detected, counts ->
        WhatWeReadUiState(
            sentOffPhone = 0,
            bankMessagesRead = counts.used,
            latestUsed = (added + detected)
                .filter { it.expense.source == ExpenseSource.SMS }
                .sortedByDescending { it.expense.smsReceivedAt ?: it.expense.occurredAt }
                .take(SHOWN)
                .map { it.toUsedMessage() },
            today = LocalDate.now(clock),
            skipped = counts.byOutcome[ReadOutcome.SKIPPED] ?: 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private fun ExpenseWithAccount.toUsedMessage(): UsedMessage = UsedMessage(
        expenseId = expense.id,
        title = account?.displayName ?: account?.bank,
        kind = when {
            expense.direction == Direction.CREDIT -> UsedMessageKind.Credit
            expense.paidWith == PaidWith.CARD -> UsedMessageKind.Card
            else -> UsedMessageKind.Debit
        },
        receivedAt = (expense.smsReceivedAt ?: expense.occurredAt).atZone(clock.zone).toLocalDateTime(),
        amountMinor = expense.amountMinor,
    )

    private companion object {
        /** How many of the newest added expenses are looked at to find the newest ones read from SMS. */
        const val LATEST_READ = 50

        /** How many used messages the screen lists. */
        const val SHOWN = 3

        /** How long the reads stay active after the screen stops watching, so a rotation does not restart them. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
