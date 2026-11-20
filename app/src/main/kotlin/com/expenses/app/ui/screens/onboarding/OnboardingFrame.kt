package com.expenses.app.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/**
 * The layout every onboarding screen shares: the canvas colour behind everything, an optional top bar, a body that
 * scrolls when it does not fit, and a footer with the buttons that stays above the system navigation bar and the
 * keyboard. The design's 46dp status bar and 24dp gesture bar are the phone's real system bars here.
 *
 * @param modifier layout changes from the caller, applied to the whole screen.
 * @param topBar drawn above the body, or null for none.
 * @param bodyPadding space around the body's content (design `Body` padding).
 * @param bodyGap space between the parts of the body (design `Body` gap).
 * @param centerBody true centres the body's content vertically when it is shorter than the space, as on Welcome.
 * @param footer the buttons at the bottom, 12dp apart, or null for a screen without buttons.
 * @param body the content of the screen, top to bottom.
 */
@Composable
internal fun OnboardingFrame(
    modifier: Modifier = Modifier,
    topBar: @Composable (() -> Unit)? = null,
    bodyPadding: PaddingValues = PaddingValues(horizontal = Space.s16, vertical = Space.s8),
    bodyGap: Dp = Space.s12,
    centerBody: Boolean = false,
    footer: @Composable (ColumnScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.bgCanvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        topBar?.invoke()
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(bodyPadding),
                verticalArrangement = if (centerBody) {
                    Arrangement.spacedBy(bodyGap, Alignment.CenterVertically)
                } else {
                    Arrangement.spacedBy(bodyGap)
                },
                content = body,
            )
        }
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

/**
 * The large headline of an onboarding screen in `Display/L`, announced as a heading.
 *
 * @param text the headline.
 * @param modifier layout changes from the caller.
 */
@Composable
internal fun OnboardingHeadline(text: String, modifier: Modifier = Modifier) {
    AppText(
        text = text,
        style = AppTheme.type.displayL,
        modifier = modifier.fillMaxWidth().semantics { heading() },
        color = AppTheme.colors.textPrimary,
    )
}

/**
 * The explanation under a headline in `Body/M`, secondary colour.
 *
 * @param text the explanation.
 * @param modifier layout changes from the caller.
 */
@Composable
internal fun OnboardingBody(text: String, modifier: Modifier = Modifier) {
    AppText(
        text = text,
        style = AppTheme.type.bodyM,
        modifier = modifier.fillMaxWidth(),
        color = AppTheme.colors.textSecondary,
    )
}
