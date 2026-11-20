package com.expenses.app.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.repository.AccountRepository
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.data.settings.SettingsRepository
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where Scanning SMS goes when the scan ends. */
enum class ScanningEffect {
    /** No bank account or card was found: Nothing found. */
    NothingFound,

    /** Accounts were found: Confirm accounts. */
    ConfirmAccounts,
}

/** What the person can do on Scanning SMS. */
enum class ScanningEvent {
    /** "Try again" after the scan stopped with an error. */
    Retry,
}

/**
 * Runs the onboarding scan from the tracking start chosen on How far back and shows its progress with the
 * accounts found so far, each with its new detected payments. When the scan ends it says where to go next; when it
 * stops with an error the screen says so and offers to try again, so onboarding never goes on with half a scan.
 */
class ScanningViewModel(
    private val settings: SettingsRepository,
    private val accounts: AccountRepository,
    expenses: ExpenseRepository,
    private val scanner: OnboardingScanner,
    private val clock: Clock,
) : ViewModel() {
    private val effects = Effects<ScanningEffect>()
    private val since = MutableStateFlow<LocalDate?>(null)
    private val progress = MutableStateFlow(ScanProgress(read = 0, total = 0, finished = false))

    private val found: Flow<List<ScannedAccount>> = combine(
        accounts.observeAll(),
        expenses.observeDetected(),
    ) { all, detected ->
        val counts = detected.groupingBy { it.expense.accountId }.eachCount()
        all.map { ScannedAccount(AccountItem.from(it), counts[it.id] ?: 0) }
    }

    /** What the screen shows. */
    val state: StateFlow<ScanningUiState> = combine(since, progress, found) { day, scan, scanned ->
        ScanningUiState(since = day, read = scan.read, total = scan.total, found = scanned, failed = scan.failed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), ScanningUiState())

    /** Where to go once the scan ended. */
    val effect: Flow<ScanningEffect> = effects.flow

    init {
        scan()
    }

    /** Handles what the person did. */
    fun onEvent(event: ScanningEvent) {
        when (event) {
            ScanningEvent.Retry -> if (progress.value.failed) scan()
        }
    }

    private fun scan() {
        progress.value = ScanProgress(read = 0, total = 0, finished = false)
        viewModelScope.launch {
            val start = settings.settings.first().trackingStartAt
                ?: LocalDate.now(clock).withDayOfMonth(1).atStartOfDay(clock.zone).toInstant()
            since.value = start.atZone(clock.zone).toLocalDate()
            val end = scanner.start(start)
                .onEach { progress.value = it }
                .first { it.finished }
            if (end.failed) return@launch
            val next = if (accounts.observeAll().first().isEmpty()) {
                ScanningEffect.NothingFound
            } else {
                ScanningEffect.ConfirmAccounts
            }
            effects.send(next)
        }
    }

    private companion object {
        /** How long the state keeps updating after the screen stops listening, so a rotation does not restart it. */
        const val StopTimeoutMillis = 5_000L
    }
}
