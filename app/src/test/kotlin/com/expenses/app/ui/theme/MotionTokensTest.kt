package com.expenses.app.ui.theme

import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The motion tokens are the values of `design/spec/motion.md`. */
class MotionTokensTest {
    @Test fun springsMatchTheDesignTable() {
        // token, damping ratio, stiffness, Figma damping
        val design = listOf(
            listOf("motion/spring/press", 1.0f, 3800f, 123.3f),
            listOf("motion/spring/press-release", 0.5f, 1500f, 38.7f),
            listOf("motion/spring/fast", 0.9f, 1400f, 67.3f),
            listOf("motion/spring/pill", 0.8f, 1400f, 59.9f),
            listOf("motion/spring/default", 0.9f, 700f, 47.6f),
            listOf("motion/spring/screen", 0.9f, 300f, 31.2f),
            listOf("motion/spring/pop", 0.5f, 600f, 24.5f),
            listOf("motion/spring/shake", 0.2f, 1200f, 13.9f),
        )
        assertEquals(design.map { it[0] }, Motion.Spring.all.map { it.token })
        Motion.Spring.all.zip(design).forEach { (spring, row) ->
            assertEquals(spring.token, row[1] as Float, spring.dampingRatio, 0f)
            assertEquals(spring.token, row[2] as Float, spring.stiffness, 0f)
            assertEquals(spring.token, row[3] as Float, spring.figmaDamping, 0.06f)
            val spec = spring.spec<Float>()
            assertEquals(spring.dampingRatio, spec.dampingRatio, 0f)
            assertEquals(spring.stiffness, spec.stiffness, 0f)
        }
    }

    @Test fun durationsDistancesAndScalesMatchTheDesign() {
        assertEquals(60, Motion.Duration.Press)
        assertEquals(150, Motion.Duration.Short)
        assertEquals(250, Motion.Duration.Medium)
        assertEquals(300, Motion.Duration.Long)
        assertEquals(350, Motion.Duration.Cap)
        assertEquals(100L, Motion.PressHoldMillis)
        assertEquals(200, Motion.PlaceholderDelayMillis)
        assertEquals(listOf(90, 160, 90), listOf(Motion.FadeThrough.OutMillis, Motion.FadeThrough.InMillis, Motion.FadeThrough.InDelayMillis))
        assertEquals(30.dp, Motion.AxisDistance)
        assertEquals(4.dp, Motion.PressDistance)
        assertEquals(24.dp, Motion.RiseDistance)
        assertEquals(8.dp, Motion.ShakeDistance)
        assertEquals(0.125f, Motion.ModalRiseFraction, 0f)
        assertEquals(0.96f, Motion.EnterScale, 0f)
        assertEquals(0.94f, Motion.DialogScale, 0f)
        assertEquals(0.90f, Motion.PredictiveScale, 0f)
        assertEquals(0.5f, Motion.PopScale, 0f)
        assertEquals(0.10f, Motion.PressedOverlayAlpha, 0f)
    }

    @Test fun noFadeIsLongerThanTheCap() {
        val long = Motion.fade<Float>(500)
        assertEquals(350, long.durationMillis)
        val delayed: TweenSpec<Float> = Motion.fade(300, delayMillis = 90)
        assertEquals(90, delayed.delay)
        assertEquals(260, delayed.durationMillis)
        assertEquals(150, Motion.fade<Float>(150).durationMillis)
    }
}
