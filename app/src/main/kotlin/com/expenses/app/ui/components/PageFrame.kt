package com.expenses.app.ui.components

import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.unit.Dp
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/**
 * The frame of a full screen that is not a tab, as the design draws its Body and Footer frames (for example
 * 02-everyday/24-group-page, 03-getting-paid-back/00-settle-up, 04-you/02-account): the Top Bar, a body that scrolls
 * (16dp at the sides, 8dp above and below, 12dp between the parts) and, when given, a footer pinned to the bottom
 * (16dp above, 16dp at the sides, 8dp below, 12dp between buttons). It keeps clear of the system bars and of the
 * keyboard, so the footer rises above the keyboard. The same frame as Payment's and You's own pages, shared for the
 * group, split, settle-up, join and account screens.
 *
 * @param title the screen's title in the top bar; null draws no top bar (a centred result screen such as Split.
 * Sorted.).
 * @param onBack called by the back button; null hides it.
 * @param modifier outer modifier. The frame fills the space it is given.
 * @param actionLabel text of the top bar's action ("Add" on the Group page), or null for none.
 * @param onAction called when the action is tapped, or null for none.
 * @param footer the buttons at the bottom, or null for no footer.
 * @param bodyTopPadding the space above the body's first part: 8dp on almost every screen, 0 where the design's Body
 * frame has none (Split it, 02-everyday/09 to 11).
 * @param scrollState the body's scroll position, for a screen that brings something back into view (the Group
 * page shows a result banner at the top of the body).
 * @param body the content of the scrolling body, top to bottom.
 */
@Composable
fun PageFrame(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    bodyTopPadding: Dp = Space.s8,
    scrollState: ScrollState = rememberScrollState(),
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.bgCanvas)
            .safeDrawingPadding(),
    ) {
        if (title != null) {
            TopBar(title = title, onBack = onBack, actionLabel = actionLabel, onAction = onAction)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(start = Space.s16, top = bodyTopPadding, end = Space.s16, bottom = Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s12),
            content = body,
        )
        if (footer != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colors.bgCanvas)
                    .padding(start = Space.s16, top = Space.s16, end = Space.s16, bottom = Space.s8),
                verticalArrangement = Arrangement.spacedBy(Space.s12),
                content = footer,
            )
        }
    }
}
