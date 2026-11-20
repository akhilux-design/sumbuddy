package com.expenses.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.ui.motion.AddedRow
import com.expenses.app.ui.motion.rememberFirstKeys
import androidx.compose.runtime.key
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.ProgressBar
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space
import java.time.LocalDate

/**
 * An account found while scanning, with the payments found for it so far.
 *
 * @param account the account.
 * @param payments how many new payments came from it.
 */
@Immutable
data class ScannedAccount(val account: AccountItem, val payments: Int)

/**
 * What Scanning SMS shows.
 *
 * @param since the first day read, or null while the settings load.
 * @param read messages read so far.
 * @param total messages to read, or 0 while the inbox is still being listed.
 * @param found accounts found so far, in the order they were first seen.
 * @param failed true when reading stopped with an error: the screen says so and offers "Try again".
 */
@Immutable
data class ScanningUiState(
    val since: LocalDate? = null,
    val read: Int = 0,
    val total: Int = 0,
    val found: List<ScannedAccount> = emptyList(),
    val failed: Boolean = false,
)

/**
 * Scanning SMS (design 01-onboarding/09-scanning-sms): a progress bar over the messages being read and the
 * accounts found so far. It has no buttons while reading; the next screen opens by itself when the scan ends. When
 * reading stopped with an error it says so and shows "Try again".
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param modifier layout changes from the caller.
 */
@Composable
fun ScanningScreen(state: ScanningUiState, onEvent: (ScanningEvent) -> Unit, modifier: Modifier = Modifier) {
    OnboardingFrame(
        modifier = modifier,
        bodyPadding = PaddingValues(horizontal = Space.s16, vertical = Space.s40),
        footer = if (state.failed) {
            {
                AppButton(
                    label = stringResource(R.string.onboarding_scanning_retry),
                    onClick = { onEvent(ScanningEvent.Retry) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            null
        },
    ) {
        StickerTile(icon = AppIcon.Search, tone = StickerTone.Brand)
        if (state.failed) {
            OnboardingHeadline(text = stringResource(R.string.onboarding_scanning_failed_title))
            OnboardingBody(text = stringResource(R.string.onboarding_scanning_failed_body))
            return@OnboardingFrame
        }
        OnboardingHeadline(text = stringResource(R.string.onboarding_scanning_title))
        if (state.since != null) {
            OnboardingBody(text = stringResource(R.string.onboarding_scanning_body, formatShortDate(state.since)))
        }
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            ProgressBar(done = state.read, total = state.total)
            AppText(
                text = if (state.total > 0) {
                    stringResource(R.string.onboarding_scanning_progress, state.read, state.total)
                } else {
                    stringResource(R.string.onboarding_scanning_starting)
                },
                style = AppTheme.type.caption,
                color = AppTheme.colors.textSecondary,
            )
        }
        // Accounts found while scanning open in one by one; the list starts empty.
        val firstFound = rememberFirstKeys(state.found.map { it.account })
        if (state.found.isNotEmpty()) {
            SectionLabel(text = stringResource(R.string.onboarding_scanning_found))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                state.found.forEach { found ->
                    key(found.account) { AddedRow(added = found.account !in firstFound) {
                        InfoRow(
                            title = accountNameWithDigits(found.account),
                            subtitle = pluralStringResource(R.plurals.onboarding_payments, found.payments, found.payments),
                            icon = found.account.type.icon,
                        )
                    } }
                }
            }
        }
    }
}
