package com.expenses.app.ui.screens.review

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenses.app.R
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.BottomSheet
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.StatTile
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.core.EdgeHeight
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.icons.ArtIcon
import com.expenses.app.ui.motion.AnimatedOverlay
import com.expenses.app.ui.motion.FadeThroughContent
import com.expenses.app.ui.motion.OverlayScope
import com.expenses.app.ui.screens.payment.PaymentPage
import com.expenses.app.ui.screens.payment.monthName
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Each card behind the top one is this much lower, and this much narrower on each side (Figma 12 and 24). */
private val NextCardStep = 12.dp

/** Space under the top card's face for the cards behind it: the stack is 448 high for a 413 card (Figma). */
private val StackBottomPadding = 30.dp

/** The progress row is as high as the Undo button, so the stack does not move when Undo appears (Figma 49). */
private val ProgressRowMinHeight = 49.dp

/** The drag shown on the dragging screens: 64 across, 8 down, turned 6 degrees. */
private val DesignDrag = 64.dp
private val DesignDrop = 8.dp
private const val DesignTilt = 6f

/** How far the card is dragged before the stamp shows. */
private val StampDistance = 24.dp

/** Letting go past this part of the card's width decides; so does a fling faster than [FlingVelocity]. */
private const val DecideFraction = 0.35f
private val FlingVelocity = 1000.dp

/** A spring that moves the card stops this close to where it is going, so a fly-out ends once the card is out. */
private val CardSettleDistance = 1.dp

/** The 3D category icons in the breakdown. */
private val BreakdownIconSize = 32.dp

/**
 * Review payments (design 02-everyday/34 to 38): the waiting payments as a stack of cards. Swipe right or "Add"
 * adds the payment on top, swipe left or "Ignore" ignores it; TalkBack offers both as actions on the card. "Undo"
 * takes the last decision back. "Add all" in the top bar opens a sheet that adds every payment left at once.
 * When nothing is left, "All sorted" sums up the review.
 *
 * @param state what to show; see [ReviewPaymentsUiState]. [ReviewPaymentsUiState.NothingWaiting] draws the empty
 * frame, as the caller leaves the screen.
 * @param onEvent called with what the person did; see [ReviewPaymentsEvent].
 * @param modifier outer modifier. The screen fills the space it is given and keeps clear of the system bars.
 * @param reduceMotion true when animations are off (Remove animations; [LocalReducedMotion] by default): the card
 * follows the finger without tilting, does not fly, and the next one simply takes its place. Haptics still play.
 * @param initialDrag where the top card starts, dragged this far (right when positive), for previews of the
 * dragging states.
 */
@Composable
fun ReviewPaymentsScreen(
    state: ReviewPaymentsUiState,
    onEvent: (ReviewPaymentsEvent) -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = LocalReducedMotion.current,
    initialDrag: Dp = 0.dp,
) {
    // The caller leaves when nothing is waiting: the last screen shown stays drawn while it goes, and ignores taps.
    val last = remember { LastShown() }
    if (state is ReviewPaymentsUiState.Reviewing || state is ReviewPaymentsUiState.Sorted) last.state = state
    val leaving = state == ReviewPaymentsUiState.NothingWaiting && last.state != null
    val shown = if (leaving) last.state ?: state else state
    val events: (ReviewPaymentsEvent) -> Unit = if (leaving) ({}) else onEvent
    // Reviewing to All sorted swaps in place (fade-through); a new card keeps the same content. Cards that come quickly
    // replace the loading frame at once.
    FadeThroughContent(
        targetState = shown,
        modifier = modifier,
        contentKey = { it::class },
        isLoading = { it == ReviewPaymentsUiState.Loading },
    ) { current ->
        when (current) {
            ReviewPaymentsUiState.Loading, ReviewPaymentsUiState.NothingWaiting -> PaymentPage(
                title = stringResource(R.string.review_title),
                onBack = { events(ReviewPaymentsEvent.Back) },
            ) {}
            is ReviewPaymentsUiState.Reviewing -> ReviewingContent(current, events, reduceMotion, initialDrag, Modifier)
            is ReviewPaymentsUiState.Sorted -> SortedContent(current, events, Modifier)
        }
    }
}

/** The last Reviewing or Sorted state drawn, kept for the frames of leaving. Not state: it never redraws anything. */
private class LastShown {
    var state: ReviewPaymentsUiState? = null
}

/**
 * Where a card is while it is dragged, whether it is on its way out, and which side of the commit line it is on.
 *
 * @param flyBack true for a card brought back by Undo: it springs into place from off the side it left by, or from
 * where it is when it was still flying out ([caught]).
 * @param caught the card's fly-out, when Undo brought it back before it was out: the card carries on from it.
 */
@Stable
private class SwipeState(initialOffset: Float, val flyBack: Boolean, val caught: FlyingCard? = null) {
    var offset by mutableFloatStateOf(initialOffset)
    var leaving by mutableStateOf(false)

    /** 1 past the commit line on the Add side, -1 on the Ignore side, 0 under it ([commitSide]). */
    var armed by mutableIntStateOf(0)

    /** The spring the card is on (fly out, return or fly back). A finger taking the card stops it. */
    var motion: Job? = null

    /** How fast the last spring moved the card when it last moved (px/s), so a spring that takes over keeps it. */
    var velocity = 0f
        private set

    /** Springs the card to [target], starting from where it is at [initialVelocity] (px/s), and stops within [settle] px. */
    suspend fun springTo(target: Float, initialVelocity: Float, settle: Float) {
        animate(offset, target, initialVelocity, Motion.Spring.Default.spec(visibilityThreshold = settle)) { value, speed ->
            offset = value
            velocity = speed
        }
    }
}

/**
 * A decided card still flying out over the next one. Its decision is already sent: the card is drawing only.
 *
 * @param grown how far the card was still growing into place (the stack) when it was decided; it keeps that size.
 */
private class FlyingCard(val card: ReviewCard, val swipe: SwipeState, val grown: Float)

/** One card drawn in the stack: the top card ([flying] null) or one flying out. */
private class CardLayer(val card: ReviewCard, val swipe: SwipeState, val flying: FlyingCard?)

/**
 * The side of the commit line the card is on after a move to [offset] (px, right is positive), given the side it was
 * armed on before ([armed]: 1 Add, -1 Ignore, 0 none). It arms when it reaches [line] from the middle, and stays armed
 * until it is back under `line - hysteresis` (`design/spec/motion.md`, "Review swipe fling"), so a finger resting on
 * the line does not tick again and again. Crossing to the other side arms that side.
 */
internal fun commitSide(armed: Int, offset: Float, line: Float, hysteresis: Float): Int {
    val side = when {
        offset > 0f -> 1
        offset < 0f -> -1
        else -> 0
    }
    if (armed != 0 && side == armed && abs(offset) >= line - hysteresis) return armed
    return if (side != 0 && abs(offset) >= line) side else 0
}

/**
 * What the stack remembers between cards: the last position and depth, and the last stack spring, so a new card
 * starts from where the stack is and how fast it moves.
 */
private class StackMemory(var position: Int, var cardsBehind: Int) {
    var stack: Animatable<Float, AnimationVector1D>? = null
}

@Composable
private fun ReviewingContent(
    state: ReviewPaymentsUiState.Reviewing,
    onEvent: (ReviewPaymentsEvent) -> Unit,
    reduceMotion: Boolean,
    initialDrag: Dp,
    modifier: Modifier,
) {
    val card = state.card
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = LocalAppHaptics.current
    var stackWidth by remember { mutableFloatStateOf(0f) }
    // The side the last decision flew out by, so Undo brings that card back from the same side.
    var lastSide by remember { mutableIntStateOf(0) }
    val memory = remember { StackMemory(state.position, state.cardsBehind) }
    // Decided cards still flying out, drawn over the top card.
    val flying = remember { mutableStateListOf<FlyingCard>() }
    val forward = state.position > memory.position
    val back = state.position < memory.position
    // A new state for every card, so the next card (or one brought back by Undo) starts in place, or off the side. A
    // card Undo brings back while it still flies out carries on from where it is, at its speed.
    val swipe = remember(card.id) {
        val caught = if (back) flying.firstOrNull { it.card.id == card.id } else null
        val flyBack = back && !reduceMotion && stackWidth > 0f && (caught != null || lastSide != 0)
        val start = when {
            caught != null -> Snapshot.withoutReadObservation { caught.swipe.offset }
            flyBack -> stackWidth * Motion.Review.FlyOutWidths * lastSide
            else -> with(density) { initialDrag.toPx() }
        }
        SwipeState(start, flyBack, caught)
    }
    // The stack: 0 at rest; a new card starts at 1 (it was the next card) and an undone one at -1, then springs to 0
    // from where the stack was and as fast as it moved.
    val stack = remember(card.id) {
        val shift = if (forward) 1f else if (back) -1f else 0f
        Animatable(if (reduceMotion) 0f else ((memory.stack?.value ?: 0f) + shift).coerceIn(-2f, 2f))
    }
    val stackVelocity = remember(card.id) { if (reduceMotion) 0f else memory.stack?.velocity ?: 0f }
    // A card newly shown at the back of the stack fades in.
    val reveal = remember(card.id) {
        Animatable(if (forward && !reduceMotion && state.cardsBehind >= memory.cardsBehind) 0f else 1f)
    }
    SideEffect {
        memory.position = state.position
        memory.cardsBehind = state.cardsBehind
        memory.stack = stack
    }
    LaunchedEffect(stack) {
        launch { reveal.animateTo(1f, Motion.fade(Motion.Duration.Short)) }
        stack.animateTo(0f, Motion.Spring.Default.spec(visibilityThreshold = StackSettle), initialVelocity = stackVelocity)
    }
    val settle = with(density) { CardSettleDistance.toPx() }
    LaunchedEffect(swipe) {
        var velocity = 0f
        swipe.caught?.let { out ->
            // Undo caught it on its way out: it stops flying and springs back from there, at the speed it had.
            out.swipe.motion?.cancel()
            flying.remove(out)
            swipe.offset = out.swipe.offset
            velocity = out.swipe.velocity
        }
        if (swipe.flyBack) swipe.motion = scope.launch { swipe.springTo(0f, velocity, settle) }
    }

    fun decide(decision: ReviewDecision, velocity: Float = 0f) {
        if (swipe.leaving) return
        swipe.leaving = true
        swipe.motion?.cancel()
        val side = if (decision == ReviewDecision.Add) 1 else -1
        lastSide = side
        // Sent at once, never after the animation (docs/motion.md 3.8): the next card takes the top while this one
        // flies out over it, and nothing is lost if the screen goes meanwhile.
        onEvent(ReviewPaymentsEvent.Decide(card.id, decision))
        if (reduceMotion || stackWidth == 0f) return
        // It flies out at the speed it was let go with (none for the buttons), tilting up to 12 degrees.
        val out = FlyingCard(card, swipe, grown = stack.value.coerceAtLeast(0f))
        flying.add(out)
        swipe.motion = scope.launch {
            try {
                swipe.springTo(stackWidth * Motion.Review.FlyOutWidths * side, velocity, settle)
            } finally {
                flying.remove(out)
            }
        }
    }

    val sheetOpen = state.addAll != null
    BackHandler(enabled = sheetOpen) { onEvent(ReviewPaymentsEvent.CancelAddAll) }

    Box(modifier = modifier.fillMaxSize()) {
        PaymentPage(
            title = stringResource(R.string.review_title),
            onBack = { onEvent(ReviewPaymentsEvent.Back) },
            // While the sheet is open, screen readers see only the sheet.
            modifier = if (sheetOpen) Modifier.clearAndSetSemantics {} else Modifier,
            actionLabel = stringResource(R.string.review_add_all),
            onAction = { onEvent(ReviewPaymentsEvent.OpenAddAll) },
            footer = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                    AppButton(
                        label = stringResource(R.string.review_ignore),
                        onClick = { decide(ReviewDecision.Ignore) },
                        modifier = Modifier.weight(1f),
                        style = ButtonStyle.Secondary,
                    )
                    AppButton(
                        label = stringResource(R.string.review_add),
                        onClick = { decide(ReviewDecision.Add) },
                        modifier = Modifier.weight(1f),
                    )
                }
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ProgressRowMinHeight),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = stringResource(R.string.review_progress, state.position, state.total),
                    style = AppTheme.type.title,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (state.canUndo) {
                    AppButton(
                        label = stringResource(R.string.review_undo),
                        // Not until the next card shows: Undo then follows the decision it takes back.
                        onClick = { if (!swipe.leaving) onEvent(ReviewPaymentsEvent.Undo) },
                        style = ButtonStyle.Ghost,
                        size = ButtonSize.Medium,
                    )
                }
            }
            val addLabel = stringResource(R.string.review_add)
            val ignoreLabel = stringResource(R.string.review_ignore)
            val colors = AppTheme.colors
            val cardsBehind = state.cardsBehind
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { stackWidth = it.width.toFloat() }
                    .padding(bottom = StackBottomPadding)
                    // The plain cards behind the top one, drawn at their depth plus the stack's move.
                    .drawBehind {
                        drawNextCards(cardsBehind, stack.value, reveal.value, colors.bgSurfaceRaised, colors.borderSubtle)
                    },
            ) {
                // The top card, then the cards still flying out over it, each keyed by its payment: a card keeps its
                // drawing as it goes from top to flying, and the next card starts fresh (not in the last one's colours).
                // A card Undo caught on its way out is the top card again at once, from where it was.
                val out = flying.filter { it !== swipe.caught }
                val layers = buildList {
                    if (out.none { it.card.id == card.id }) add(CardLayer(card, swipe, flying = null))
                    out.forEach { add(CardLayer(it.card, it.swipe, it)) }
                }
                layers.forEach { layer ->
                    key(layer.card.id) {
                        ReviewCardLayer(
                            layer = layer,
                            stack = { stack.value },
                            reduceMotion = reduceMotion,
                            actions = listOf(
                                CustomAccessibilityAction(addLabel) { decide(ReviewDecision.Add); true },
                                CustomAccessibilityAction(ignoreLabel) { decide(ReviewDecision.Ignore); true },
                            ),
                            onChangeCategory = { onEvent(ReviewPaymentsEvent.ChangeCategory(layer.card.id)) },
                            onSplit = { onEvent(ReviewPaymentsEvent.Split(layer.card.id)) },
                            onDragStopped = { velocity ->
                                val dragged = layer.swipe
                                val threshold = stackWidth * DecideFraction
                                val fling = with(density) { FlingVelocity.toPx() }
                                val decision = when {
                                    dragged.offset > threshold || (velocity > fling && dragged.offset > 0) ->
                                        ReviewDecision.Add
                                    dragged.offset < -threshold || (velocity < -fling && dragged.offset < 0) ->
                                        ReviewDecision.Ignore
                                    else -> null
                                }
                                when {
                                    decision != null -> {
                                        // A fling that decides before the line still crosses it: one tick as it goes.
                                        val side = if (decision == ReviewDecision.Add) 1 else -1
                                        if (dragged.armed != side) {
                                            haptics.thresholdIn()
                                            dragged.armed = side
                                        }
                                        decide(decision, velocity)
                                    }
                                    else -> {
                                        // Let go short of the line, it goes back under it: a card still armed (inside
                                        // the hysteresis) crosses back now.
                                        if (dragged.armed != 0) {
                                            haptics.thresholdOut()
                                            dragged.armed = 0
                                        }
                                        if (reduceMotion) {
                                            dragged.offset = 0f
                                        } else {
                                            // It springs back, carrying the speed it was let go with.
                                            dragged.motion = scope.launch { dragged.springTo(0f, velocity, settle) }
                                        }
                                    }
                                }
                            },
                            onDrag = { delta ->
                                val dragged = layer.swipe
                                dragged.offset += delta
                                // The commit line: a tick when the card crosses it, and when it crosses back.
                                val hysteresis = with(density) { Motion.Review.CommitHysteresis.toPx() }
                                val side = commitSide(dragged.armed, dragged.offset, stackWidth * DecideFraction, hysteresis)
                                if (side != dragged.armed) {
                                    if (side == 0) haptics.thresholdOut() else haptics.thresholdIn()
                                    dragged.armed = side
                                }
                            },
                        )
                    }
                }
            }
            AppText(
                text = stringResource(R.string.review_hint),
                style = AppTheme.type.caption,
                modifier = Modifier.fillMaxWidth(),
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        AnimatedOverlay(state.addAll) { summary ->
            AddAllSheet(
                summary = summary,
                onConfirm = guard { onEvent(ReviewPaymentsEvent.ConfirmAddAll) },
                onCancel = { onEvent(ReviewPaymentsEvent.CancelAddAll) },
                overlay = this,
            )
        }
    }
}

/**
 * One card of the stack ([CardLayer]). The top card follows the finger ([onDrag], [onDragStopped]) and offers the
 * TalkBack [actions]; a card flying out is drawing only: no drag, no actions, hidden from accessibility, and its
 * buttons do nothing.
 *
 * @param stack the stack's move, read while drawing: the top card grows into place from the next card's size.
 */
@Composable
private fun ReviewCardLayer(
    layer: CardLayer,
    stack: () -> Float,
    reduceMotion: Boolean,
    actions: List<CustomAccessibilityAction>,
    onChangeCategory: () -> Unit,
    onSplit: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
) {
    val swipe = layer.swipe
    val flying = layer.flying
    val density = LocalDensity.current
    val stamp by remember(swipe) {
        derivedStateOf {
            val offsetDp = with(density) { swipe.offset.toDp() }
            when {
                offsetDp >= StampDistance -> SwipeStamp.Add
                offsetDp <= -StampDistance -> SwipeStamp.Ignore
                else -> SwipeStamp.None
            }
        }
    }
    val drag by rememberUpdatedState(onDrag)
    val dragState = remember(swipe) { DraggableState { delta -> if (!swipe.leaving) drag(delta) } }
    SwipeCard(
        card = layer.card,
        stamp = stamp,
        armed = swipe.armed != 0,
        onChangeCategory = { if (flying == null) onChangeCategory() },
        onSplit = { if (flying == null) onSplit() },
        actions = if (flying == null) actions else emptyList(),
        modifier = Modifier
            .then(if (flying == null) Modifier else Modifier.clearAndSetSemantics { })
            .graphicsLayer {
                // The finger always moves the card; the tilt, the drop and the stack move are motion.
                translationX = swipe.offset
                if (!reduceMotion) {
                    val dragged = swipe.offset / DesignDrag.toPx()
                    val grown = flying?.grown ?: stack().coerceAtLeast(0f)
                    if (size.width > 0f) {
                        scaleX = (size.width - Motion.Review.NextCardNarrower.toPx() * grown) / size.width
                    }
                    translationY = (abs(dragged) * DesignDrop.toPx()).coerceAtMost(DesignDrop.toPx() * 2) +
                        Motion.Review.NextCardLower.toPx() * grown
                    rotationZ = (dragged * DesignTilt)
                        .coerceIn(-Motion.Review.MaxTiltDegrees, Motion.Review.MaxTiltDegrees)
                }
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = flying == null && !swipe.leaving,
                // The finger takes the card from wherever a spring has it.
                onDragStarted = { swipe.motion?.cancel() },
                onDragStopped = { velocity -> onDragStopped(velocity) },
            ),
    )
}

/** The stack spring stops this close to rest (a fraction of one card step). */
private const val StackSettle = 0.002f

/**
 * The plain cards behind the top one, in the stack box: card depth 1 is 12 lower and 24 narrower, 2 is 24 lower and
 * 48 narrower, each as high as the top card's face (which lies [EdgeHeight] above the box's bottom). While the stack
 * moves each is drawn at its depth plus [shift]; the one at the back fades in with [backAlpha].
 */
private fun DrawScope.drawNextCards(cardsBehind: Int, shift: Float, backAlpha: Float, fill: Color, stroke: Color) {
    val step = NextCardStep.toPx()
    val height = size.height - EdgeHeight.toPx()
    val r = Radius.xl.toPx()
    val w = SubtleStroke.toPx()
    for (depth in cardsBehind downTo 1) {
        val at = (depth + shift).coerceAtLeast(0f)
        val inset = step * at
        val width = size.width - 2f * inset
        if (width <= w || height <= w) continue
        val alpha = if (depth == cardsBehind) backAlpha else 1f
        drawRoundRect(fill, Offset(inset, inset), Size(width, height), CornerRadius(r, r), alpha = alpha)
        drawRoundRect(
            color = stroke,
            topLeft = Offset(inset + w / 2f, inset + w / 2f),
            size = Size(width - w, height - w),
            cornerRadius = CornerRadius(r - w / 2f, r - w / 2f),
            style = Stroke(w),
            alpha = alpha,
        )
    }
}

/**
 * The Add all sheet (design 37), a [BottomSheet] shown in [overlay]: the question, the breakdown by category with the
 * total, the note, "Add all N" and "Cancel". A tap on the scrim, or a drag down past the dismiss line, cancels.
 */
@Composable
private fun AddAllSheet(summary: AddAllSummary, onConfirm: () -> Unit, onCancel: () -> Unit, overlay: OverlayScope) {
    BottomSheet(
        title = pluralStringResource(R.plurals.review_add_all_title, summary.count, summary.count),
        onDismiss = onCancel,
        overlay = overlay,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.bgSurface, RoundedCornerShape(Radius.lg))
                .padding(Space.s12),
            verticalArrangement = Arrangement.spacedBy(Space.s12),
        ) {
            summary.byCategory.forEach { BreakdownRow(it) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AppTheme.colors.borderSubtle),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(text = stringResource(R.string.review_add_all_total), style = AppTheme.type.title)
                AppText(text = formatRupees(summary.totalMinor), style = AppTheme.type.amountM)
            }
        }
        AppText(
            text = stringResource(R.string.review_add_all_note),
            style = AppTheme.type.bodyM,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
        )
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            AppButton(
                label = pluralStringResource(R.plurals.review_add_all_confirm, summary.count, summary.count),
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                label = stringResource(R.string.review_add_all_cancel),
                onClick = overlay.guard(onCancel),
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        }
    }
}

/** One category of the breakdown: its 3D icon, name and count, and how much. */
@Composable
private fun BreakdownRow(total: CategoryTotal) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtIcon(icon = total.category.icon, size = BreakdownIconSize)
        Column(modifier = Modifier.weight(1f)) {
            AppText(text = stringResource(total.category.label), style = AppTheme.type.bodyMStrong)
            AppText(
                text = pluralStringResource(R.plurals.review_add_all_count, total.count, total.count),
                style = AppTheme.type.caption,
                color = AppTheme.colors.textSecondary,
            )
        }
        AppText(text = formatRupees(total.totalMinor), style = AppTheme.type.bodyMStrong)
    }
}

/**
 * All sorted (design 38): laid out like Expense added, with what was added and ignored and the month so far. It has
 * no back arrow; Back does what Done does (the run ends), so a later review never carries this one's counts.
 */
@Composable
private fun SortedContent(
    state: ReviewPaymentsUiState.Sorted,
    onEvent: (ReviewPaymentsEvent) -> Unit,
    modifier: Modifier,
) {
    BackHandler { onEvent(ReviewPaymentsEvent.Done) }
    val month = monthName(state.month)
    PaymentPage(
        title = null,
        onBack = null,
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.review_done),
                onClick = { onEvent(ReviewPaymentsEvent.Done) },
                modifier = Modifier.fillMaxWidth(),
            )
            // Hidden when only splits were made, as the stack hides Undo with nothing to take back.
            if (state.canUndo) {
                AppButton(
                    label = stringResource(R.string.review_undo),
                    onClick = { onEvent(ReviewPaymentsEvent.Undo) },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Ghost,
                    size = ButtonSize.Medium,
                )
            }
        },
    ) {
        // The design's tile starts 40dp below the status bar: 8 from the page, 20 here and the 12dp gap.
        Spacer(Modifier.height(Space.s20))
        StickerTile(icon = AppIcon.CheckCircle, tone = StickerTone.Brand, arrival = true)
        AppText(
            text = stringResource(R.string.review_sorted_title),
            style = AppTheme.type.displayL,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() },
        )
        AppText(
            text = pluralStringResource(R.plurals.review_sorted_body, state.sortedCount, state.sortedCount),
            style = AppTheme.type.bodyL,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
        )
        if (state.addedCount > 0) {
            val amount = formatRupees(state.addedMinor)
            InfoRow(
                title = stringResource(R.string.review_sorted_added, state.addedCount),
                subtitle = if (state.addedAllInMonth) {
                    stringResource(R.string.review_sorted_added_body, amount, month)
                } else {
                    stringResource(R.string.review_sorted_added_body_any_month, amount)
                },
                icon = AppIcon.Check,
            )
        }
        if (state.ignoredCount > 0) {
            InfoRow(
                title = stringResource(R.string.review_sorted_ignored, state.ignoredCount),
                subtitle = stringResource(R.string.review_sorted_ignored_body),
                icon = AppIcon.Ban,
            )
        }
        StatTile(
            label = stringResource(R.string.review_sorted_month_so_far, month),
            value = formatRupees(state.monthTotalMinor),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
