package com.expenses.app.ui.screens.review

import org.junit.Assert.assertEquals
import org.junit.Test

/** The commit line with its 8 dp hysteresis, in px with a line at 100 and a hysteresis of 8. */
class CommitSideTest {
    private fun side(armed: Int, offset: Float) = commitSide(armed, offset, line = 100f, hysteresis = 8f)

    @Test
    fun armsAtTheLineOnEitherSide() {
        assertEquals(0, side(0, 99f))
        assertEquals(1, side(0, 100f))
        assertEquals(-1, side(0, -100f))
    }

    @Test
    fun staysArmedWithinTheHysteresis() {
        assertEquals(1, side(1, 93f))
        assertEquals(1, side(1, 92f))
        assertEquals(0, side(1, 91.9f))
        assertEquals(-1, side(-1, -92f))
    }

    @Test
    fun doesNotArmInsideTheHysteresisFromBelow() {
        assertEquals(0, side(0, 95f))
    }

    @Test
    fun crossingToTheOtherSideArmsThatSide() {
        assertEquals(-1, side(1, -120f))
        assertEquals(0, side(1, -50f))
        assertEquals(0, side(1, 0f))
    }
}
