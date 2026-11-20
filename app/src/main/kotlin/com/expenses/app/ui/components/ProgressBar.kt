package com.expenses.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.Radius

/** Height of the progress bar. */
private val ProgressHeight = 10.dp

/**
 * The 10dp pill of the design's Progress block (01-onboarding/09-scanning-sms, 02-everyday/138-importing): a
 * `color/bg/surface-raised` track filled with `color/brand/primary` as [done] of [total] are finished. Put the same in
 * numbers in the Caption under it ("20 of 46 sent"), so the progress never shows by colour alone. Screen readers read
 * it as a progress bar.
 *
 * @param done how many are finished.
 * @param total how many there are; 0 draws an empty track.
 * @param modifier outer modifier. The bar fills the width it is given.
 */
@Composable
fun ProgressBar(done: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val shape = RoundedCornerShape(Radius.full)
    // The fill grows with motion/spring/default from where it is; it jumps when the value goes down (a new count).
    val fill = remember { Animatable(fraction) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(fraction) {
        if (reduced || fraction < fill.value) fill.snapTo(fraction) else fill.animateTo(fraction, Motion.Spring.Default.spec(FILL_THRESHOLD))
    }
    val fillColor = AppTheme.colors.brandPrimary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ProgressHeight)
            .clip(shape)
            .background(AppTheme.colors.bgSurfaceRaised)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
            // Read in the draw phase only: the bar is not recomposed while the fill moves.
            .drawBehind {
                val width = size.width * fill.value.coerceIn(0f, 1f)
                if (width > 0f) {
                    val r = minOf(width, size.height) / 2f
                    drawRoundRect(fillColor, size = Size(width, size.height), cornerRadius = CornerRadius(r, r))
                }
            },
    )
}

/** The fill stops within this fraction of its target. */
private const val FILL_THRESHOLD = 0.001f
