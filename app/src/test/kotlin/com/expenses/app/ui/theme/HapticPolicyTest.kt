package com.expenses.app.ui.theme

import com.expenses.app.ui.theme.HapticPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** The haptic rules of `design/spec/motion.md` ("Haptic rules"), on a fake clock. */
class HapticPolicyTest {
    private var now = 10_000L
    private val policy = HapticPolicy { now }

    private fun at(millis: Long, haptic: Haptic): Decision {
        now = millis
        return policy.decide(haptic)
    }

    @Test fun aFirstTickPlays() {
        assertEquals(Decision.Play, at(10_000, Haptic.Selection))
    }

    @Test fun aTickUnder80MsAfterAnotherHapticIsDropped() {
        assertEquals(Decision.Play, at(10_000, Haptic.Press))
        assertEquals(Decision.Drop, at(10_079, Haptic.Selection))
        assertEquals(Decision.Drop, at(10_040, Haptic.ToggleOn))
    }

    @Test fun aTick80MsOrMoreLaterPlays() {
        assertEquals(Decision.Play, at(10_000, Haptic.ThresholdIn))
        assertEquals(Decision.Play, at(10_080, Haptic.ThresholdOut))
        assertEquals(Decision.Play, at(10_500, Haptic.ToggleOff))
    }

    @Test fun aDroppedTickDoesNotPushTheWindowOn() {
        assertEquals(Decision.Play, at(10_000, Haptic.Selection))
        assertEquals(Decision.Drop, at(10_050, Haptic.Selection))
        // 80 ms after the one that played, not after the dropped one.
        assertEquals(Decision.Play, at(10_080, Haptic.Selection))
    }

    @Test fun everyTickKindIsThrottled() {
        Haptic.entries.filter { it.isTick }.forEachIndexed { i, tick ->
            val start = 20_000L + i * 1_000L
            assertEquals(Decision.Play, at(start, Haptic.Reject))
            assertEquals("$tick", Decision.Drop, at(start + 10, tick))
        }
    }

    @Test fun rejectIsNeverDropped() {
        assertEquals(Decision.Play, at(10_000, Haptic.Press))
        assertEquals(Decision.Play, at(10_001, Haptic.Reject))
        assertEquals(Decision.Play, at(10_002, Haptic.Reject))
    }

    @Test fun confirmIsNeverDropped() {
        assertEquals(Decision.Play, at(10_000, Haptic.Selection))
        assertEquals(Decision.Play, at(10_001, Haptic.Confirm))
        assertEquals(Decision.Play, at(10_002, Haptic.Confirm))
    }

    @Test fun confirmComesAtLeast250MsAfterAPress() {
        assertEquals(Decision.Play, at(10_000, Haptic.Press))
        assertEquals(Decision.PlayAfter(250), at(10_000, Haptic.Confirm))
        assertEquals(Decision.PlayAfter(150), at(10_100, Haptic.Confirm))
    }

    @Test fun confirmLongAfterAPressPlaysAtOnce() {
        assertEquals(Decision.Play, at(10_000, Haptic.Press))
        assertEquals(Decision.Play, at(10_250, Haptic.Confirm))
        assertEquals(Decision.Play, at(12_000, Haptic.Confirm))
    }

    @Test fun confirmWaitsForTheLastPressOnly() {
        assertEquals(Decision.Play, at(10_000, Haptic.Press))
        assertEquals(Decision.Play, at(10_200, Haptic.Press))
        assertEquals(Decision.PlayAfter(200), at(10_250, Haptic.Confirm))
    }

    @Test fun aDroppedPressDoesNotDelayConfirm() {
        assertEquals(Decision.Play, at(10_000, Haptic.Selection))
        assertEquals(Decision.Drop, at(10_010, Haptic.Press))
        assertEquals(Decision.Play, at(10_100, Haptic.Confirm))
    }

    @Test fun aTickJustBeforeADelayedConfirmIsDropped() {
        assertEquals(Decision.Play, at(10_000, Haptic.Press))
        assertEquals(Decision.PlayAfter(240), at(10_010, Haptic.Confirm))
        // The confirm plays at 10 250: a tick 30 ms after it would blur into it.
        assertEquals(Decision.Drop, at(10_280, Haptic.Selection))
        assertEquals(Decision.Play, at(10_330, Haptic.Selection))
    }

    @Test fun onlyConfirmAndRejectAreNotTicks() {
        assertEquals(listOf(Haptic.Confirm, Haptic.Reject), Haptic.entries.filterNot { it.isTick })
    }
}
