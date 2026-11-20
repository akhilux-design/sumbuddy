package com.expenses.app.ui.motion

import com.expenses.app.ui.motion.PressTiming.End
import com.expenses.app.ui.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Test

/** A press shows for at least 100 ms from its start (`motion/hold/press`); a long press and a cancel hold nothing. */
class PressTimingTest {
    @Test fun theHoldIsTheDesignToken() {
        assertEquals(100L, Motion.PressHoldMillis)
    }

    @Test fun aSameFrameTapHoldsTheWholePress() {
        // Press and Release arrived before the press's first frame (a quick tap inside a scroll body).
        assertEquals(100L, PressTiming.holdLeft(End.Release, pressedAtMillis = null, endedAtMillis = 5_000L))
    }

    @Test fun aTapReleasedInTheSameFrameAsItsPressHoldsTheWholePress() {
        assertEquals(100L, PressTiming.holdLeft(End.Release, pressedAtMillis = 5_000L, endedAtMillis = 5_000L))
    }

    @Test fun aShortTapHoldsWhatIsLeftOfTheHundredMilliseconds() {
        assertEquals(68L, PressTiming.holdLeft(End.Release, pressedAtMillis = 5_000L, endedAtMillis = 5_032L))
    }

    @Test fun aLongPressHoldsNothingExtra() {
        assertEquals(0L, PressTiming.holdLeft(End.Release, pressedAtMillis = 5_000L, endedAtMillis = 5_100L))
        assertEquals(0L, PressTiming.holdLeft(End.Release, pressedAtMillis = 5_000L, endedAtMillis = 6_500L))
    }

    @Test fun aCancelNeverHolds() {
        assertEquals(0L, PressTiming.holdLeft(End.Cancel, pressedAtMillis = null, endedAtMillis = 5_000L))
        assertEquals(0L, PressTiming.holdLeft(End.Cancel, pressedAtMillis = 5_000L, endedAtMillis = 5_010L))
    }
}
