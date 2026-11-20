package com.expenses.app.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.icons.ArtIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius

/**
 * The rounded square that holds a 3D icon at the start of a row or card: 44dp, radius 12,
 * `color/bg/surface-raised`, with the icon at 30dp. Decorative: the row's text names the item.
 */
@Composable
fun IconBubble(
    icon: AppIcon,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 30.dp,
    background: Color = AppTheme.colors.bgSurfaceRaised,
) {
    Box(
        modifier = modifier.size(size).background(background, RoundedCornerShape(Radius.md)),
        contentAlignment = Alignment.Center,
    ) {
        ArtIcon(icon = icon, size = iconSize)
    }
}
