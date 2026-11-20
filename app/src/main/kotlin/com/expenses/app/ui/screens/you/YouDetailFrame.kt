package com.expenses.app.ui.screens.you

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.expenses.app.ui.components.TopBar
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/**
 * The frame of the screens opened from You, as designs 04-you/01, 04 and 05 draw it: the top bar with a back
 * button, a body that scrolls (8dp top and bottom, 16dp at the sides, 12dp between parts) and, when given, a footer
 * that stays at the bottom (16dp top, 16dp at the sides, 8dp bottom). It keeps clear of the system bars and the
 * keyboard.
 *
 * @param title the screen's title.
 * @param onBack called by the back button.
 * @param modifier outer modifier. The frame fills the space it is given.
 * @param footer what stays under the body, usually one full-width button; null for none.
 * @param body the parts of the body, top to bottom.
 */
@Composable
internal fun YouDetailFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.bgCanvas)
            .safeDrawingPadding(),
    ) {
        TopBar(title = title, onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.s16, vertical = Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s12),
            content = body,
        )
        if (footer != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Space.s16, top = Space.s16, end = Space.s16, bottom = Space.s8),
                verticalArrangement = Arrangement.spacedBy(Space.s12),
                content = footer,
            )
        }
    }
}
