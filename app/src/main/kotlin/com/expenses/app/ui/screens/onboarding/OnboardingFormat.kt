package com.expenses.app.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A day as the onboarding screens write it, for example "1 Nov", in the phone's language. */
@Composable
internal fun formatShortDate(date: LocalDate): String {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ENGLISH
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    return formatter.format(date)
}
