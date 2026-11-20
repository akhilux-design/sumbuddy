package com.expenses.app.ui.screens.activity

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.expenses.app.R
import com.expenses.app.ui.motion.AnimatedItem
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppChip
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.StatTile
import com.expenses.app.ui.components.StatTilePair
import com.expenses.app.ui.components.TabHeader
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space
import java.time.format.DateTimeFormatter

/**
 * The Activity tab: the title with an add button, what was spent this month, and either the payments grouped by
 * day under a row of filters or, before anything is added, an empty state. Matches
 * design/spec/screens/02-everyday/16-activity.json and 17-activity-empty.json. It draws only what lies between
 * the status bar and the bottom navigation, and the whole body scrolls.
 *
 * @param state what to show; see [ActivityUiState].
 * @param modifier outer modifier. The screen fills the space it is given.
 * @param contentPadding space to keep clear, from the shell: the top is kept above the title, the bottom is added
 * at the end of the list so the last row can scroll out from under the bottom navigation.
 * @param onEvent called with what the user tapped; see [ActivityEvent].
 */
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onEvent: (ActivityEvent) -> Unit = {},
) {
    val filterScroll = rememberScrollState()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Space.s16,
            top = contentPadding.calculateTopPadding() + Space.s8,
            end = Space.s16,
            bottom = contentPadding.calculateBottomPadding() + Space.s8,
        ),
        verticalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        item(key = "top") {
            AnimatedItem {
                ActivityTop(
                    state = state,
                    filterScroll = filterScroll,
                    onEvent = onEvent,
                )
            }
        }
        when (val content = state.content) {
            ActivityContent.Empty -> item(key = "empty") {
                AnimatedItem {
                    EmptyState(
                        icon = AppIcon.Receipt,
                        title = stringResource(R.string.activity_empty_title),
                        body = stringResource(R.string.activity_empty_body),
                        modifier = Modifier.padding(top = SectionExtraGap),
                        action = {
                            AppButton(
                                label = stringResource(R.string.activity_empty_action),
                                onClick = { onEvent(ActivityEvent.AddByHandClicked) },
                                style = ButtonStyle.Secondary,
                                size = ButtonSize.Medium,
                            )
                        },
                    )
                }
            }

            is ActivityContent.Days -> if (content.days.isEmpty()) {
                item(key = "filter-empty") {
                    AnimatedItem {
                        EmptyState(
                            icon = AppIcon.Search,
                            title = stringResource(R.string.activity_filter_empty_title),
                            body = stringResource(R.string.activity_filter_empty_body),
                            modifier = Modifier.padding(top = SectionExtraGap),
                        )
                    }
                }
            } else {
                activityDays(days = content.days, onItemClick = { onEvent(ActivityEvent.ItemClicked(it)) })
            }
        }
    }
}

/** The part above the list: header, month numbers and, when there is a list, the filters. 12dp apart. */
@Composable
private fun ActivityTop(
    state: ActivityUiState,
    filterScroll: ScrollState,
    onEvent: (ActivityEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        TabHeader(
            title = stringResource(R.string.activity_title),
            actionLabel = stringResource(R.string.activity_add),
            onAction = { onEvent(ActivityEvent.AddClicked) },
        )
        ActivityStats(state = state, onClick = { onEvent(ActivityEvent.MonthClicked) })
        val content = state.content
        if (content is ActivityContent.Days) {
            ActivityFilters(
                selected = content.selectedFilter,
                scrollState = filterScroll,
                onSelect = { onEvent(ActivityEvent.FilterSelected(it)) },
            )
        }
    }
}

/**
 * The two month tiles side by side, 12dp apart, kept the same height when one of them wraps. Each opens Month view.
 */
@Composable
private fun ActivityStats(state: ActivityUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("MMM", locale) }
    val spentLabel = stringResource(R.string.activity_stat_spent, state.month.format(formatter))
    val splitsLabel = stringResource(R.string.activity_stat_from_splits)
    val openLabel = stringResource(R.string.activity_open_month)
    val tileModifier = Modifier
        .clip(RoundedCornerShape(Radius.lg))
        .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
    StatTilePair(
        modifier = modifier,
        first = { StatTile(label = spentLabel, value = formatRupees(state.spentPaise), modifier = tileModifier) },
        second = {
            StatTile(label = splitsLabel, value = formatRupees(state.fromSplitsPaise), modifier = tileModifier)
        },
    )
}

/**
 * The filter chips, 4dp apart. The design clips the last chip at the edge of the content; here the row scrolls
 * sideways so every filter can be reached.
 */
@Composable
private fun ActivityFilters(
    selected: ActivityFilter,
    scrollState: ScrollState,
    onSelect: (ActivityFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        ActivityFilter.entries.forEach { filter ->
            AppChip(
                label = filter.label(),
                selected = filter == selected,
                onClick = { onSelect(filter) },
                role = Role.Tab,
            )
        }
    }
}

@Composable
private fun ActivityFilter.label(): String = stringResource(
    when (this) {
        ActivityFilter.All -> R.string.activity_filter_all
        ActivityFilter.Me -> R.string.activity_filter_me
        ActivityFilter.Family -> R.string.activity_filter_family
        ActivityFilter.Splits -> R.string.activity_filter_splits
        ActivityFilter.Ignored -> R.string.activity_filter_ignored
    },
)
