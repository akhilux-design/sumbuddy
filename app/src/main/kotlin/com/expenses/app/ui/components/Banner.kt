package com.expenses.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/** The fill of a [Banner]. */
enum class BannerTone {
    /** `color/accent/yellow-soft`: something to notice (Home offline, 02-everyday/03; someone joined, 40). */
    Notice,

    /** `color/bg/surface`: a quiet result (Join undone, 02-everyday/41). */
    Plain,
}

/**
 * A banner at the top of a screen's body, as the design draws its "Offline banner", "Joined banner" and "Undone
 * banner" frames (02-everyday/03, 40, 41): radius 16, 12 padding, an Info Row with a 3D icon, and, when given, an
 * action after it 12dp away (40's Secondary Medium "Undo"). Screen readers read the title and the subtitle as one
 * item and announce it when it appears; the action stays a separate button.
 *
 * @param title the first line, for example "You're offline".
 * @param subtitle the explanation, for example "Your own expenses still work. Splits sync when you are back online.".
 * @param icon the 3D icon of the Info Row.
 * @param modifier layout changes from the caller.
 * @param tone the fill.
 * @param action the button at the end, or null for none.
 */
@Composable
fun Banner(
    title: String,
    subtitle: String,
    icon: AppIcon,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Notice,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tone.fill(), RoundedCornerShape(Radius.lg))
            .padding(Space.s12),
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One node that holds the title and the explanation, so the announcement has something to read.
        Box(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            InfoRow(title = title, subtitle = subtitle, icon = icon)
        }
        action?.invoke()
    }
}

@Composable
private fun BannerTone.fill(): Color = when (this) {
    BannerTone.Notice -> AppTheme.colors.accentYellowSoft
    BannerTone.Plain -> AppTheme.colors.bgSurface
}
