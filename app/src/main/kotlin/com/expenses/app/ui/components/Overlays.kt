@file:OptIn(ExperimentalAnimationApi::class)

package com.expenses.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import com.expenses.app.ui.motion.OverlayScope
import com.expenses.app.ui.theme.AppHaptics
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.SpringToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.icons.ArtIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

// The overlays the design draws over a screen: the confirm dialog (frame "Android permission dialog" in
// 02-everyday/47, 04-you/03 and 04-you/08), the card it sits on, which the rename dialog of 01-onboarding/15 shares,
// and the bottom sheet (02-everyday/37 and 46). All sit on a scrim of color/bg/canvas at 72%.

/** Opacity of the scrim behind a dialog or sheet, as drawn. */
private const val ScrimAlpha = 0.72f

/** The 3D icon at the top of a confirm dialog. */
private val DialogIconSize = 32.dp

/** The title and body of a confirm dialog are 260 wide inside the 280 of its content: 10 more at each side. */
private val DialogTextInset = 10.dp

/** A dialog choice is drawn 44 high; its touch area is 48 (2 more above and below, inside the 8 gap). */
private val ChoiceTouchHeight = 48.dp
private val ChoiceTouchOverhang = Space.s2

/** The sheet's grab handle. */
private val HandleWidth = 36.dp
private val HandleHeight = 4.dp

/** Space above the handle. */
private val SheetPaddingTop = 12.dp

/**
 * A confirm dialog as the design draws it (for example "Delete your account and all data?"): over a scrim, a card
 * on `color/bg/surface-raised` (radius 32, padding 24, 16 between parts) near the bottom of the screen, with the 3D
 * [icon], the [title] (Title, centred), the [body] (Body/M, `color/text/secondary`, centred) and two choice pills
 * (Body/M Strong on `color/bg/surface`): [confirmLabel] first, then [dismissLabel]. Back, a tap on the scrim and
 * [dismissLabel] all keep things as they are.
 *
 * @param icon the 3D icon at the top (Trash for a delete, Logout for sign out).
 * @param title the question.
 * @param body what happens, or null for none.
 * @param confirmLabel the choice that does it, for example "Delete everything".
 * @param dismissLabel the choice that leaves things as they are, for example "Keep my account".
 * @param onConfirm called when the person confirms.
 * @param onDismiss called when the person keeps things as they are.
 * @param overlay the [AnimatedOverlay][com.expenses.app.ui.motion.AnimatedOverlay] scope this dialog is shown in, so
 * it also leaves with motion and ignores taps while it does (see [DialogCard]); null for a dialog that only arrives.
 */
@Composable
fun ChoiceDialog(
    icon: AppIcon,
    title: String,
    body: String?,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    overlay: OverlayScope? = null,
) {
    val confirm = overlay?.guard(onConfirm) ?: onConfirm
    val dismiss = overlay?.guard(onDismiss) ?: onDismiss
    DialogCard(title = title, onDismiss = dismiss, overlay = overlay) {
        ArtIcon(icon = icon, size = DialogIconSize)
        AppText(
            text = title,
            style = AppTheme.type.title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DialogTextInset)
                .semantics { heading() },
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            AppText(
                text = body,
                style = AppTheme.type.bodyM,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DialogTextInset),
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.s8 - ChoiceTouchOverhang * 2),
        ) {
            DialogChoice(label = confirmLabel, onClick = confirm)
            DialogChoice(label = dismissLabel, onClick = dismiss)
        }
    }
}

/**
 * The card the design's dialogs sit on (the confirm dialog of 02-everyday/47, the rename dialog of
 * 01-onboarding/15): over a scrim of `color/bg/canvas` at 72%, a card on `color/bg/surface-raised` (radius 32, padding
 * 24, 16 between parts), 16 from the sides and 4 above the navigation bar, or above the keyboard while that is up.
 * Back and a tap on the scrim call [onDismiss].
 *
 * **Motion** (`design/spec/motion.md`, "Dialog"). The scrim fades in over 150 ms while the card rises 24 dp and grows
 * from 94% out of its bottom centre (`motion/spring/default`). Inside [AnimatedOverlay][com.expenses.app.ui.motion.AnimatedOverlay]
 * (pass its scope as [overlay]) it also leaves, faster than it came: it fades and shrinks to 96% as the scrim fades
 * out, and taps and Back are ignored while it leaves. Drawn on its own it only arrives. No haptic.
 *
 * @param title what screen readers call the dialog.
 * @param onDismiss called when the person leaves the dialog without choosing.
 * @param horizontalAlignment where the parts sit across the card: centred in a confirm dialog.
 * @param overlay the [AnimatedOverlay][com.expenses.app.ui.motion.AnimatedOverlay] scope this dialog is shown in, or
 * null for a dialog that only arrives.
 * @param content the parts of the card, top to bottom.
 */
@Composable
fun DialogCard(
    title: String,
    onDismiss: () -> Unit,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    overlay: OverlayScope? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val transition = overlayTransition(overlay)
    val reduced = LocalReducedMotion.current
    val dismiss by rememberUpdatedState(overlay?.guard(onDismiss) ?: onDismiss)
    val rise = with(LocalDensity.current) { Motion.RiseDistance.roundToPx() }
    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The window's own dim is replaced by the design's scrim. The dialog's window draws under the system bars, and
        // its bar icons would default to white: keep them dark in the light theme, as the screen under it has them.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val lightBars = !AppTheme.colors.isDark
        SideEffect {
            window?.let { dialogWindow ->
                dialogWindow.setDimAmount(0f)
                WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .ignoreTouchWhileLeaving(overlay)
                .pointerInput(Unit) { detectTapGestures { dismiss() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Scrim(transition, reduced, fadeInMillis = Motion.Duration.Short)
            transition.AnimatedVisibility(
                visible = { it },
                // Above the navigation bar, and above the keyboard when a field of the card is being typed in. Outside
                // the animated layer, so the card grows from its own bottom centre.
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(start = Space.s16, end = Space.s16, bottom = Space.s4),
                enter = if (reduced) EnterTransition.None else cardEnter(rise),
                exit = if (reduced) ExitTransition.None else CardExit,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.xxl))
                        .background(AppTheme.colors.bgSurfaceRaised)
                        // Taps on the card do not reach the scrim.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .semantics { paneTitle = title }
                        .padding(Space.s24),
                    verticalArrangement = Arrangement.spacedBy(Space.s16),
                    horizontalAlignment = horizontalAlignment,
                ) {
                    // A Secondary button on the grey card takes the lighter face, so it does not look disabled.
                    OnRaisedSurface { content() }
                }
            }
        }
    }
}

/**
 * One choice pill of [ChoiceDialog]: drawn 44 high, touchable 48 high. While pressed, the pressed overlay is drawn on
 * the pill itself, not on the taller touch area.
 */
@Composable
private fun DialogChoice(label: String, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChoiceTouchHeight)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = ChoiceTouchOverhang),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.full))
                .background(AppTheme.colors.bgSurface)
                .indication(interactions, LocalIndication.current)
                .padding(horizontal = Space.s16, vertical = Space.s12),
            contentAlignment = Alignment.Center,
        ) {
            AppText(text = label, style = AppTheme.type.bodyMStrong, textAlign = TextAlign.Center)
        }
    }
}

/**
 * A bottom sheet as the design draws it (02-everyday/37 Add all, 46 the friend's link): over a scrim, a sheet on
 * `color/bg/surface-raised` with its top corners rounded 32, padding 12 / 16 / 32 / 16, 16 between parts: the grab
 * handle, the [title] (Heading/L), then [content] (details card, note, buttons). A tap on the scrim calls
 * [onDismiss]. Draw it last inside a full-screen `Box`, over the screen it belongs to, and handle Back with a
 * `BackHandler` that calls [onDismiss].
 *
 * **Motion** (`design/spec/motion.md`, "Bottom sheet"). The sheet rises from below (`motion/spring/screen`) as the
 * scrim fades in over 250 ms. Dragged down and let go, it keeps the finger's speed: past 30% of its height or faster
 * than 1000 dp/s it closes (it calls [onDismiss] at once and flies on), otherwise it springs back up
 * (`motion/spring/default`). Crossing the dismiss line plays `haptic/threshold-in`, crossing back
 * `haptic/threshold-out`. Inside [AnimatedOverlay][com.expenses.app.ui.motion.AnimatedOverlay] (pass its scope as
 * [overlay]) it also drops away on close, and taps are ignored while it leaves; drawn on its own it only arrives. With
 * Remove animations on the drag still follows the finger and its haptics play, but nothing flies or springs.
 *
 * @param title the sheet's heading, for example "Priya Nair".
 * @param onDismiss called by a tap on the scrim, or a drag down past the dismiss line.
 * @param overlay the [AnimatedOverlay][com.expenses.app.ui.motion.AnimatedOverlay] scope this sheet is shown in, or
 * null for a sheet that only arrives.
 * @param content the parts under the title, top to bottom.
 */
@Composable
fun BottomSheet(
    title: String,
    onDismiss: () -> Unit,
    overlay: OverlayScope? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val transition = overlayTransition(overlay)
    val reduced = LocalReducedMotion.current
    val dismiss by rememberUpdatedState(overlay?.guard(onDismiss) ?: onDismiss)
    val haptics = LocalAppHaptics.current
    val scope = rememberCoroutineScope()
    val dismissSpeed = with(LocalDensity.current) { Motion.Sheet.DismissVelocityDpPerSecond.dp.toPx() }
    val drag = remember(scope, haptics) {
        SheetDrag(scope, haptics, dismissSpeed, onDismiss = { dismiss() }, isLeaving = { overlay?.isLeaving == true })
    }
    drag.reducedMotion = reduced
    Box(modifier = Modifier.fillMaxSize().ignoreTouchWhileLeaving(overlay)) {
        Box(modifier = Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { dismiss() } }) {
            Scrim(transition, reduced, fadeInMillis = Motion.ResultFade.InMillis)
        }
        transition.AnimatedVisibility(
            visible = { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.statusBars),
            enter = if (reduced) EnterTransition.None else SheetEnter,
            // Flung away by a drag, it is already flying: hold it while it does, instead of a second slide.
            exit = when {
                reduced -> ExitTransition.None
                drag.flung -> fadeOut(tween(Motion.Duration.Cap), targetAlpha = 1f)
                else -> SheetExit
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = drag.offset }
                    .onSizeChanged { drag.height = it.height }
                    .clip(RoundedCornerShape(topStart = Radius.xxl, topEnd = Radius.xxl))
                    .background(AppTheme.colors.bgSurfaceRaised)
                    // Taps on the sheet itself do not reach the scrim.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { paneTitle = title }
                    // A drag the content does not scroll moves the sheet.
                    .nestedScroll(drag)
                    .verticalScroll(rememberScrollState())
                    // 32 below the buttons, as drawn, with the gesture bar inside those 32 (8 above it at least).
                    .padding(start = Space.s16, top = SheetPaddingTop, end = Space.s16, bottom = maxOf(Space.s32, navBottom + Space.s8)),
                verticalArrangement = Arrangement.spacedBy(Space.s16),
            ) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(width = HandleWidth, height = HandleHeight)
                            .background(AppTheme.colors.borderStrong, RoundedCornerShape(Radius.full)),
                    )
                }
                AppText(
                    text = title,
                    style = AppTheme.type.headingL,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { heading() },
                )
                // A Secondary button on the grey sheet takes the lighter face, so it does not look disabled.
                OnRaisedSurface { content() }
            }
        }
    }
}

/** The scrim of a dialog or sheet: `color/bg/canvas` at 72%, fading in over [fadeInMillis] and out over 150 ms. */
@Composable
private fun BoxScope.Scrim(transition: Transition<Boolean>, reduced: Boolean, fadeInMillis: Int) {
    transition.AnimatedVisibility(
        visible = { it },
        modifier = Modifier.matchParentSize(),
        enter = if (reduced) EnterTransition.None else fadeIn(Motion.fade(fadeInMillis)),
        exit = if (reduced) ExitTransition.None else fadeOut(Motion.fade(Motion.Duration.Short)),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(AppTheme.colors.bgCanvas.copy(alpha = ScrimAlpha)))
    }
}

/** [overlay]'s transition, or for an overlay drawn on its own, one that arrives once and never leaves. */
@Composable
private fun overlayTransition(overlay: OverlayScope?): Transition<Boolean> {
    val alone = remember { MutableTransitionState(false) }
    alone.targetState = true
    val own = rememberTransition(alone, label = "overlay")
    return overlay?.transition ?: own
}

/** [OverlayScope.ignoreTouchWhileLeaving] when shown in an overlay. */
private fun Modifier.ignoreTouchWhileLeaving(overlay: OverlayScope?): Modifier =
    if (overlay == null) this else with(overlay) { this@ignoreTouchWhileLeaving.ignoreTouchWhileLeaving() }

/** The card grows from, and shrinks toward, its bottom centre. */
private val BottomCentre = TransformOrigin(0.5f, 1f)

/** The card's arrival: it rises [risePx] (24 dp) and grows from 94% out of its bottom centre as it fades in. */
private fun cardEnter(risePx: Int): EnterTransition =
    fadeIn(Motion.fade(Motion.Duration.Short)) +
        slideInVertically(Motion.Spring.Default.spec(IntOffset.VisibilityThreshold)) { risePx } +
        scaleIn(Motion.Spring.Default.spec(SCALE_THRESHOLD), initialScale = Motion.DialogScale, transformOrigin = BottomCentre)

/** The card leaves faster than it came: it fades out over 100 ms as it shrinks to 96%. */
private val CardExit: ExitTransition =
    fadeOut(Motion.fade(Motion.RevealFade.OutMillis, easing = Motion.Easings.Exit)) +
        scaleOut(Motion.Spring.Default.spec(SCALE_THRESHOLD), targetScale = Motion.EnterScale, transformOrigin = BottomCentre)

private val SheetEnter: EnterTransition = slideInVertically(Motion.Spring.Screen.spec(IntOffset.VisibilityThreshold)) { it }
private val SheetExit: ExitTransition = slideOutVertically(Motion.Spring.Screen.spec(IntOffset.VisibilityThreshold)) { it }

/** A scale stops within this of its target. */
private const val SCALE_THRESHOLD = 0.001f

/**
 * The drag of a [BottomSheet]: a drag its content does not scroll (down at the top, or any drag when it does not
 * scroll) moves the sheet by [offset]; letting go settles it by position and speed (`docs/motion.md` owner rule 2).
 */
@Stable
private class SheetDrag(
    private val scope: CoroutineScope,
    private val haptics: AppHaptics,
    private val dismissSpeed: Float,
    private val onDismiss: () -> Unit,
    private val isLeaving: () -> Boolean,
) : NestedScrollConnection {
    /** How far the sheet is pulled down from where it rests, in px. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** The sheet's height, in px. */
    var height by mutableIntStateOf(0)

    /** The sheet was let go past its dismiss line and is flying away. */
    var flung by mutableStateOf(false)
        private set

    var reducedMotion = false
    private var pastLine = false
    private var settling: Job? = null

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y < 0f && offset > 0f) Offset(0f, dragBy(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, dragBy(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset <= 0f || flung) return Velocity.Zero
        settle(available.y)
        return available
    }

    private fun dragBy(dy: Float): Float {
        if (flung) return 0f
        settling?.cancel()
        val before = offset
        offset = (offset + dy).coerceAtLeast(0f)
        val past = height > 0 && offset > height * Motion.Sheet.DismissFraction
        if (past != pastLine) {
            pastLine = past
            if (past) haptics.thresholdIn() else haptics.thresholdOut()
        }
        return offset - before
    }

    /** Closes past the line or fast enough downward, else springs back; the state changes first, the motion follows. */
    private fun settle(velocity: Float) {
        val close = offset > height * Motion.Sheet.DismissFraction || velocity > dismissSpeed
        pastLine = false
        if (close) {
            flung = true
            onDismiss()
        }
        settling = scope.launch {
            moveTo(if (close) height.toFloat() else 0f, velocity, if (close) Motion.Spring.Screen else Motion.Spring.Default)
            // The caller kept the sheet (it did not close): come back up.
            if (close && !isLeaving()) {
                flung = false
                moveTo(0f, 0f, Motion.Spring.Default)
            }
        }
    }

    private suspend fun moveTo(target: Float, velocity: Float, spring: SpringToken) {
        if (reducedMotion) {
            offset = target
        } else {
            animate(offset, target, velocity, spring.spec(OFFSET_THRESHOLD)) { value, _ -> offset = value }
        }
    }
}

/** The sheet's offset stops within half a pixel. */
private const val OFFSET_THRESHOLD = 0.5f
