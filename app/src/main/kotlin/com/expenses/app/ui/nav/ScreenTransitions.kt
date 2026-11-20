package com.expenses.app.ui.nav

import androidx.activity.BackEventCompat
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.shell.LocalTabMemory
import com.expenses.app.ui.shell.TabMemory
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * How a screen arrives and leaves (`docs/motion.md` 2.1 and 3.1; `design/spec/motion.md`, "Patterns"). Adding a route
 * means adding it here when it is not a plain [Push].
 */
enum class ScreenKind {
    /** A main tab (Home, Groups, Activity, You). Tab to tab moves inside the tabs ([Move.Tab]): each draws its own dock. */
    Tab,

    /** Every other screen: slides in on the shared X axis. The default. */
    Push,

    /** Add expense, Sign in, Choose category, New group, Add friend, Import from Splitwise: rises from below. */
    Modal,

    /** Expense added, Split added, Balance cleared, Group imported: fades in and grows from 96%. */
    Result,
}

/**
 * The move between two screens, chosen by [move]. One move describes both screens; the NavHost's enter and exit
 * lambdas map it to Compose transitions in one place.
 */
enum class Move {
    /** No transition: everything when Remove animations is on. */
    None,

    /**
     * Tab to tab. The screens do not move: the new tab's page covers the old one inside `MainScaffold`, and the old tab
     * stays drawn, unchanged, until it has (its exit keeps it for [Motion.Duration.Cap]), so no frame shows the canvas
     * alone. A back swipe shows no preview.
     */
    Tab,

    /** Push: the new screen slides in 30 dp from the end as it fades in; the old one slides 30 dp to the start as it fades out. */
    Forward,

    /** Pop: [Forward] mirrored. Predictive back previews it. */
    Back,

    /** A modal opens: it rises 1/8 of its height as it fades in. The screen under it stays still. */
    ModalRise,

    /** A modal closes: it drops back 1/8 as it fades out. The screen under it stays still. */
    ModalDrop,

    /** A result screen, or a tab reached from outside the tabs: fades in and grows from 96%; the screen under it fades out. */
    Arrive,

    /** Leaving a result screen by Back: the result fades out, then the screen under it fades in. */
    FadeThrough,

    /**
     * Opening a screen that asks for sign-in first ([isSignInGate]) while signed out: it draws nothing until Sign in
     * opens over it, so it only fades in (150 ms) and the screen under it fades out. A slide would move an empty page.
     */
    GateFade,
}

private val TabRoutes: Set<KClass<*>> = setOf(Home::class, Groups::class, Activity::class, You::class)

private val ModalRoutes: Set<KClass<*>> =
    setOf(SignIn::class, AddExpense::class, ChooseCategory::class, NewGroup::class, AddFriend::class, ImportSplitwise::class)

private val ResultRoutes: Set<KClass<*>> = setOf(ExpenseAdded::class, SplitAdded::class, BalanceCleared::class, GroupImported::class)

/** Screens drawn inside [SignInGate]: signed out, they draw nothing and open Sign in once resumed. */
private val GateRoutes: Set<KClass<*>> =
    setOf(SplitPayment::class, SplitTyped::class, JoinGroup::class, ImportSplitwise::class, SetUpFamily::class, FamilySettings::class)

/** Whether the screen whose route class is [route] is drawn inside [SignInGate]. */
fun isSignInGate(route: KClass<*>): Boolean = route in GateRoutes

/** The kind of the screen whose route class is [route]. Any route not listed as a tab, modal or result is a [ScreenKind.Push]. */
fun screenKindOf(route: KClass<*>): ScreenKind = when (route) {
    in TabRoutes -> ScreenKind.Tab
    in ModalRoutes -> ScreenKind.Modal
    in ResultRoutes -> ScreenKind.Result
    else -> ScreenKind.Push
}

/** The kind of this destination, found by its route class; [ScreenKind.Push] for a route that is not listed. */
fun NavDestination.kind(): ScreenKind {
    @Suppress("UNCHECKED_CAST")
    fun has(route: KClass<*>) = hasRoute(route as KClass<Any>)
    return when {
        TabRoutes.any(::has) -> ScreenKind.Tab
        ModalRoutes.any(::has) -> ScreenKind.Modal
        ResultRoutes.any(::has) -> ScreenKind.Result
        else -> ScreenKind.Push
    }
}

/** Whether this destination is drawn inside [SignInGate] (Split it, Join group, Import from Splitwise, Family). */
fun NavDestination.isSignInGate(): Boolean {
    @Suppress("UNCHECKED_CAST")
    return GateRoutes.any { hasRoute(it as KClass<Any>) }
}

/** The main tab this destination is, or null when it is not a tab. */
fun NavDestination.mainTab(): MainTab? {
    @Suppress("UNCHECKED_CAST")
    return MainTab.entries.firstOrNull { hasRoute(it.route::class as KClass<Any>) }
}

/**
 * The move from a screen of kind [from] to one of kind [to]. [pop] is true when [from] is leaving the back stack
 * (Back, the top bar's back, `leave()`, a predictive back gesture).
 *
 * - Remove animations ([reducedMotion]): always [Move.None].
 * - Forward to a screen drawn inside [SignInGate] while signed out ([toSignedOutGate]): [Move.GateFade].
 * - Tab to tab, either way: [Move.Tab]. The tab content animates inside `MainScaffold` instead.
 * - Forward: to a modal from outside the modals, [Move.ModalRise]; modal to modal is a plain push. To a result, or to
 *   a tab from outside the tabs, [Move.Arrive]. Anything else, [Move.Forward].
 * - Pop: from a result, [Move.FadeThrough]. From a modal to a screen that is not a modal, [Move.ModalDrop]. Anything
 *   else, [Move.Back].
 */
fun move(
    from: ScreenKind,
    to: ScreenKind,
    pop: Boolean,
    reducedMotion: Boolean = false,
    toSignedOutGate: Boolean = false,
): Move = when {
    reducedMotion -> Move.None
    !pop && toSignedOutGate -> Move.GateFade
    from == ScreenKind.Tab && to == ScreenKind.Tab -> Move.Tab
    !pop -> when (to) {
        ScreenKind.Modal -> if (from == ScreenKind.Modal) Move.Forward else Move.ModalRise
        ScreenKind.Result, ScreenKind.Tab -> Move.Arrive
        ScreenKind.Push -> Move.Forward
    }
    from == ScreenKind.Result -> Move.FadeThrough
    from == ScreenKind.Modal && to != ScreenKind.Modal -> Move.ModalDrop
    else -> Move.Back
}

/**
 * The NavHost's transitions (`docs/motion.md` 3.2), with the values of `design/spec/motion.md` ("Screen push and pop",
 * "Modal rise", "Result arrival", "Predictive back"). Each lambda finds the [Move] between the two screens and maps it
 * here, in one place. Every move is `motion/spring/screen`; fades are short tweens.
 *
 * Make it with [rememberNavTransitions] and pass its functions to the six NavHost lambdas.
 */
@Stable
class NavTransitions internal constructor(
    private val axisPx: Int,
    private val reducedMotion: () -> Boolean,
    private val signedOut: () -> Boolean,
    private val tabs: () -> TabMemory? = { null },
) {
    /** The move from [AnimatedContentTransitionScope.initialState] to its target; [pop] for the pop lambdas. */
    fun moveOf(scope: AnimatedContentTransitionScope<NavBackStackEntry>, pop: Boolean): Move {
        val target = scope.targetState.destination
        return move(
            from = scope.initialState.destination.kind(),
            to = target.kind(),
            pop = pop,
            reducedMotion = reducedMotion(),
            toSignedOutGate = !pop && target.isSignInGate() && signedOut(),
        )
    }

    /** How the screen being shown arrives: `enterTransition` ([pop] false) and `popEnterTransition` ([pop] true). */
    fun enter(scope: AnimatedContentTransitionScope<NavBackStackEntry>, pop: Boolean): EnterTransition =
        when (moveOf(scope, pop)) {
            Move.Tab -> EnterTransition.None.also { tabChange(scope, under = pop, predictive = false) }
            Move.None, Move.ModalDrop -> EnterTransition.None
            Move.Forward -> scope.slideIntoContainer(SlideDirection.Start, slide, this::axis) + fadeIn(fadeIn)
            Move.Back -> scope.slideIntoContainer(SlideDirection.End, slide, this::axis) + fadeIn(fadeIn)
            Move.ModalRise -> slideInVertically(slide) { (it * Motion.ModalRiseFraction).roundToInt() } + fadeIn(fadeIn)
            Move.Arrive -> fadeIn(Motion.fade(Motion.ResultFade.InMillis, easing = Motion.Easings.Enter)) +
                scaleIn(scale, initialScale = Motion.EnterScale)
            Move.FadeThrough -> fadeIn(Motion.fade(Motion.FadeThrough.InMillis, delayMillis = Motion.FadeThrough.InDelayMillis))
            Move.GateFade -> fadeIn(Motion.fade(Motion.Duration.Short))
        }

    /** How the screen being left goes: `exitTransition` ([pop] false) and `popExitTransition` ([pop] true). */
    fun exit(scope: AnimatedContentTransitionScope<NavBackStackEntry>, pop: Boolean): ExitTransition =
        when (moveOf(scope, pop)) {
            Move.None -> ExitTransition.None
            // The old tab stays drawn, unchanged, while the new tab's page covers it (`MainScaffold`: at most a short wait
            // for the new content, then the 150 ms fade), so no frame shows the canvas alone.
            Move.Tab -> fadeOut(tabKept)
            Move.Forward -> scope.slideOutOfContainer(SlideDirection.Start, slide, this::axis) + fadeOut(fadeOut)
            Move.Back -> scope.slideOutOfContainer(SlideDirection.End, slide, this::axis) + fadeOut(fadeOut)
            // The screen under a modal stays still and stays drawn while the modal rises.
            Move.ModalRise -> fadeOut(hold, targetAlpha = 1f)
            Move.ModalDrop -> slideOutVertically(slide) { (it * Motion.ModalRiseFraction).roundToInt() } + fadeOut(fadeOut)
            Move.Arrive -> fadeOut(Motion.fade(Motion.ResultFade.OutMillis, easing = Motion.Easings.Exit))
            Move.FadeThrough -> fadeOut(Motion.fade(Motion.FadeThrough.OutMillis))
            Move.GateFade -> fadeOut(fadeOut)
        }

    /**
     * The screen under a back swipe (`predictivePopEnterTransition`): it slides in from 30 dp toward the start as it
     * fades in, or only fades in under a result. Under a modal it stays still; tab to tab shows no preview. The track is
     * linear so the screens follow the finger.
     */
    @Suppress("UNUSED_PARAMETER")
    fun predictiveEnter(scope: AnimatedContentTransitionScope<NavBackStackEntry>, swipeEdge: Int): EnterTransition =
        when (moveOf(scope, pop = true)) {
            Move.Back -> scope.slideIntoContainer(SlideDirection.End, track(), this::axis) + fadeIn(track())
            Move.FadeThrough -> fadeIn(track())
            Move.Tab -> EnterTransition.None.also { tabChange(scope, under = true, predictive = true) }
            else -> EnterTransition.None
        }

    /**
     * The screen a back swipe leaves (`predictivePopExitTransition`): it shrinks to 90% and moves an eighth of its
     * width toward the side the swipe started from, as it fades out. Tab to tab shows no preview.
     */
    fun predictiveExit(scope: AnimatedContentTransitionScope<NavBackStackEntry>, swipeEdge: Int): ExitTransition =
        when (moveOf(scope, pop = true)) {
            Move.None -> ExitTransition.None
            Move.Tab -> fadeOut(tabKept)
            else -> scaleOut(track(), targetScale = Motion.PredictiveScale) +
                slideOutHorizontally(track()) { width ->
                    val eighth = width / PREDICTIVE_SLIDE_DIVISOR
                    if (swipeEdge == BackEventCompat.EDGE_LEFT) eighth else -eighth
                } +
                fadeOut(track())
        }

    /**
     * Tells `MainScaffold` about a tab change ([TabMemory.changing]): which tabs, and whether the new tab is drawn
     * under the old one ([under], a pop: the NavHost draws a popped screen on top) or a back swipe ([predictive]).
     */
    private fun tabChange(scope: AnimatedContentTransitionScope<NavBackStackEntry>, under: Boolean, predictive: Boolean) {
        val memory = tabs() ?: return
        val from = scope.initialState.destination.mainTab() ?: return
        val to = scope.targetState.destination.mainTab() ?: return
        if (from != to) memory.changing(scope.initialState.id, scope.targetState.id, from, to, under, predictive)
    }

    /** 30 dp toward the side [fullSlide] points to (positive or negative), so Start and End also flip in RTL. */
    private fun axis(fullSlide: Int): Int = if (fullSlide < 0) -axisPx else axisPx

    private val slide = Motion.Spring.Screen.spec(IntOffset.VisibilityThreshold)
    private val scale = Motion.Spring.Screen.spec(SCALE_THRESHOLD)
    private val fadeIn = Motion.fade<Float>(Motion.Duration.Short, easing = Motion.Easings.Enter)
    private val fadeOut = Motion.fade<Float>(Motion.Duration.Short, easing = Motion.Easings.Exit)

    /**
     * Keeps the screen under a rising modal drawn, unchanged, for about as long as the rise (`motion/spring/screen`
     * lands within a pixel in about 350 ms). Compose keeps an exiting screen only while its own exit runs.
     */
    private val hold = tween<Float>(Motion.Duration.Cap)

    /**
     * Keeps the old tab of a tab change drawn, unchanged, for [Motion.Duration.Cap], then lets it go: whole until the
     * delay is over, as a fade to the same alpha would end at once. The new tab's page covers it meanwhile.
     */
    private val tabKept = snap<Float>(delayMillis = Motion.Duration.Cap)

    /** A linear track for a back swipe: the transition is seeked by the gesture's progress. */
    private fun <T> track(): FiniteAnimationSpec<T> = tween(Motion.Duration.Medium, easing = LinearEasing)
}

/** A screen-wide scale stops within this of its target (the default 0.01 would jump about 4 dp at the edges). */
private const val SCALE_THRESHOLD = 0.001f

/** The leaving screen of a back swipe moves this fraction (1/8) of its width. */
private const val PREDICTIVE_SLIDE_DIVISOR = 8

/**
 * The app's [NavTransitions]. Remove animations is read live ([LocalReducedMotion]), so switching it while the app is
 * open applies to the next navigation. Tab changes are told to [LocalTabMemory].
 *
 * @param signedOut whether nobody is signed in; read only when opening a screen drawn inside [SignInGate].
 */
@Composable
fun rememberNavTransitions(signedOut: () -> Boolean): NavTransitions {
    val reduced by rememberUpdatedState(LocalReducedMotion.current)
    val isSignedOut by rememberUpdatedState(signedOut)
    val tabs by rememberUpdatedState(LocalTabMemory.current)
    val axisPx = with(LocalDensity.current) { Motion.AxisDistance.roundToPx() }
    return remember(axisPx) {
        NavTransitions(axisPx, reducedMotion = { reduced }, signedOut = { isSignedOut() }, tabs = { tabs })
    }
}
