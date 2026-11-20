package com.expenses.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.core.RaisedSurface
import com.expenses.app.ui.motion.PressIndication
import com.expenses.app.ui.theme.AppColors
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space
import com.expenses.app.ui.theme.motion

/** How strongly an [AppButton] stands out. One entry per Figma `Style` variant. */
enum class ButtonStyle {
    /** The main action of a screen: green face on a darker green edge. */
    Primary,

    /** A second action beside or under a primary one: grey face with a thin outline on a grey edge. */
    Secondary,

    /** The quietest action: only a green label, with no face, outline or edge. */
    Ghost,
}

/** How big an [AppButton] is. One entry per Figma `Size` variant. */
enum class ButtonSize {
    /** The standard button: face 52dp high, 24dp of space left and right of the label. */
    Large,

    /** The compact button for rows and cards: face 44dp high, 16dp of space left and right of the label. */
    Medium,
}

/**
 * True inside a container drawn on `color/bg/surface-raised`: Home's payment card and tip, a bottom sheet, a dialog
 * card. A [ButtonStyle.Secondary] face there must not be the container's own colour, or the button reads as
 * disabled, so it takes `color/bg/on-raised`: lighter than the card in both themes. Set it with [OnRaisedSurface].
 */
val LocalOnRaisedSurface = staticCompositionLocalOf { false }

/** Marks [content] as sitting on `color/bg/surface-raised`; see [LocalOnRaisedSurface]. */
@Composable
fun OnRaisedSurface(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOnRaisedSurface provides true, content = content)
}

/**
 * Width of the outline on a [ButtonStyle.Secondary] face. It is drawn inside the face and does not add to its size,
 * so a Secondary face is as big as a Primary one with the same label (design: stroke not included in layout).
 */
private val SecondaryStrokeWidth = 1.5.dp

/** Opacity of a button that cannot be used, as drawn on the "amounts do not add up" split screen. */
private const val DisabledOpacity = 0.4f

/** The label is one line in the design. It may wrap once when it does not fit, so it is never cut off. */
private const val MaxLabelLines = 2

/**
 * The tappable action button with a text label.
 *
 * The button is as wide as its label plus padding. Pass `Modifier.fillMaxWidth()` or a row weight to stretch it;
 * the label stays centred. Every style keeps 5dp free under the face (where Primary and Secondary draw their
 * edge), so buttons of different styles line up. The height comes from the label and its padding and grows with
 * the phone's font size. All three styles are the same size for the same label: the Secondary outline sits inside
 * the face.
 *
 * Pressing a Primary or Secondary button springs the face down onto its edge and back (the 3D press of
 * [RaisedSurface]). A Ghost button has no face, so while it is pressed a `color/brand/soft` pill (the face's shape,
 * radius 16) appears behind its label: in over 50 ms, held until 100 ms after the touch, out over 150 ms; nothing
 * moves and no haptic plays (`components/Button.md`, Pressed). The Medium face is 44dp high; the area that reacts to
 * touch is widened to 48dp around it by Compose. Turning [enabled] on or off fades the opacity over 150 ms.
 *
 * @param pressHaptic whether a press plays `haptic/press`; false for buttons inside the draggable swipe card.
 *
 * @param label text on the button, for example "Send code". Shown on one line; it wraps to a second line
 * only when it does not fit.
 * @param onClick called when the button is tapped.
 * @param modifier layout changes from the caller, applied to the whole button including the space for the edge.
 * @param style Primary, Secondary or Ghost; see [ButtonStyle].
 * @param size Large or Medium; see [ButtonSize].
 * @param enabled false for a button that cannot be used right now: it is drawn faded, ignores taps and is
 * announced as disabled by screen readers.
 */
@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
    pressHaptic: Boolean = true,
) {
    val colors = AppTheme.colors
    // The outline is drawn inside the face (RaisedSurface uses Modifier.border) and adds nothing to the padding.
    val border = if (style == ButtonStyle.Secondary) BorderStroke(SecondaryStrokeWidth, colors.borderSubtle) else null
    val source = remember { MutableInteractionSource() }
    val opacity by animateFloatAsState(
        targetValue = if (enabled) 1f else DisabledOpacity,
        animationSpec = motion(Motion.fade(Motion.Duration.Short)),
        label = "buttonOpacity",
    )
    RaisedSurface(
        faceColor = colors.faceFor(style, onRaised = LocalOnRaisedSurface.current),
        edgeColor = colors.edgeFor(style),
        modifier = modifier.graphicsLayer { alpha = opacity },
        cornerRadius = Radius.lg,
        border = border,
        onClick = onClick,
        enabled = enabled,
        role = Role.Button,
        interactionSource = source,
        pressHaptic = pressHaptic,
    ) {
        if (style == ButtonStyle.Ghost) {
            // `state/pressed-ghost`: the pill sits behind the label, inside the face (the 5dp edge space stays empty).
            val ghostPressed = remember(colors) { PressIndication(colors.brandSoft, RoundedCornerShape(Radius.lg)) }
            Box(Modifier.matchParentSize().indication(source, ghostPressed))
        }
        AppText(
            text = label,
            style = AppTheme.type.button,
            // The label box is the 20dp Button line, so the Medium face is 12 + 20 + 12 = 44dp as in the design.
            modifier = Modifier
                .padding(horizontal = size.horizontalPadding, vertical = size.verticalPadding),
            color = colors.labelFor(style),
            textAlign = TextAlign.Center,
            maxLines = MaxLabelLines,
        )
    }
}

/** Space left and right of the label, inside the face. */
private val ButtonSize.horizontalPadding: Dp
    get() = when (this) {
        ButtonSize.Large -> Space.s24
        ButtonSize.Medium -> Space.s16
    }

/** Space above and below the label, inside the face. */
private val ButtonSize.verticalPadding: Dp
    get() = when (this) {
        ButtonSize.Large -> Space.s16
        ButtonSize.Medium -> Space.s12
    }

/**
 * Fill of the face for each style. Ghost has no face. A Secondary face on a raised container is
 * `color/bg/on-raised`; see [LocalOnRaisedSurface].
 */
internal fun AppColors.faceFor(style: ButtonStyle, onRaised: Boolean): Color = when (style) {
    ButtonStyle.Primary -> brandPrimary
    ButtonStyle.Secondary -> if (onRaised) bgOnRaised else bgSurfaceRaised
    ButtonStyle.Ghost -> Color.Transparent
}

/** Colour of the band under the face for each style, or null for Ghost, which has none. */
private fun AppColors.edgeFor(style: ButtonStyle): Color? = when (style) {
    ButtonStyle.Primary -> brandEdge
    ButtonStyle.Secondary -> edgeNeutral
    ButtonStyle.Ghost -> null
}

/** Colour of the label for each style. */
private fun AppColors.labelFor(style: ButtonStyle): Color = when (style) {
    ButtonStyle.Primary -> textOnBrand
    ButtonStyle.Secondary -> textPrimary
    ButtonStyle.Ghost -> textBrand
}
