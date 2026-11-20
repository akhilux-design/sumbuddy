package com.expenses.app.ui.screens.month

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.motion.AnimatedItem
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.ExpenseRow
import com.expenses.app.ui.components.ExpenseRowKind
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.StatTile
import com.expenses.app.ui.components.StatTilePair
import com.expenses.app.ui.components.TopBar
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.screens.activity.SectionExtraGap
import com.expenses.app.ui.screens.activity.activityDays
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Month view (design 02-everyday/18-month-view): the month as the title, what was spent next to last month with the
 * buttons to the month before and after right under it (the design's "Change"), the spending per category with each
 * one's share, then the month's payments by day. Draws the whole screen, keeping clear of the system bars, and
 * scrolls.
 *
 * @param state what to show; see [MonthUiState].
 * @param modifier outer modifier. The screen fills the space it is given.
 * @param onEvent called with what the person tapped; see [MonthEvent].
 */
@Composable
fun MonthScreen(
    state: MonthUiState,
    modifier: Modifier = Modifier,
    onEvent: (MonthEvent) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.bgCanvas)
            .safeDrawingPadding(),
    ) {
        TopBar(title = monthTitle(state.month), onBack = { onEvent(MonthEvent.Back) })
        // Previous and Next month: the new month slides in 30 dp from the side it comes from as it fades in, the old one
        // slides away the other way as it fades out (motion/spring/screen, fade-through).
        val reduced = LocalReducedMotion.current
        val axis = with(LocalDensity.current) { Motion.AxisDistance.roundToPx() }
        AnimatedContent(
            targetState = state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { if (reduced) noMonthMove() else monthMove(forward = targetState.month > initialState.month, axis) },
            contentKey = { it.month },
            label = "month",
        ) { shown ->
            // The month leaving is hidden from screen readers, so they read only the new one.
            val leaving = transition.targetState != EnterExitState.Visible
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (leaving) Modifier.semantics { hideFromAccessibility() } else Modifier),
                contentPadding = PaddingValues(horizontal = Space.s16, vertical = Space.s8),
                verticalArrangement = Arrangement.spacedBy(Space.s8),
            ) {
                item(key = "stats") { AnimatedItem { MonthStats(shown) } }
                // Right under the totals, so another month is reachable without scrolling past every day.
                item(key = "months") {
                    AnimatedItem {
                        MonthButtons(
                            canGoNext = shown.canGoNext,
                            onPrevious = { onEvent(MonthEvent.PreviousMonth) },
                            onNext = { onEvent(MonthEvent.NextMonth) },
                        )
                    }
                }
                if (shown.categories.isEmpty() && shown.days.isEmpty()) {
                    item(key = "empty") {
                        AnimatedItem {
                            EmptyState(
                                icon = AppIcon.Chart,
                                title = stringResource(R.string.month_empty_title),
                                body = stringResource(R.string.month_empty_body),
                                modifier = Modifier.padding(top = SectionExtraGap),
                            )
                        }
                    }
                }
                if (shown.categories.isNotEmpty()) {
                    item(key = "by-category") {
                        AnimatedItem {
                            SectionLabel(
                                text = stringResource(R.string.month_by_category),
                                modifier = Modifier.padding(vertical = SectionExtraGap),
                            )
                        }
                    }
                    items(shown.categories, key = { "category-${it.category}" }) { category ->
                        AnimatedItem {
                            CategoryRow(category)
                        }
                    }
                }
                activityDays(days = shown.days, onItemClick = { onEvent(MonthEvent.ItemClicked(it)) })
            }
        }
    }
}

/** The month as the title writes it, for example "November 2026", in the phone's language. */
@Composable
private fun monthTitle(month: YearMonth): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    return month.format(formatter)
}

/** The Spent and Last month tiles side by side. */
@Composable
private fun MonthStats(state: MonthUiState) {
    val spentLabel = stringResource(R.string.month_spent)
    val lastMonthLabel = stringResource(R.string.month_last_month)
    StatTilePair(
        first = { StatTile(label = spentLabel, value = formatRupees(state.spentMinor)) },
        second = { StatTile(label = lastMonthLabel, value = formatRupees(state.lastMonthMinor)) },
    )
}

/** One category: its icon and name, how many expenses, the total and its share of the month. Also Month view (Family)'s. */
@Composable
internal fun CategoryRow(category: MonthCategory) {
    ExpenseRow(
        title = stringResource(category.category.label),
        subtitle = pluralStringResource(R.plurals.month_category_count, category.count, category.count),
        amount = formatRupees(category.totalMinor),
        meta = stringResource(R.string.month_category_percent, category.percent),
        icon = category.category.icon,
        kind = ExpenseRowKind.Personal,
    )
}

/** "Previous month" and, before the current month, "Next month", in equal widths. Also Month view (Family)'s. */
@Composable
internal fun MonthButtons(canGoNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        AppButton(
            label = stringResource(R.string.month_previous),
            onClick = onPrevious,
            modifier = Modifier.weight(1f),
            style = ButtonStyle.Secondary,
            size = ButtonSize.Medium,
        )
        AppButton(
            label = stringResource(R.string.month_next),
            onClick = onNext,
            modifier = Modifier.weight(1f),
            style = ButtonStyle.Secondary,
            size = ButtonSize.Medium,
            enabled = canGoNext,
        )
    }
}

/** No move between months (Remove animations). Also Month view (Family)'s. */
internal fun noMonthMove(): ContentTransform = ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)

/**
 * The move to the next month ([forward]) or the previous one, by [axisPx] (30 dp); Start and End flip in RTL. Also Month
 * view (Family)'s.
 */
internal fun <S> AnimatedContentTransitionScope<S>.monthMove(forward: Boolean, axisPx: Int): ContentTransform {
    val towards = if (forward) SlideDirection.Start else SlideDirection.End
    val slide = Motion.Spring.Screen.spec(IntOffset.VisibilityThreshold)
    val offset = { full: Int -> if (full < 0) -axisPx else axisPx }
    return ContentTransform(
        targetContentEnter = slideIntoContainer(towards, slide, offset) +
            fadeIn(Motion.fade(Motion.FadeThrough.InMillis, delayMillis = Motion.FadeThrough.InDelayMillis)),
        initialContentExit = slideOutOfContainer(towards, slide, offset) + fadeOut(Motion.fade(Motion.FadeThrough.OutMillis)),
        sizeTransform = null,
    )
}
