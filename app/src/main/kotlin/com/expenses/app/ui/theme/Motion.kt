package com.expenses.app.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * A spring from `design/spec/motion.md` ("Springs"): a damping ratio and a stiffness, with mass 1.
 *
 * Everything that moves, scales or resizes uses one of these. A spring has no fixed length: it starts from the
 * current value and velocity (`Animatable.animateTo` keeps both), so an interrupted motion carries on smoothly.
 *
 * @property token the design token name, for example `motion/spring/press`.
 * @property dampingRatio Compose damping ratio; 1 is no bounce, lower bounces more.
 * @property stiffness Compose stiffness.
 */
@Immutable
data class SpringToken(val token: String, val dampingRatio: Float, val stiffness: Float) {
    /** The Figma prototype damping for this spring (mass 1): 2 x ratio x sqrt(stiffness). */
    val figmaDamping: Float get() = 2f * dampingRatio * sqrt(stiffness)

    /**
     * The Compose spec for this spring. Pass the type's visibility threshold (for example `Dp.VisibilityThreshold` or
     * `IntOffset.VisibilityThreshold`) when animating something other than a plain float fraction.
     */
    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = dampingRatio, stiffness = stiffness, visibilityThreshold = visibilityThreshold)
}

/**
 * Every motion token of `design/spec/motion.md`. The design is the source of truth: when the body of
 * `docs/motion.md` gives a different value, the design's value is used here (its section "Where this differs from
 * the body of docs/motion.md").
 *
 * Screens and components never write raw durations, springs or distances; they use these.
 * - Moves, scales and resizes: a [Spring] token, via [SpringToken.spec].
 * - Fades and colour changes: a short tween, via [fade] with an [Easings] token. No tween is longer than [Duration.Cap].
 * - Remove animations: wrap a spec in [motion] (it becomes `snap()`), or read [LocalReducedMotion].
 */
object Motion {
    /** `motion/spring/...`. Durations in the KDoc are roughly how long each takes to land. */
    object Spring {
        /** `motion/spring/press`, 1.0 / 3800: lands in about 60 ms, no bounce. The 3D face going down onto its edge. */
        val Press = SpringToken("motion/spring/press", dampingRatio = 1.0f, stiffness = 3800f)

        /** `motion/spring/press-release`, 0.5 / 1500: about 200 ms, about 0.65 dp overshoot. The 3D face coming back up. */
        val PressRelease = SpringToken("motion/spring/press-release", dampingRatio = 0.5f, stiffness = 1500f)

        /** `motion/spring/fast`, 0.9 / 1400: about 120 ms, no visible bounce. Chip, option card and segment faces; nav label. */
        val Fast = SpringToken("motion/spring/fast", dampingRatio = 0.9f, stiffness = 1400f)

        /** `motion/spring/pill`, 0.8 / 1400: about 140 ms, about 1.5% overshoot. Bottom-bar pill, Me/Family pill, switch knob. */
        val Pill = SpringToken("motion/spring/pill", dampingRatio = 0.8f, stiffness = 1400f)

        /** `motion/spring/default`, 0.9 / 700: about 170 ms. List placement, dialog card, review card, sheet settle, Reveal. */
        val Default = SpringToken("motion/spring/default", dampingRatio = 0.9f, stiffness = 700f)

        /** `motion/spring/screen`, 0.9 / 300: about 260 ms. Push, pop, modal rise, sheet in and out, tab content, result. */
        val Screen = SpringToken("motion/spring/screen", dampingRatio = 0.9f, stiffness = 300f)

        /** `motion/spring/pop`, 0.5 / 600: about 320 ms, 16% overshoot. Success sticker, radio dot, review stamp. */
        val Pop = SpringToken("motion/spring/pop", dampingRatio = 0.5f, stiffness = 600f)

        /** `motion/spring/shake`, 0.2 / 1200: swings about 8, 4, 2 dp from a [ShakeKickDpPerSecond] kick; rests in about 450 ms. */
        val Shake = SpringToken("motion/spring/shake", dampingRatio = 0.2f, stiffness = 1200f)

        /** All of them, in the order of the design table. */
        val all: List<SpringToken> = listOf(Press, PressRelease, Fast, Pill, Default, Screen, Pop, Shake)
    }

    /**
     * `motion/duration/...` in milliseconds. [Press], [Short] (for small moves), [Medium] and [Long] are spring
     * targets, not durations to code: they are what the named spring roughly takes. The tween values are real.
     */
    object Duration {
        /** `motion/duration/press`, 60 ms: what [Spring.Press] takes to land. */
        const val Press: Int = 60

        /** `motion/duration/short`, 150 ms: tween for fades, colour, border and scrim out. */
        const val Short: Int = 150

        /** `motion/duration/medium`, 250 ms: spring target for the dialog card and Back. */
        const val Medium: Int = 250

        /** `motion/duration/long`, 300 ms: spring target for push, modal rise, sheet in, result arrival. */
        const val Long: Int = 300

        /** No tween is longer than this. Springs have no fixed length. */
        const val Cap: Int = 350
    }

    /**
     * `motion/hold/press`, 100 ms: a press stays visible at least this long after it starts, counted on the frame
     * clock. Not motion: it is kept when Remove animations is on.
     */
    const val PressHoldMillis: Long = 100L

    /** Flat pressed overlay and Ghost pill: fade in (ms). */
    const val PressFadeInMillis: Int = 50

    /** Flat pressed overlay and Ghost pill: fade out after a release (ms). */
    const val PressFadeOutMillis: Int = 150

    /** Flat pressed overlay: fade out after a cancelled press (ms). No hold. */
    const val PressCancelFadeOutMillis: Int = 100

    /** `motion/delay/placeholder`, 200 ms: loading placeholders fade in only after this (a tween delay). */
    const val PlaceholderDelayMillis: Int = 200

    /** Placeholder pulse: 900 ms each way, the only loop in the app. Static when Remove animations is on. */
    const val PlaceholderPulseMillis: Int = 900

    /** Placeholder pulse: the alpha it pulses down to (and back to 1). */
    const val PlaceholderPulseAlpha: Float = 0.55f

    /** Fade-through for content that swaps in place: the old content fades out over [OutMillis]; the new one fades in over [InMillis], starting after [InDelayMillis]. */
    object FadeThrough {
        const val OutMillis: Int = 90
        const val InMillis: Int = 160
        const val InDelayMillis: Int = 90
    }

    /** Reveal: fade in over [InMillis] while it opens with [Spring.Default]; fade out over [OutMillis] while it closes, a little faster. */
    object RevealFade {
        const val InMillis: Int = 150
        const val OutMillis: Int = 100
    }

    /** Result arrival (and arrival at a tab from outside): fade in over [InMillis], the form under it out over [OutMillis]; scale with [Spring.Screen]. */
    object ResultFade {
        const val InMillis: Int = 250
        const val OutMillis: Int = 150
    }

    /** Success: the sticker pops about this long after a result screen arrives (ms). */
    const val SuccessDelayMillis: Int = 100

    /** `motion/distance/axis`, 30 dp: push and pop slide; the screen under a back swipe. */
    val AxisDistance: Dp = 30.dp

    /** `motion/distance/press`, 4 dp: the 3D face drop; it leaves a 1 dp lip of the 5 dp edge. */
    val PressDistance: Dp = 4.dp

    /** `motion/distance/rise`, 24 dp: the dialog card rise. */
    val RiseDistance: Dp = 24.dp

    /** `motion/distance/shake`, 8 dp: the first swing of the error shake (the result of [ShakeKickDpPerSecond]). */
    val ShakeDistance: Dp = 8.dp

    /** The sideways kick that starts the error shake, in dp per second. With [Spring.Shake] it swings about 8, 4, 2 dp. */
    const val ShakeKickDpPerSecond: Float = 365f

    /** `motion/fraction/modal`, 1/8: a modal screen rises this fraction of its height. */
    const val ModalRiseFraction: Float = 1f / 8f

    /** `motion/scale/enter`, 0.96: result screens, arrival at a tab, tab content. */
    const val EnterScale: Float = 0.96f

    /** `motion/scale/dialog`, 0.94: the dialog card, grown from its bottom centre. It leaves by shrinking to [EnterScale]. */
    const val DialogScale: Float = 0.94f

    /** `motion/scale/predictive`, 0.90: the leaving screen during a back swipe. */
    const val PredictiveScale: Float = 0.90f

    /** `motion/scale/pop`, 0.5: the success sticker starts here, peaks near 1.08 and rests at 1. */
    const val PopScale: Float = 0.5f

    /** Review card values ("Review card" and "Commit line" in the design). */
    object Review {
        /** The card flies out this many card widths. */
        const val FlyOutWidths: Float = 1.5f

        /** Largest tilt of a card flying out, in degrees. */
        const val MaxTiltDegrees: Float = 12f

        /** The next card grows from this much narrower. */
        val NextCardNarrower: Dp = 24.dp

        /** The next card grows from this much lower. */
        val NextCardLower: Dp = 12.dp

        /** The commit line disarms only this far back under it, so it does not chatter. */
        val CommitHysteresis: Dp = 8.dp
    }

    /** Bottom sheet drag ("Sheet dismiss"): a dragged sheet closes past [DismissFraction] of its height or above [DismissVelocityDpPerSecond]. */
    object Sheet {
        const val DismissFraction: Float = 0.3f
        const val DismissVelocityDpPerSecond: Float = 1000f
    }

    /** `state/pressed`: the flat pressed overlay is `color/text/primary` at this alpha. (`state/pressed-ghost` is `color/brand/soft` at 100%.) */
    const val PressedOverlayAlpha: Float = 0.10f

    /**
     * Easings for fades and colour changes only (tweens). They are never used for movement: movement is a spring.
     * [Standard] is for alpha and colour on something that stays; [Enter] for something fading in as it arrives;
     * [Exit] for something fading out as it leaves.
     */
    object Easings {
        val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    }

    /**
     * A tween for a fade or a colour change. The length is capped at [Duration.Cap] (delay included). Delays are
     * part of the spec, so they scale with the animator duration scale and vanish when Remove animations is on.
     */
    fun <T> fade(durationMillis: Int, delayMillis: Int = 0, easing: Easing = Easings.Standard): TweenSpec<T> {
        val delay = delayMillis.coerceIn(0, Duration.Cap)
        return tween(durationMillis = durationMillis.coerceIn(0, Duration.Cap - delay), delayMillis = delay, easing = easing)
    }
}

/**
 * True when the person turned animations off (Settings, Accessibility, Remove animations, or the animator duration
 * scale set to 0). Provided by [AppTheme]; `false` outside it. Read it to pick `None` transitions or to skip motion
 * that is not a plain spec (loops, gesture transforms, the shake). The press hold and every haptic stay either way.
 */
val LocalReducedMotion = compositionLocalOf { false }

/**
 * Returns [spec], or `snap()` when [LocalReducedMotion] is on. Compose already ends animations on the next frame when
 * the animator duration scale is 0; this also makes previews and captures that turn motion off deterministic.
 */
@Composable
@ReadOnlyComposable
fun <T> motion(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap() else spec

/**
 * Whether the animator duration scale is 0 (Remove animations), followed live: switching the setting while the app
 * is open updates it without a restart (`docs/motion.md` 3.7). [AppTheme] provides it as [LocalReducedMotion];
 * prefer reading that.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = read()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        reduced = read()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
