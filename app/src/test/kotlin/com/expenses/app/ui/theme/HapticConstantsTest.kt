package com.expenses.app.ui.theme

import androidx.core.view.HapticFeedbackConstantsCompat as C
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each haptic plays the constant of the design's haptics table, with its older-phone fallback. */
class HapticConstantsTest {
    @Test fun tokensAreTheDesignNames() {
        assertEquals(
            listOf(
                "haptic/press", "haptic/selection", "haptic/toggle-on", "haptic/toggle-off", "haptic/threshold-in",
                "haptic/threshold-out", "haptic/confirm", "haptic/reject",
            ),
            Haptic.entries.map { it.token },
        )
    }

    @Test fun onAndroid14AndLaterEachHapticHasItsOwnConstant() {
        val expected = mapOf(
            Haptic.Press to C.VIRTUAL_KEY,
            Haptic.Selection to C.SEGMENT_TICK,
            Haptic.ToggleOn to C.TOGGLE_ON,
            Haptic.ToggleOff to C.TOGGLE_OFF,
            Haptic.ThresholdIn to C.GESTURE_THRESHOLD_ACTIVATE,
            Haptic.ThresholdOut to C.GESTURE_THRESHOLD_DEACTIVATE,
            Haptic.Confirm to C.CONFIRM,
            Haptic.Reject to C.REJECT,
        )
        listOf(34, 36).forEach { sdk -> assertEquals(expected, Haptic.entries.associateWith { it.androidConstant(sdk) }) }
    }

    @Test fun belowAndroid14TheTicksFallBack() {
        val expected = mapOf(
            Haptic.Press to C.VIRTUAL_KEY,
            Haptic.Selection to C.CONTEXT_CLICK,
            Haptic.ToggleOn to C.CONTEXT_CLICK,
            Haptic.ToggleOff to C.CLOCK_TICK,
            Haptic.ThresholdIn to C.CONTEXT_CLICK,
            Haptic.ThresholdOut to C.CLOCK_TICK,
            Haptic.Confirm to C.CONFIRM,
            Haptic.Reject to C.REJECT,
        )
        listOf(30, 33).forEach { sdk -> assertEquals(expected, Haptic.entries.associateWith { it.androidConstant(sdk) }) }
    }

    @Test fun belowAndroid11ConfirmAndRejectFallBack() {
        listOf(26, 29).forEach { sdk ->
            assertEquals(C.VIRTUAL_KEY, Haptic.Confirm.androidConstant(sdk))
            assertEquals(C.LONG_PRESS, Haptic.Reject.androidConstant(sdk))
            assertEquals(C.CONTEXT_CLICK, Haptic.Selection.androidConstant(sdk))
        }
    }
}
