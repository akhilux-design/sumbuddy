package com.expenses.app.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.expenses.app.ui.components.BottomNav
import com.expenses.app.ui.components.BottomNavMotion
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the tabs share (`docs/motion.md` 3.6): the tab shown last, the bottom-bar pill, and the tab change the navigation
 * is making. Each tab draws its own page and dock. During a tab change both tabs are drawn: the NavHost keeps the
 * leaving one (`Move.Tab`) until the arriving page covers it, so no frame shows the canvas alone. `AppRoot`
 * tells it which screen is on top ([onTop]), so coming back from a pushed screen does not animate the tab again.
 */
@Stable
class TabMemory(
    /** The tab that was showing before the current screen, or null when it was not a tab. */
    var last: MainTab? = null,
    /** Where the pill moves: `AppRoot`'s scope, so a dock leaving the screen does not stop it. Null: each tab's own. */
    internal val scope: CoroutineScope? = null,
) {
    /**
     * The bottom-bar pill. A tab reached from another tab draws this one in its dock, so both docks of a tab change
     * show the same pill, and a tap moves it before the next tab is drawn.
     */
    internal var pill: BottomNavMotion = BottomNavMotion.at(MainTab.Home)

    /** The latest tab change, as the NavHost's transitions saw it. Read while drawing or in effects. */
    internal var change: TabChange? by mutableStateOf(null)
        private set

    /** The tab on top of the back stack, or null when the screen on top is not a tab. Set by `AppRoot`. */
    internal var top: MainTab? by mutableStateOf(null)
        private set

    /** [tab] now shows. */
    internal fun showing(tab: MainTab) {
        last = tab
    }

    /**
     * The navigation shows [tab], or a screen that is not a tab (null). That breaks the tab-to-tab chain: the tab shown
     * after it was not reached from another tab, so its page and pill do not move (the screen transition does).
     */
    fun onTop(tab: MainTab?) {
        top = tab
        if (tab == null) last = null
    }

    /**
     * The NavHost changes from the tab [from] (entry [fromId]) to [to] (entry [toId]). [under]: the new tab is drawn
     * under the old one (a pop: Back, or a tap on Home, which pops the other tab). [predictive]: a back swipe. Each
     * screen's transition reads the change, so it may come more than once; a repeat keeps the first record.
     */
    internal fun changing(fromId: String, toId: String, from: MainTab, to: MainTab, under: Boolean, predictive: Boolean) {
        val now = change
        if (now != null && now.fromId == fromId && now.toId == toId && now.predictive == predictive) return
        change = TabChange((now?.number ?: 0) + 1, fromId, toId, from, to, under, predictive)
    }
}

/**
 * One tab change, numbered in order. [progress] runs from 0 to 1 as the new tab's page covers the old one: the new page
 * fades in over the old (a push), or the old page fades out over the new (a pop, [under]). The new tab drives it.
 */
@Stable
internal class TabChange(
    val number: Int,
    val fromId: String,
    val toId: String,
    val from: MainTab,
    val to: MainTab,
    val under: Boolean,
    val predictive: Boolean,
) {
    val progress = Animatable(0f)
}

/** The app's [TabMemory]; null outside `AppRoot` (previews, tests), where tabs never animate on arrival. */
val LocalTabMemory = compositionLocalOf<TabMemory?> { null }

/** Whether a tab shown for [active] arrives from another tab ([from]), so its content and pill animate. */
internal fun arrivesFromAnotherTab(from: MainTab?, active: MainTab): Boolean = from != null && from != active

/** The two parts [MainScaffold] measures: the bottom bar first, then the page that needs its height. */
private enum class MainScaffoldSlot { BottomBar, Page }

/**
 * The frame of the four main screens: the page (the canvas colour and the screen's content) and the bottom navigation
 * floating at the bottom, just above the system navigation bar.
 *
 * The content is drawn edge to edge, under the status bar and under the bottom navigation, so lists scroll
 * underneath the dock as the design shows. It receives the space it must keep clear as [PaddingValues]: the top is
 * the status bar, the bottom is the full height the bottom navigation covers (including the system navigation bar
 * under it). Apply it as content padding of a list, or as padding of a screen that does not scroll.
 *
 * **Tab change** (`design/spec/motion.md`, "Tab change"). A tap on another tab starts the pill at once
 * (`motion/spring/pill`, in the next frame). The tab reached from another tab ([LocalTabMemory]) then covers the old
 * one: drawn over it, its page fades in (150 ms) as its content grows from 96% (`motion/spring/screen`); drawn under it
 * (Back, or a tap on Home), its page is whole at once and the old page fades out over it the same way. The old page
 * stays drawn until then, and the fade waits for the new content to draw something, so no frame shows the canvas
 * alone. After a back swipe it starts once the swipe is done (the swipe's preview does not play it). The dock is never
 * inside the animated layer. Coming back from a pushed screen, or arriving from outside the tabs, the screen
 * transition moves it instead. With Remove animations on, the tabs swap at once.
 *
 * @param active the tab whose screen is showing; it is drawn as the raised pill in the bottom navigation.
 * @param onSelect called with the tab the person tapped, including the active one.
 * @param modifier outer modifier. The scaffold always fills the space it is given.
 * @param content the screen. Its parameter is the padding to keep clear of the status bar and the bottom navigation.
 */
@Composable
fun MainScaffold(
    active: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val statusBars = WindowInsets.statusBars
    val memory = LocalTabMemory.current
    val reduced = LocalReducedMotion.current
    val ownScope = rememberCoroutineScope()
    val scope = memory?.scope ?: ownScope
    val page = remember {
        val from = memory?.last
        // With Remove animations on, the page and the pill are simply in place.
        Snapshot.withoutReadObservation {
            TabPage(memory, active, from, arriving = arrivesFromAnotherTab(from, active) && !reduced)
        }
    }
    // A tab reached from another tab draws the pill the last dock drew; any other tab starts it at rest on itself.
    val pill = remember {
        when {
            memory == null -> BottomNavMotion.at(active)
            page.arriving -> memory.pill
            else -> BottomNavMotion.at(active).also { memory.pill = it }
        }
    }
    if (memory != null) {
        LaunchedEffect(page) {
            if (page.arriving) {
                // After a back swipe, once it is done: the swipe's preview does not play it.
                page.arrival?.let { if (it.predictive) memory.awaitOnTop(active) }
                pill.select(active, reduced, scope)
                memory.showing(active)
                page.awaitContent()
                launch { page.scale.animateTo(1f, Motion.Spring.Screen.spec(CONTENT_SCALE_THRESHOLD)) }
                page.progress.animateTo(1f, PageFade)
            }
            // Back to this tab while it is still drawn, leaving: it covers the tab it was leaving for again.
            snapshotFlow { page.returnOf(memory.change) }.filterNotNull().collect { back ->
                if (back.predictive) memory.awaitOnTop(active)
                pill.select(active, reduced, scope)
                memory.showing(active)
                back.progress.animateTo(1f, PageFade)
            }
        }
    }
    LifecycleResumeEffect(memory, active) {
        memory?.showing(active)
        onPauseOrDispose { }
    }

    SubcomposeLayout(modifier = modifier.fillMaxSize()) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bar = subcompose(MainScaffoldSlot.BottomBar) {
            BottomNav(
                active = active,
                onSelect = { tab ->
                    // The pill sets off from the tap itself, before the next tab's screen is drawn.
                    if (tab != active && memory != null) pill.select(tab, reduced, scope)
                    onSelect(tab)
                },
                modifier = Modifier.navigationBarsPadding(),
                motion = pill,
                start = memory == null,
            )
        }.map { it.measure(loose) }
        val barHeight = bar.maxOfOrNull { it.height } ?: 0
        val padding = PaddingValues(
            top = statusBars.getTop(this).toDp(),
            bottom = barHeight.toDp(),
        )
        val body = subcompose(MainScaffoldSlot.Page) { Page(page) { content(padding) } }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.forEach { it.place(0, 0) }
            bar.forEach { it.place(0, constraints.maxHeight - it.height) }
        }
    }
}

/**
 * The page of a tab: the canvas colour and [content], in one layer whose alpha [TabPage.alpha] sets (read in the layer
 * only, so nothing is measured or recomposed again while it fades). The content grows from 96% inside it, so the canvas
 * always covers the whole screen. It notes when the content first draws something.
 */
@Composable
private fun Page(page: TabPage, content: @Composable () -> Unit) {
    Layout(
        content = content,
        modifier = Modifier
            .graphicsLayer { alpha = page.alpha() }
            .background(AppTheme.colors.bgCanvas),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        if (placeables.isNotEmpty() && !page.hasContent) page.hasContent = true
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { placeable ->
                if (page.arriving) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = page.scale.value
                        scaleY = page.scale.value
                    }
                } else {
                    placeable.place(0, 0)
                }
            }
        }
    }
}

/**
 * One tab's page through tab changes: how it covers the tab it replaces when it arrives from another tab ([arriving]),
 * and how it goes when another tab replaces it.
 */
@Stable
private class TabPage(private val memory: TabMemory?, private val active: MainTab, from: MainTab?, val arriving: Boolean) {
    /** Tab changes numbered after this one came after this page was composed. */
    private val born = memory?.change?.number ?: 0

    /** The change that brought this tab, recorded by the NavHost as it composed this page; null outside a NavHost. */
    val arrival: TabChange? = memory?.change?.takeIf { arriving && it.to == active && it.from == from }

    /** 0 to 1 as this page covers the tab it replaces; shared with that tab's page through [arrival]. */
    val progress: Animatable<Float, AnimationVector1D> = arrival?.progress ?: Animatable(if (arriving) 0f else 1f)

    /** The content's scale: from 96% when arriving. */
    val scale = Animatable(if (arriving) Motion.EnterScale else 1f)

    /** Drawn under the tab it replaces: whole at once, while that tab's page fades out over it. */
    private val under = arrival?.under == true

    /** Whether the content drew anything yet (a tab draws nothing until its data is read). */
    var hasContent by mutableStateOf(false)

    /** The latest change by which this page leaves. */
    private var leaving: TabChange? = null

    /** The page's alpha. Read while drawing. */
    fun alpha(): Float {
        val change = memory?.change
        if (change != null && change.number > born && change.from == active) leaving = change
        val own = if (arriving && !under) progress.value else 1f
        val gone = leaving?.takeIf { it.under }?.progress?.value ?: 0f
        return own * (1f - gone)
    }

    /** [change] when it brings back this tab, still drawn as it was leaving; else null. */
    fun returnOf(change: TabChange?): TabChange? = change?.takeIf { it.number > born && it.to == active }

    /**
     * Waits until the content draws something, at most [CONTENT_WAIT_MILLIS] of animation time, so the page covers the
     * old tab with content, and still within the time the NavHost keeps the old tab.
     */
    suspend fun awaitContent() {
        val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        var start = -1L
        while (!hasContent) {
            val now = withFrameNanos { it }
            if (start < 0) start = now
            if (now - start >= CONTENT_WAIT_MILLIS * NANOS_PER_MILLI * scale) return
        }
    }
}

/** Waits until [tab] is on top of the back stack (a back swipe to it is done). */
private suspend fun TabMemory.awaitOnTop(tab: MainTab) {
    snapshotFlow { top }.first { it == tab }
}

/** The page's fade: in over the old page, or the old page out over it (`motion/duration/short`). */
private val PageFade = Motion.fade<Float>(Motion.Duration.Short, easing = Motion.Easings.Enter)

/**
 * The longest the page waits for its content before it fades anyway. The NavHost keeps the old tab for
 * [Motion.Duration.Cap] (350 ms); this wait, the 150 ms fade and a frame or two to start fit inside it.
 */
private const val CONTENT_WAIT_MILLIS = 120L

private const val NANOS_PER_MILLI = 1_000_000L

/** The content's scale stops within this of 1 (the default 0.01 would jump about 4 dp at the screen's edges). */
private const val CONTENT_SCALE_THRESHOLD = 0.001f
