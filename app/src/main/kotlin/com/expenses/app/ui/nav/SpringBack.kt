package com.expenses.app.ui.nav

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.compose.NavBackStackEntryInfo
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventHistory
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Back swipes inside [content] follow the finger, then settle with a spring from where the finger let go
 * (`docs/motion.md`, owner rule 2 and "Predictive back").
 *
 * Why it is needed: once a back swipe is committed, the NavHost (navigation-compose 2.10.2) finishes the swipe's
 * transition with `SeekableTransitionState.animateTo(entry)` and no spec. That plays the rest of each predictive spec
 * at its own pace, a linear track here, so the screens glide on at one speed and stop dead. The NavHost takes no spec
 * for it. What it does take is the progress of each back event: it seeks its transition to it.
 *
 * So the back events reach [content] through this relay. It passes a swipe on as it comes. When the finger lets go
 * on a swipe that the NavHost shows as a preview ([settles]), it springs the progress on to 1 with
 * `motion/spring/screen` and the finger's speed, sends each frame as a back event, and only then says the swipe
 * completed; the NavHost then has nothing left to play. A cancelled swipe is already sprung back to 0 by the system
 * before the cancel comes (seen on Android 16); if the progress is still out when it comes, the relay springs it back
 * the same way.
 *
 * Everything else is passed on at once, as before: Back from a button or the keyboard (on Android 16 a button starts
 * a swipe at 0 and completes it at once; the screens never moved, so the NavHost plays its pop), a swipe that a
 * screen's own `BackHandler` takes, a swipe between tabs (no preview), and every swipe with Remove animations on.
 *
 * Inside [content], `BackHandler` and the NavHost register with the relay's dispatcher (it is provided as
 * [LocalNavigationEventDispatcherOwner]), so their order is as before. Do not put a `BackHandler` inside a `Dialog`
 * in [content]: it would register here, not with the dialog's window.
 *
 * @param settles whether the swipe starting now moves screens the NavHost previews (see [NavTransitions.previewsBack]).
 */
@Composable
fun SpringBack(settles: () -> Boolean, content: @Composable () -> Unit) {
    val outer = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    if (outer == null) {
        content()
        return
    }
    val scope = rememberCoroutineScope()
    val reduced by rememberUpdatedState(LocalReducedMotion.current)
    val previews by rememberUpdatedState(settles)
    val relay = remember(outer) {
        BackSpringRelay(
            scope = scope,
            settles = { !reduced && previews() },
            spec = { Motion.Spring.Screen.spec(PROGRESS_THRESHOLD) },
        )
    }
    DisposableEffect(outer, relay) {
        outer.addHandler(relay.handler)
        onDispose { relay.dispose() }
    }
    // Before the app goes to the background (and its back handlers are removed), a swipe still settling completes.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { relay.finishNow() }
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides relay.owner) { content() }
}

/**
 * The relay of [SpringBack], without Compose. [handler] goes on the outer dispatcher (the activity's); [owner] is what
 * the content registers with. [settles] is asked when a swipe starts; [spec] is the spring the progress settles with.
 * [now] is the clock used when a back event carries no frame time.
 */
internal class BackSpringRelay(
    private val scope: CoroutineScope,
    private val settles: () -> Boolean,
    private val spec: () -> AnimationSpec<Float>,
    private val now: () -> Long = SystemClock::uptimeMillis,
) {
    /** What the content registers its back handlers with. */
    val inner = NavigationEventDispatcher()

    /** Provides [inner] to the content. */
    val owner: NavigationEventDispatcherOwner = object : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher: NavigationEventDispatcher = inner
    }

    private val input = Input()

    /** Takes the back events from the outer dispatcher, while any handler of the content is enabled. */
    val handler: NavigationEventHandler<NavigationEventInfo> = Handler()

    /** The swipe in progress: its latest event and its speed. Null between swipes. */
    private var swipe: Swipe? = null

    /** The settle running after a swipe let go, and what ends it (completed or cancelled). */
    private var settle: Job? = null
    private var end: (() -> Unit)? = null

    init {
        inner.addInput(input)
    }

    /** Ends a settle that is still running, at once, as it would end. */
    fun finishNow() {
        settle?.cancel()
        settle = null
        val pending = end
        end = null
        pending?.invoke()
    }

    /** Ends a settle, then takes the relay out of the outer dispatcher. */
    fun dispose() {
        finishNow()
        handler.remove()
        inner.removeInput(input)
    }

    private class Swipe(val settles: Boolean, var last: NavigationEvent) {
        val speed = ProgressSpeed()

        /**
         * Whether the swipe moved the screens at all. A Back button on Android 16 starts a swipe at 0 and completes it
         * at once: that is a plain pop, so it is passed on at once and the NavHost plays the pop.
         */
        var moved = false
    }

    private fun timeOf(event: NavigationEvent): Long = event.frameTimeMillis.takeIf { it > 0 } ?: now()

    private fun onStarted(event: NavigationEvent) {
        finishNow()
        val swipe = Swipe(settles = navHostTakesIt() && settles(), last = event)
        swipe.speed.add(timeOf(event), event.progress)
        swipe.moved = event.progress >= MOVED_PROGRESS
        this.swipe = swipe
        input.started(event)
    }

    private fun onProgressed(event: NavigationEvent) {
        swipe?.let {
            it.last = event
            it.speed.add(timeOf(event), event.progress)
            if (event.progress >= MOVED_PROGRESS) it.moved = true
        }
        input.progressed(event)
    }

    private fun onEnded(completed: Boolean) {
        val swipe = swipe
        this.swipe = null
        val target = if (completed) 1f else 0f
        val pass = { if (completed) input.completed() else input.cancelled() }
        if (swipe == null || !swipe.settles || !swipe.moved || abs(swipe.last.progress - target) < PROGRESS_THRESHOLD) {
            pass()
            return
        }
        val from = swipe.last
        val speed = swipe.speed.perSecond(towards = target)
        end = pass
        settle = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            Animatable(from.progress, PROGRESS_THRESHOLD).animateTo(target, spec(), initialVelocity = speed) {
                input.progressed(from.at(value.coerceIn(0f, 1f)))
            }
            if (from.progress != target) input.progressed(from.at(target))
            settle = null
            val pending = end
            end = null
            pending?.invoke()
        }
    }

    /** Whether the content's handler that takes a swipe now is the NavHost's (not a screen's own `BackHandler`). */
    private fun navHostTakesIt(): Boolean {
        val history = inner.history.value
        return history.mergedHistory.getOrNull(history.currentIndex) is NavBackStackEntryInfo
    }

    private inner class Input : NavigationEventInput() {
        override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
            handler.isBackEnabled = hasEnabledHandlers
        }

        override fun onHistoryChanged(history: NavigationEventHistory) {
            val merged = history.mergedHistory
            val current = merged.getOrNull(history.currentIndex) ?: return
            handler.setInfo(current, merged.take(history.currentIndex), merged.drop(history.currentIndex + 1))
        }

        fun started(event: NavigationEvent) = dispatchOnBackStarted(event)
        fun progressed(event: NavigationEvent) = dispatchOnBackProgressed(event)
        fun completed() = dispatchOnBackCompleted()
        fun cancelled() = dispatchOnBackCancelled()
    }

    private inner class Handler : NavigationEventHandler<NavigationEventInfo>(RelayInfo, false, false) {
        override fun onBackStarted(event: NavigationEvent) = onStarted(event)
        override fun onBackProgressed(event: NavigationEvent) = onProgressed(event)
        override fun onBackCompleted() = onEnded(completed = true)
        override fun onBackCancelled() = onEnded(completed = false)
    }

    private object RelayInfo : NavigationEventInfo()
}

/** [this] event with [progress] in its place. */
private fun NavigationEvent.at(progress: Float) =
    NavigationEvent(touchX = touchX, touchY = touchY, progress = progress, swipeEdge = swipeEdge, frameTimeMillis = frameTimeMillis)

/**
 * The speed of a swipe's progress, from its recent back events (`docs/motion.md`, owner rule 2: the settle starts with
 * the finger's speed). Pure, for tests.
 */
internal class ProgressSpeed {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Float>()

    /** Adds the progress [value] at [timeMillis]. Keeps the events of the last [WINDOW_MILLIS]. */
    fun add(timeMillis: Long, value: Float) {
        if (times.isNotEmpty() && timeMillis < times.last()) clear()
        times.addLast(timeMillis)
        values.addLast(value)
        while (times.size > 2 && timeMillis - times.first() > WINDOW_MILLIS) {
            times.removeFirst()
            values.removeFirst()
        }
    }

    /**
     * Progress per second over the kept events, only in the direction of [towards] (0 or 1): a finger moving the other
     * way, or one that stood still, gives 0. Capped at [MAX_PER_SECOND].
     */
    fun perSecond(towards: Float): Float {
        if (times.size < 2) return 0f
        val span = times.last() - times.first()
        if (span < MIN_SPAN_MILLIS) return 0f
        val speed = (values.last() - values.first()) * MILLIS_PER_SECOND / span
        return if (towards >= values.last()) speed.coerceIn(0f, MAX_PER_SECOND) else speed.coerceIn(-MAX_PER_SECOND, 0f)
    }

    private fun clear() {
        times.clear()
        values.clear()
    }

    companion object {
        /** Only the events of this last stretch of the swipe count for its speed. */
        const val WINDOW_MILLIS = 100L

        /** Shorter than this (about one frame), there is no speed to read. */
        const val MIN_SPAN_MILLIS = 8L

        /** No settle starts faster than the whole swipe in a fifth of a second. */
        const val MAX_PER_SECOND = 5f

        private const val MILLIS_PER_SECOND = 1000f
    }
}

/** The progress settles within this of its end (on a screen 1080 px wide, under a fifth of a pixel). */
private const val PROGRESS_THRESHOLD = 0.001f

/** A swipe whose progress reached this moved the screens (about 2 px of the leaving screen's slide). */
private const val MOVED_PROGRESS = 0.01f
