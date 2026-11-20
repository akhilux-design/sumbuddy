package com.expenses.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.theme.AppHaptics
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.Space
import com.expenses.app.ui.theme.motion

private val SwitchTrackWidth = 52.dp
private val SwitchTrackHeight = 32.dp
private val SwitchKnobSize = 24.dp

/** Smallest touch area of a switch that can be tapped. */
private val SwitchTouchTarget = 48.dp

/** How far the touch area reaches past the track above and below: 8 + 32 + 8 = 48. */
private val SwitchTouchOverhang = (SwitchTouchTarget - SwitchTrackHeight) / 2

/** Border of the track when the switch is off. The on state has no border. */
private val SwitchOffBorderWidth = 1.5.dp

/** Where the knob starts when off: the 4 padding plus the 1.5 border, which Figma counts as part of the layout. */
private val SwitchKnobStartOff = Space.s4 + SwitchOffBorderWidth

/** Where the knob starts when on: against the 4 padding at the far end of the track. */
private val SwitchKnobStartOn = SwitchTrackWidth - Space.s4 - SwitchKnobSize

/**
 * An on/off switch: a 52 x 32 pill-shaped track with a round knob. It is flat (no raised edge).
 *
 * Off: grey track with a border, grey knob at the start. On: green track without a border, dark knob at the end.
 * The knob position shows the state, so it does not depend on colour alone.
 *
 * Motion (`design/spec/motion.md`, "Chip and toggle"): the knob slides with `motion/spring/pill` (a slight
 * overshoot) and continues from where it is when tapped again mid-slide; the track, knob and border colours change
 * over 150 ms. Both are read only while drawing, so the switch does not recompose on every frame. Flipping the switch
 * with its own touch area plays `haptic/toggle-on` or `haptic/toggle-off`; a row that owns the tap plays it itself.
 *
 * The switch keeps the exact size of the design in the layout. When it can be tapped, its own touch area is 48dp
 * high: it reaches 8dp past the track above and below without moving anything, and it keeps working inside a row
 * that is tappable itself. Keep at least 8dp free above and below it (every row in the design does).
 *
 * @param checked true when the switch is on. This is the Figma property `On`.
 * @param onCheckedChange called with the new value when the user taps the switch. Pass null when the row around
 * the switch handles the tap and tells screen readers the state itself; the switch is then only a picture.
 * @param modifier layout changes from the caller, applied to the track.
 * @param contentDescription what the switch turns on or off, for screen readers, for example "HDFC Bank".
 * Give it whenever [onCheckedChange] is set, because the switch has no text of its own. It is not used when
 * [onCheckedChange] is null.
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val colors = AppTheme.colors
    // 0 is fully off, 1 is fully on. The knob springs (it may pass the end a little); the colours fade.
    val knob by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = motion(Motion.Spring.Pill.spec()),
        label = "switchKnob",
    )
    val tint by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = motion(Motion.fade(Motion.Duration.Short)),
        label = "switchColour",
    )
    Box(
        modifier = modifier
            .switchTouchArea(checked, onCheckedChange, contentDescription, LocalAppHaptics.current)
            .size(SwitchTrackWidth, SwitchTrackHeight)
            .drawBehind {
                val radius = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(color = lerp(colors.bgSurfaceRaised, colors.brandPrimary, tint), cornerRadius = radius)
                val border = 1f - tint
                if (border > 0f) {
                    // Inside the track, as `Modifier.border` draws it.
                    val w = SwitchOffBorderWidth.toPx()
                    val r = (size.height - w) / 2f
                    drawRoundRect(
                        color = colors.borderSubtle,
                        topLeft = Offset(w / 2f, w / 2f),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(r, r),
                        style = Stroke(w),
                        alpha = border,
                    )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset {
                    val start = SwitchKnobStartOff + (SwitchKnobStartOn - SwitchKnobStartOff) * knob
                    IntOffset(start.roundToPx(), 0)
                }
                .size(SwitchKnobSize)
                .drawBehind { drawCircle(lerp(colors.textSecondary, colors.textOnBrand, tint)) },
        )
    }
}

/**
 * Makes the track tappable in a 48dp high area around it and announces it as a switch. Does nothing when the
 * switch is only a picture.
 */
private fun Modifier.switchTouchArea(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    contentDescription: String?,
    haptics: AppHaptics,
): Modifier {
    if (onCheckedChange == null) return this
    return touchOverhang(horizontal = Space.s0, vertical = SwitchTouchOverhang)
        .toggleable(
            value = checked,
            interactionSource = null,
            indication = null,
            role = Role.Switch,
            onValueChange = { on ->
                haptics.toggle(on)
                onCheckedChange(on)
            },
        )
        .semantics {
            if (contentDescription != null) this.contentDescription = contentDescription
        }
        .padding(vertical = SwitchTouchOverhang)
}
