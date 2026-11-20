package com.expenses.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.expenses.app.R
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.core.EdgeHeight
import com.expenses.app.ui.icons.TintedIcon
import com.expenses.app.ui.motion.rememberPressDrop
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** The four main places of the app, in the order the bottom navigation shows them. */
enum class MainTab {
    /** The month's spending, the latest payments and anything waiting for a decision. */
    Home,

    /** The groups and friends the user splits with, and what each owes. */
    Groups,

    /** Every payment, grouped by day, with filters. */
    Activity,

    /** The user's profile, tracking and privacy settings. */
    You,
}

/** Width of the border of the dock. The design counts it as part of the dock's inner spacing. */
private val BottomNavDockBorder = 1.5.dp

/** Size of a tab that is not active. It is also its touch area. */
private val BottomNavTabSize = 48.dp

/** Size of the icon of a tab, active or not. */
private val BottomNavIconSize = 24.dp

/** The pill's face is at least this tall: 12 + 24 (icon) + 12. A large font makes it taller. */
private val BottomNavPillHeight = 48.dp

/** The pill's padding: [Space.s16] left and right, [Space.s12] top and bottom. */
private val BottomNavPillPaddingH = Space.s16
private val BottomNavPillPaddingV = Space.s12

/** The pill's progress stops within this of its target. */
private const val PILL_THRESHOLD = 0.001f

/**
 * Where the bottom-bar pill is and how fast it moves: how far each tab is selected (0 to 1, adding up to 1, a little
 * past while the pill overshoots), and the alpha of each tab's name. Each tab draws its own dock; in the app every dock
 * draws the one motion `TabMemory` keeps, so while one tab's screen replaces another's both docks show the same pill,
 * and a tap on another tab while the pill slides carries it on from where it is, at its speed (`docs/motion.md`, owner
 * rule 1).
 *
 * Read its values only while drawing or in effects. Make one with [at].
 */
@Stable
class BottomNavMotion private constructor(progress: FloatArray) {
    internal val pill: List<Animatable<Float, AnimationVector1D>> = progress.map { Animatable(it) }
    internal val names: List<Animatable<Float, AnimationVector1D>> = progress.map { Animatable(it) }

    /** The tab the pill is on, or on its way to. */
    var heading: MainTab = MainTab.entries[progress.indexOfFirst { it == 1f }.coerceAtLeast(0)]
        private set

    private var driving: Job? = null

    /**
     * Starts the pill toward [tab] in [scope] (see [moveTo]), unless it is already on its way there or there. Call it
     * from the tap itself: the pill then moves in the next frame, before the next tab's screen is drawn.
     */
    internal fun select(tab: MainTab, reduced: Boolean, scope: CoroutineScope) {
        if (heading == tab && (driving?.isActive == true || pill[tab.ordinal].value == 1f)) return
        heading = tab
        driving = scope.launch { moveTo(tab, reduced) }
    }

    /**
     * Moves the pill to [active] (`motion/spring/pill`, from the current value and speed) and fades the names (in over
     * 150 ms, out over 100 ms). The pill is already one frame along in the first frame it draws, so a tap moves it in
     * the next frame. A jump when [reduced].
     */
    internal suspend fun moveTo(active: MainTab, reduced: Boolean) {
        heading = active
        coroutineScope {
            MainTab.entries.forEach { tab ->
                val i = tab.ordinal
                val target = if (tab == active) 1f else 0f
                launch { if (reduced) pill[i].snapTo(target) else pill[i].springTo(target) }
                launch {
                    when {
                        reduced -> names[i].snapTo(target)
                        target == 1f -> names[i].animateTo(target, Motion.fade(Motion.RevealFade.InMillis))
                        else -> names[i].animateTo(target, Motion.fade(Motion.RevealFade.OutMillis))
                    }
                }
            }
        }
    }

    companion object {
        private val TAB_COUNT = MainTab.entries.size

        /** At rest on [tab]. */
        fun at(tab: MainTab): BottomNavMotion = BottomNavMotion(FloatArray(TAB_COUNT) { if (it == tab.ordinal) 1f else 0f })
    }
}

/**
 * Springs to [target] (`motion/spring/pill`) from the current value and speed. A spring has no memory, so it first
 * steps to where the same spring is one frame later and carries on from there: an animation's first frame otherwise
 * draws its start value again, and the pill would stand still for a frame after a tap.
 */
private suspend fun Animatable<Float, AnimationVector1D>.springTo(target: Float) {
    val spec = Motion.Spring.Pill.spec(PILL_THRESHOLD)
    val start = value
    val speed = velocity
    if (start == target && speed == 0f) return
    // The frame is counted in animation time, as the animator duration scale stretches it.
    val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
    val ahead = TargetBasedAnimation(spec, Float.VectorConverter, start, target, AnimationVector1D(speed))
    val frame = if (scale > 0f) (FRAME_NANOS / scale).toLong() else Long.MAX_VALUE
    if (ahead.isFinishedFromNanos(frame)) {
        snapTo(target)
        return
    }
    snapTo(ahead.getValueFromNanos(frame))
    animateTo(target, spec, initialVelocity = ahead.getVelocityVectorFromNanos(frame).value)
}

/** One frame at 60 Hz, in nanoseconds. */
private const val FRAME_NANOS = 16_666_667L

/**
 * The bottom navigation: a pill-shaped dock with the four main tabs. The active tab is a raised pill with its
 * icon and name; the other three show only an icon. It does not add space for the system navigation bar: the
 * screen that holds it does.
 *
 * **Motion** (`design/spec/motion.md`, "Tab change"). One green pill slides from the tab it was on to [active]: its x
 * and width follow `motion/spring/pill` (slight overshoot), and an interrupted slide carries on from where it is. The
 * new tab widens as its name fades in, the old one narrows as its name fades out, and the other icons glide (space
 * between). Each icon changes colour with the pill. A tap on another tab plays `haptic/selection`. The pill presses like
 * a 3D face (`motion/spring/press`, no press haptic); the other tabs show the pressed overlay in their 48 dp circle.
 * With Remove animations on, it jumps.
 *
 * @param active the tab whose screen is showing.
 * @param onSelect called with the tab that was tapped. It is also called when the active tab is tapped again.
 * @param modifier outer modifier. The bar always fills the width it is given.
 * @param motion where the pill is and how fast it moves: at rest on [active] by default; in the app (`MainScaffold`),
 * the one motion every dock draws, which `TabMemory` moves.
 * @param start true to slide the pill to [active] here; false when the caller moves [motion] itself
 * ([BottomNavMotion.select]).
 */
@Composable
fun BottomNav(
    active: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    motion: BottomNavMotion = remember { BottomNavMotion.at(active) },
    start: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduced = LocalReducedMotion.current
    val dockShape = RoundedCornerShape(Radius.full)
    LaunchedEffect(motion, active, start, reduced) {
        if (start) motion.moveTo(active, reduced)
    }
    val pill: List<State<Float>> = motion.pill.map { it.asState() }
    val names: List<State<Float>> = motion.names.map { it.asState() }
    val sources = remember { MainTab.entries.map { MutableInteractionSource() } }
    // The pill presses from the active tab's own selectable (its whole slot), without the press haptic.
    val drop = rememberPressDrop(source = sources[active.ordinal], enabled = true, haptic = false)

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Space.s16, top = Space.s16, end = Space.s16, bottom = Space.s8)
            .clip(dockShape)
            .background(colors.bgSurfaceRaised)
            .border(BottomNavDockBorder, colors.borderSubtle, dockShape)
            .padding(BottomNavDockBorder + Space.s8)
            .selectableGroup(),
        content = {
            BottomNavPill(drop)
            MainTab.entries.forEach { tab ->
                BottomNavTab(
                    tab = tab,
                    selected = tab == active,
                    progress = pill[tab.ordinal],
                    nameAlpha = names[tab.ordinal],
                    drop = if (tab == active) drop else null,
                    source = sources[tab.ordinal],
                    onClick = {
                        if (tab != active) haptics.selection()
                        onSelect(tab)
                    },
                )
            }
        },
    ) { measurables, constraints ->
        val available = constraints.maxWidth
        val compact = BottomNavTabSize.roundToPx()
        // As the old Row's weight did: the three icon tabs keep their size when a large font makes the pill wider.
        val tabMax = (available - (MainTab.entries.size - 1) * compact).coerceAtLeast(compact)
        val tabs = measurables.drop(1).map { it.measure(Constraints(maxWidth = tabMax)) }
        val gap = ((available - tabs.sumOf { it.width }) / (tabs.size - 1).coerceAtLeast(1)).coerceAtLeast(0)
        val xs = tabs.runningFold(0) { x, placeable -> x + placeable.width + gap }
        // The pill sits where the tabs' progress puts it: on one tab at rest, between two while it slides. The
        // progress values add up to 1 (springs superpose), so this is a weighted average of the tab slots.
        val weights = pill.map { it.value }
        val total = weights.sum().takeIf { it > MIN_WEIGHT } ?: 1f
        val pillX = tabs.indices.sumOf { (weights[it] * xs[it]).toDouble() }.toFloat() / total
        val pillWidth = tabs.indices.sumOf { (weights[it] * tabs[it].width).toDouble() }.toFloat() / total
        val height = tabs.maxOf { it.height }
        val pillFace = measurables.first().measure(Constraints.fixed(pillWidth.roundToInt().coerceAtLeast(0), height))
        layout(available, height) {
            pillFace.placeRelative(pillX.roundToInt(), 0)
            tabs.forEachIndexed { i, placeable -> placeable.placeRelative(xs[i], (height - placeable.height) / 2) }
        }
    }
}

/** Below this total the pill's weights are not used (it never happens at rest: they add up to 1). */
private const val MIN_WEIGHT = 0.01f

/**
 * The green pill behind the tabs: a face in `color/brand/primary` over its edge in `color/brand/edge`, 5 dp under it
 * (`DROP_SHADOW(0,5,0)`). The face drops [drop] x 4 dp while pressed, leaving a 1 dp lip, as a 3D face does.
 */
@Composable
private fun BottomNavPill(drop: State<Float>) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier.drawBehind {
            val edge = EdgeHeight.toPx()
            val face = Size(size.width, (size.height - edge).coerceAtLeast(0f))
            val r = minOf(face.height, face.width) / 2f
            drawRoundRect(colors.brandEdge, topLeft = Offset(0f, edge), size = face, cornerRadius = CornerRadius(r, r))
            drawRoundRect(
                colors.brandPrimary,
                topLeft = Offset(0f, drop.value * Motion.PressDistance.toPx()),
                size = face,
                cornerRadius = CornerRadius(r, r),
            )
        },
    )
}

/**
 * One tab: its icon and its name, as wide as its [progress] makes it: 48 dp when not active, the pill's width (16 +
 * icon + 8 + name + 16) when active. The name is clipped while the tab widens. Read out as the tab's name, with its
 * selected state.
 *
 * @param progress how far this tab is selected, 0 to 1.
 * @param nameAlpha the alpha of the name.
 * @param drop the pill's press, for the active tab: its icon and name go down with the face.
 * @param source the interactions of this tab's `selectable`; the pill presses from the active tab's.
 */
@Composable
private fun BottomNavTab(
    tab: MainTab,
    selected: Boolean,
    progress: State<Float>,
    nameAlpha: State<Float>,
    drop: State<Float>?,
    source: MutableInteractionSource,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val label = tab.label()
    // The active tab presses as a 3D face (the pill), so it gets no overlay; the others show it in their circle.
    val indication = if (selected) null else LocalIndication.current
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .size(BottomNavTabSize)
                    .clip(CircleShape)
                    .indication(source, indication),
                contentAlignment = Alignment.Center,
            ) {
                // Two tints crossfaded with the pill, read in the layer only: text/tertiary off the pill, on-brand on it.
                TintedIcon(
                    id = tab.iconRes,
                    size = BottomNavIconSize,
                    tint = colors.textTertiary,
                    modifier = Modifier.graphicsLayer { alpha = 1f - progress.value.coerceIn(0f, 1f) },
                )
                TintedIcon(
                    id = tab.iconRes,
                    size = BottomNavIconSize,
                    tint = colors.textOnBrand,
                    modifier = Modifier.graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) },
                )
            }
            AppText(
                text = label,
                style = AppTheme.type.bodyMStrong,
                color = colors.textOnBrand,
                maxLines = 1,
                modifier = Modifier
                    .clearAndSetSemantics { }
                    .graphicsLayer { alpha = nameAlpha.value },
            )
        },
        modifier = Modifier
            .selectable(selected = selected, interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label }
            .clipToBounds(),
    ) { measurables, constraints ->
        val s = progress.value.coerceIn(0f, 1f)
        val compact = BottomNavTabSize.roundToPx()
        val icon = BottomNavIconSize.roundToPx()
        val padH = BottomNavPillPaddingH.roundToPx()
        val gap = Space.s8.roundToPx()
        val circle = measurables[0].measure(Constraints.fixed(compact, compact))
        val nameMax = (constraints.maxWidth - 2 * padH - icon - gap).coerceAtLeast(0)
        val name = measurables[1].measure(Constraints(maxWidth = nameMax))
        val faceHeight = maxOf(BottomNavPillHeight.roundToPx(), name.height + 2 * BottomNavPillPaddingV.roundToPx())
        val height = faceHeight + EdgeHeight.roundToPx()
        val full = (padH + icon + gap + name.width + padH).coerceIn(compact, maxOf(compact, constraints.maxWidth))
        val width = lerp(compact, full, s)
        // Off the pill the 48 dp circle is centred in the tab; on it, the icon is centred on the face, 16 from its start.
        val circleX = lerp(0, padH - (compact - icon) / 2, s)
        val circleY = lerp((height - compact) / 2, (faceHeight - compact) / 2, s)
        val nameX = circleX + (compact + icon) / 2 + gap
        val nameY = circleY + (compact - name.height) / 2
        val press = Motion.PressDistance.toPx()
        layout(width, height) {
            circle.placeRelativeWithLayer(circleX, circleY) { translationY = (drop?.value ?: 0f) * press }
            name.placeRelativeWithLayer(nameX, nameY) { translationY = (drop?.value ?: 0f) * press }
        }
    }
}

/** [start] to [stop] by [fraction], rounded. */
private fun lerp(start: Int, stop: Int, fraction: Float): Int = (start + (stop - start) * fraction).roundToInt()

/** Name of the tab as the design writes it. Shown in the active pill and read out for the icon-only tabs. */
@Composable
private fun MainTab.label(): String = stringResource(
    when (this) {
        MainTab.Home -> R.string.shell_tab_home
        MainTab.Groups -> R.string.shell_tab_groups
        MainTab.Activity -> R.string.shell_tab_activity
        MainTab.You -> R.string.shell_tab_you
    },
)

/** Outline icon of the tab. The same drawing is used in both states; only its colour changes. */
private val MainTab.iconRes: Int
    @DrawableRes get() = when (this) {
        MainTab.Home -> R.drawable.ic_nav_home
        MainTab.Groups -> R.drawable.ic_nav_groups
        MainTab.Activity -> R.drawable.ic_nav_activity
        MainTab.You -> R.drawable.ic_nav_you
    }
