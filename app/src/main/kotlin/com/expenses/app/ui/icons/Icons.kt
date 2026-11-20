package com.expenses.app.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.theme.AppTheme

/**
 * The coloured 3D drawing of [icon]. Used in rows (30dp inside a 44dp bubble), tiles (32dp) and empty states (40dp).
 * It has fixed colours and is never tinted.
 *
 * @param contentDescription what the icon means for screen readers, or null when the text next to it says it.
 */
@Composable
fun ArtIcon(icon: AppIcon, modifier: Modifier = Modifier, size: Dp = 30.dp, contentDescription: String? = null) {
    Image(painter = painterResource(icon.art), contentDescription = contentDescription, modifier = modifier.size(size))
}

/** The outline drawing of [icon] in one colour. Used in the bottom bar and top bars. */
@Composable
fun LineIcon(
    icon: AppIcon,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = AppTheme.colors.textPrimary,
    contentDescription: String? = null,
) {
    TintedIcon(icon.line, modifier, size, tint, contentDescription)
}

/** Any single-colour vector drawable (for example `R.drawable.ic_nav_home`, `R.drawable.ic_back`) in [tint]. */
@Composable
fun TintedIcon(
    @DrawableRes id: Int,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = AppTheme.colors.textPrimary,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(id),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}
