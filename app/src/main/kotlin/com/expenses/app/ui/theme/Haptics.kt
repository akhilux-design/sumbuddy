package com.expenses.app.ui.theme

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.view.HapticFeedbackConstantsCompat

/**
 * The haptics of `design/spec/motion.md` ("Haptics"). Each has an Android constant and, on older phones, the fallback
 * the design table gives (see [androidConstant]).
 *
 * @property token the design token name.
 * @property isTick a light tick, dropped by [HapticPolicy] when another haptic played under 80 ms earlier. Confirm and
 * reject are not ticks and are never dropped.
 */
enum class Haptic(val token: String, val isTick: Boolean) {
    /** A 3D face goes down. Only on phones with rich haptics; not on the bottom-bar pill or buttons inside the swipe card. */
    Press("haptic/press", isTick = true),

    /** A tab change in the bottom bar; a selection changes in a chip, Me/Family, an option card or a category. */
    Selection("haptic/selection", isTick = true),

    /** A switch turns on, on its own or from its setting row. */
    ToggleOn("haptic/toggle-on", isTick = true),

    /** A switch turns off, on its own or from its setting row. */
    ToggleOff("haptic/toggle-off", isTick = true),

    /** The review card crosses the commit line; a dragged sheet crosses its dismiss line. */
    ThresholdIn("haptic/threshold-in", isTick = true),

    /** The review card or a dragged sheet crosses back. */
    ThresholdOut("haptic/threshold-out", isTick = true),

    /** Something the person started is done: success screens, group created or joined, reminder sent, a dialog action done. */
    Confirm("haptic/confirm", isTick = false),

    /** Something the person tried was refused: error shake, server refused, offline. */
    Reject("haptic/reject", isTick = false),
}

/**
 * The `HapticFeedbackConstantsCompat` value to play for this haptic on a phone running [sdk], with the older-phone
 * fallbacks of the design table: below API 34 selection and the "on"/"in" haptics play `CONTEXT_CLICK` and the
 * "off"/"out" haptics `CLOCK_TICK`; below API 30 confirm plays `VIRTUAL_KEY` and reject `LONG_PRESS`.
 */
fun Haptic.androidConstant(sdk: Int = Build.VERSION.SDK_INT): Int = when (this) {
    Haptic.Press -> HapticFeedbackConstantsCompat.VIRTUAL_KEY
    Haptic.Selection -> if (sdk >= 34) HapticFeedbackConstantsCompat.SEGMENT_TICK else HapticFeedbackConstantsCompat.CONTEXT_CLICK
    Haptic.ToggleOn -> if (sdk >= 34) HapticFeedbackConstantsCompat.TOGGLE_ON else HapticFeedbackConstantsCompat.CONTEXT_CLICK
    Haptic.ToggleOff -> if (sdk >= 34) HapticFeedbackConstantsCompat.TOGGLE_OFF else HapticFeedbackConstantsCompat.CLOCK_TICK
    Haptic.ThresholdIn ->
        if (sdk >= 34) HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstantsCompat.CONTEXT_CLICK
    Haptic.ThresholdOut ->
        if (sdk >= 34) HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstantsCompat.CLOCK_TICK
    Haptic.Confirm -> if (sdk >= 30) HapticFeedbackConstantsCompat.CONFIRM else HapticFeedbackConstantsCompat.VIRTUAL_KEY
    Haptic.Reject -> if (sdk >= 30) HapticFeedbackConstantsCompat.REJECT else HapticFeedbackConstantsCompat.LONG_PRESS
}

/**
 * Plays the app's haptics. Read it with [LocalAppHaptics]; never call `Vibrator` directly.
 *
 * Play at most one haptic per action, and only for something the person started. None for Back, scrolling, typing,
 * opening a dialog or sheet, Cancel, Not now, Done, Undo or disabled taps. The bottom-bar tab change is the one
 * navigation that ticks. A test can record what plays by overriding [play] alone.
 */
interface AppHaptics {
    /** Plays [haptic] (or drops it, as [HapticPolicy] decides). Every other function calls this. */
    fun play(haptic: Haptic)

    /** `haptic/press`: a 3D face goes down. */
    fun press() = play(Haptic.Press)

    /** `haptic/selection`: a selection changed, or a bottom-bar tab change. Never on a repeat tap of the chosen item. */
    fun selection() = play(Haptic.Selection)

    /** `haptic/toggle-on` or `haptic/toggle-off`, from the switch's new value [on]. */
    fun toggle(on: Boolean) = play(if (on) Haptic.ToggleOn else Haptic.ToggleOff)

    /** `haptic/threshold-in`: crossed the commit or dismiss line. */
    fun thresholdIn() = play(Haptic.ThresholdIn)

    /** `haptic/threshold-out`: crossed back. */
    fun thresholdOut() = play(Haptic.ThresholdOut)

    /** `haptic/confirm`: something the person started is done. Played at least 250 ms after the last press. */
    fun confirm() = play(Haptic.Confirm)

    /** `haptic/reject`: something the person tried was refused. */
    fun reject() = play(Haptic.Reject)
}

/** Plays nothing. The default of [LocalAppHaptics], so previews and tests are silent unless they provide another. */
object NoHaptics : AppHaptics {
    override fun play(haptic: Haptic) = Unit
}

/** The app's haptics. [com.expenses.app.ui.shell.AppRoot] provides [ViewHaptics]; anywhere else it is [NoHaptics]. */
val LocalAppHaptics = staticCompositionLocalOf<AppHaptics> { NoHaptics }

/**
 * The haptic rules of `design/spec/motion.md`, with no Android in it.
 * - A tick ([Haptic.isTick]) is dropped if another haptic played under [TickGapMillis] earlier.
 * - Confirm and reject are never dropped.
 * - Confirm plays at least [ConfirmAfterPressMillis] after the last press, delayed if needed (on a Pixel a press and
 *   a confirm close together feel like reject's double click).
 *
 * Not thread-safe: call it from the main thread only.
 *
 * @param nowMillis the clock, in milliseconds. Tests pass a fake one.
 */
class HapticPolicy(private val nowMillis: () -> Long = SystemClock::uptimeMillis) {
    /** What to do with a requested haptic. */
    sealed interface Decision {
        /** Play it now. */
        data object Play : Decision

        /** Do not play it. */
        data object Drop : Decision

        /** Play it after [delayMillis]. */
        data class PlayAfter(val delayMillis: Long) : Decision
    }

    private var lastPlayedAt: Long? = null
    private var lastPressAt: Long? = null

    /** Decides what happens to [haptic], requested now, and records it as played when it is not dropped. */
    fun decide(haptic: Haptic): Decision {
        val now = nowMillis()
        val last = lastPlayedAt
        if (haptic.isTick && last != null && now - last < TickGapMillis) return Decision.Drop
        val delay = if (haptic == Haptic.Confirm) {
            lastPressAt?.let { (it + ConfirmAfterPressMillis - now).coerceAtLeast(0L) } ?: 0L
        } else {
            0L
        }
        val playsAt = now + delay
        lastPlayedAt = maxOf(playsAt, last ?: playsAt)
        if (haptic == Haptic.Press) lastPressAt = now
        return if (delay > 0L) Decision.PlayAfter(delay) else Decision.Play
    }

    companion object {
        /** A tick is dropped if another haptic played less than this long before it (ms). */
        const val TickGapMillis: Long = 80L

        /** Confirm comes at least this long after the last press (ms). */
        const val ConfirmAfterPressMillis: Long = 250L
    }
}

/**
 * Plays haptics with `View.performHapticFeedback`, with no flags, so it follows the system Touch feedback setting and
 * needs no VIBRATE permission. Use the activity's root view: a dialog's own view detaches when it closes and a
 * confirm played through it afterwards would be lost.
 *
 * @param view the activity's root view.
 * @param richHaptics whether this phone has rich haptics ([hasRichHaptics]). Without them the press haptic is skipped,
 * so phones whose motors buzz feel no press; the other haptics always play.
 * @param policy the drop and delay rules.
 */
class ViewHaptics(
    private val view: View,
    private val richHaptics: Boolean,
    private val policy: HapticPolicy = HapticPolicy(),
) : AppHaptics {
    override fun play(haptic: Haptic) {
        if (haptic == Haptic.Press && !richHaptics) return
        when (val decision = policy.decide(haptic)) {
            HapticPolicy.Decision.Play -> perform(haptic)
            HapticPolicy.Decision.Drop -> Unit
            is HapticPolicy.Decision.PlayAfter -> view.postDelayed({ perform(haptic) }, decision.delayMillis)
        }
    }

    private fun perform(haptic: Haptic) {
        view.performHapticFeedback(haptic.androidConstant())
    }
}

/**
 * Whether this phone has rich haptics: API 30 or later, a vibrator, and support for the click and tick primitives.
 * Only the press haptic depends on it.
 */
fun hasRichHaptics(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    return runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator != null && vibrator.hasVibrator() && vibrator.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
        )
    }.getOrDefault(false)
}
