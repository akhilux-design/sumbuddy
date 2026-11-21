package com.expenses.app.ui.nav

import androidx.compose.animation.core.spring
import androidx.compose.runtime.MonotonicFrameClock
import androidx.navigation.compose.NavBackStackEntryInfo
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpringBack]'s relay (`docs/motion.md`, "Predictive back"): a swipe the NavHost previews is passed on as it comes,
 * and once the finger lets go its progress springs on to 1, frame by frame, before the swipe completes. Everything
 * else is passed on at once. Driven by a real [NavigationEventDispatcher] and a 16 ms test frame clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpringBackTest {
    /** A frame every 16 ms of test time. */
    private class FrameClock : MonotonicFrameClock {
        var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(FRAME_MILLIS)
            nanos += FRAME_MILLIS * 1_000_000
            return onFrame(nanos)
        }
    }

    /** A handler of the content that writes down what reaches it. */
    private class Recorder(info: NavigationEventInfo) : NavigationEventHandler<NavigationEventInfo>(info, true) {
        val log = mutableListOf<String>()
        val progress = mutableListOf<Float>()
        override fun onBackStarted(event: NavigationEvent) { log += "started" }
        override fun onBackProgressed(event: NavigationEvent) {
            log += "progressed"
            progress += event.progress
        }
        override fun onBackCompleted() { log += "completed" }
        override fun onBackCancelled() { log += "cancelled" }
    }

    private object ScreenBackHandler : NavigationEventInfo()

    private class Rig(scope: TestScope, settles: Boolean, info: NavigationEventInfo = NavBackStackEntryInfo()) {
        val outer = NavigationEventDispatcher()
        val system = DirectNavigationEventInput().also { outer.addInput(it) }
        var time = 0L
        val relay = BackSpringRelay(
            scope = scope + FrameClock(),
            settles = { settles },
            spec = { spring(dampingRatio = 0.9f, stiffness = 300f) },
            now = { time },
        ).also { outer.addHandler(it.handler) }
        val content = Recorder(info).also { relay.inner.addHandler(it) }

        /** A finger that moves the progress from 0 to [to] in 16 ms steps of 0.04, then stays. */
        fun swipe(to: Float) {
            system.backStarted(NavigationEvent(progress = 0f, swipeEdge = NavigationEvent.EDGE_LEFT))
            var p = 0f
            while (p < to - 1e-4f) {
                p = minOf(to, p + 0.04f)
                time += FRAME_MILLIS
                system.backProgressed(NavigationEvent(progress = p, swipeEdge = NavigationEvent.EDGE_LEFT))
            }
        }
    }

    @Test fun aPreviewedSwipeSpringsOnToTheEndBeforeItCompletes() = runTest {
        val rig = Rig(this, settles = true)
        rig.swipe(to = 0.4f)
        val dragged = rig.content.progress.size
        rig.system.backCompleted()
        runCurrent()
        assertFalse("not completed while it settles", "completed" in rig.content.log)
        advanceUntilIdle()
        assertEquals("completed", rig.content.log.last())
        val settle = rig.content.progress.drop(dragged)
        assertTrue("it settles over several frames: $settle", settle.size >= 8)
        assertEquals(1f, settle.last(), 0f)
        assertTrue("it only moves on: $settle", settle.zipWithNext().all { (a, b) -> b >= a })
        // A spring, not a track: the steps first grow from the finger's speed, then shrink to nothing.
        val steps = settle.zipWithNext { a, b -> b - a }.drop(1) // the first frame is where the finger let go
        val fastest = steps.indexOf(steps.max())
        assertTrue("speeds up first: $steps", fastest > 0)
        assertTrue("slows to rest: $steps", steps.last() < steps[fastest] / 10)
    }

    @Test fun theSettleStartsWithTheFingersSpeed() = runTest {
        val rig = Rig(this, settles = true)
        rig.swipe(to = 0.4f)
        val dragged = rig.content.progress.size
        rig.system.backCompleted()
        advanceUntilIdle()
        // The settle's first frame is where the finger let go; the next is one frame on.
        assertEquals(0.4f, rig.content.progress[dragged], 1e-4f)
        val first = rig.content.progress[dragged + 1] - 0.4f
        // The finger moved 0.04 a frame. Starting at rest, the spring would move far less in its first frame.
        assertTrue("first step $first", first > 0.03f)
    }

    @Test fun aScreensOwnBackHandlerGetsTheSwipeAtOnce() = runTest {
        val rig = Rig(this, settles = true, info = ScreenBackHandler)
        rig.swipe(to = 0.4f)
        val dragged = rig.content.progress.size
        rig.system.backCompleted()
        assertEquals("completed", rig.content.log.last())
        assertEquals(dragged, rig.content.progress.size)
    }

    @Test fun aSwipeWithoutPreviewCompletesAtOnce() = runTest {
        val rig = Rig(this, settles = false) // tab to tab, or Remove animations
        rig.swipe(to = 0.4f)
        rig.system.backCompleted()
        assertEquals("completed", rig.content.log.last())
    }

    @Test fun aBackButtonIsAPlainPop() = runTest {
        val rig = Rig(this, settles = true)
        rig.system.backStarted(NavigationEvent(progress = 0f)) // Android 16 starts a swipe at 0 for the button
        rig.system.backCompleted()
        assertEquals(listOf("started", "completed"), rig.content.log)
        rig.system.backCompleted() // and older ones only complete
        assertEquals(listOf("started", "completed", "completed"), rig.content.log)
    }

    @Test fun aCancelAlreadySprungBackIsPassedOnAtOnce() = runTest {
        val rig = Rig(this, settles = true)
        rig.swipe(to = 0.3f)
        rig.system.backProgressed(NavigationEvent(progress = 0f)) // the system springs it back before cancelling
        rig.system.backCancelled()
        assertEquals("cancelled", rig.content.log.last())
    }

    @Test fun aCancelStillOutSpringsBackFirst() = runTest {
        val rig = Rig(this, settles = true)
        rig.swipe(to = 0.3f)
        rig.system.backCancelled()
        runCurrent()
        assertFalse("cancelled" in rig.content.log)
        advanceUntilIdle()
        assertEquals("cancelled", rig.content.log.last())
        assertEquals(0f, rig.content.progress.last(), 0f)
    }

    @Test fun finishNowEndsASettleAtOnce() = runTest {
        val rig = Rig(this, settles = true)
        rig.swipe(to = 0.4f)
        rig.system.backCompleted()
        runCurrent()
        rig.relay.finishNow() // the app goes to the background
        assertEquals("completed", rig.content.log.last())
        advanceUntilIdle()
        assertEquals(1, rig.content.log.count { it == "completed" })
    }

    /** A navigation made by code ends every settle first; with none running, or after one ended, it does nothing. */
    @Test fun endBackSettlesEndsARunningSettleOnce() = runTest {
        val rig = Rig(this, settles = true)
        endBackSettles()
        assertTrue("nothing to end", rig.content.log.isEmpty())
        rig.swipe(to = 0.4f)
        rig.system.backCompleted()
        runCurrent()
        endBackSettles()
        assertEquals("completed", rig.content.log.last())
        val ended = rig.content.log.size
        advanceUntilIdle()
        endBackSettles()
        assertEquals("no frame and no second completion after it ended", ended, rig.content.log.size)
    }

    /** A settle that ended by itself is no longer ended by a later navigation. */
    @Test fun aSettleThatEndedIsNotEndedAgain() = runTest {
        val rig = Rig(this, settles = true)
        rig.swipe(to = 0.4f)
        rig.system.backCompleted()
        advanceUntilIdle()
        val ended = rig.content.log.size
        endBackSettles()
        assertEquals(ended, rig.content.log.size)
        assertEquals(1, rig.content.log.count { it == "completed" })
    }

    @Test fun aNewSwipeEndsTheSettleFirst() = runTest {
        val rig = Rig(this, settles = true)
        rig.swipe(to = 0.4f)
        rig.system.backCompleted()
        runCurrent()
        rig.swipe(to = 0.2f)
        assertEquals(listOf("completed", "started"), rig.content.log.filter { it != "progressed" }.drop(1).take(2))
        advanceUntilIdle()
    }

    @Test fun theRelayTakesBackOnlyWhileTheContentDoes() = runTest {
        val rig = Rig(this, settles = true)
        assertTrue(rig.relay.handler.isBackEnabled)
        rig.content.isBackEnabled = false
        assertFalse(rig.relay.handler.isBackEnabled)
        rig.content.isBackEnabled = true
        assertTrue(rig.relay.handler.isBackEnabled)
    }

    @Test fun speedReadsTheLastStretchInTheDirectionOfTheEnd() {
        val speed = ProgressSpeed()
        assertEquals(0f, speed.perSecond(towards = 1f), 0f)
        speed.add(0, 0.0f)
        speed.add(500, 0.1f) // long ago: dropped once newer events come
        speed.add(550, 0.2f)
        speed.add(600, 0.3f)
        assertEquals(2f, speed.perSecond(towards = 1f), 1e-4f) // 0.2 in 100 ms
        assertEquals(0f, speed.perSecond(towards = 0f), 0f) // moving away from 0
        speed.add(700, 0.1f)
        assertEquals(0f, speed.perSecond(towards = 1f), 0f)
        assertEquals(-2f, speed.perSecond(towards = 0f), 1e-4f)
    }

    @Test fun speedIsCapped() {
        val speed = ProgressSpeed()
        speed.add(0, 0f)
        speed.add(10, 0.5f)
        assertEquals(ProgressSpeed.MAX_PER_SECOND, speed.perSecond(towards = 1f), 0f)
    }

    private companion object {
        const val FRAME_MILLIS = 16L
    }
}
