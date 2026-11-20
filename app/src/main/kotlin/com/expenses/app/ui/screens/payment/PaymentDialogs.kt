package com.expenses.app.ui.screens.payment

import android.app.DatePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.util.Calendar

/**
 * The system date picker, shown while this is in the composition. Days after [latest] cannot be picked.
 *
 * @param initial the day the picker opens on.
 * @param latest the last day that can be picked, usually today.
 * @param onPicked called with the day the person picked.
 * @param onDismiss called when the picker closes, after [onPicked] or without a pick.
 */
@Composable
internal fun SystemDatePicker(
    initial: LocalDate,
    latest: LocalDate,
    onPicked: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val dismissed by rememberUpdatedState(onDismiss)
    DisposableEffect(context, initial, latest) {
        // The picker counts months from 0.
        val dialog = DatePickerDialog(
            context,
            { _, year, month, day -> picked(LocalDate.of(year, month + 1, day)) },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        )
        // The picker reads its limits in the phone's own calendar, so the last moment of [latest] is set there.
        dialog.datePicker.maxDate = Calendar.getInstance().run {
            clear()
            set(latest.year, latest.monthValue - 1, latest.dayOfMonth, LAST_HOUR, LAST_MINUTE, LAST_SECOND)
            timeInMillis
        }
        dialog.setOnDismissListener { dismissed() }
        dialog.show()
        onDispose {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
    }
}

private const val LAST_HOUR = 23
private const val LAST_MINUTE = 59
private const val LAST_SECOND = 59
