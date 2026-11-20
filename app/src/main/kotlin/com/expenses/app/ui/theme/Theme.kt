package com.expenses.app.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.expenses.app.ui.motion.PressIndication

private val LocalAppColors = staticCompositionLocalOf { DarkColors }
private val LocalAppTypography = staticCompositionLocalOf { AppTypography() }

/** Entry point to the design tokens: `AppTheme.colors.bgCanvas`, `AppTheme.type.headingM`. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val type: AppTypography
        @Composable @ReadOnlyComposable get() = LocalAppTypography.current
}

/**
 * Wraps the app (or a preview) in the Dark or Light token set. Follows the phone's setting by default.
 *
 * @param dark the Dark token set; the phone's dark theme by default.
 * @param reducedMotion provided as [LocalReducedMotion]. By default it follows Remove animations (the animator
 * duration scale is 0) live. Previews and captures pass true so nothing is caught mid-motion.
 *
 * Every `clickable` that does not set its own indication gets the flat pressed overlay ([PressIndication],
 * `state/pressed`: `color/text/primary` at 10%) as `LocalIndication`, in place of foundation's raw black debug
 * indication: rows, cards, tiles, chips and icon buttons (`design/spec/motion.md`, "Flat pressed overlay").
 */
@Composable
fun AppTheme(
    dark: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = rememberReducedMotion(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkColors else LightColors
    val pressed = remember(colors) { PressIndication(colors.textPrimary.copy(alpha = Motion.PressedOverlayAlpha)) }
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalAppTypography provides AppTypography(),
        LocalReducedMotion provides reducedMotion,
        LocalIndication provides pressed,
        content = content,
    )
}
