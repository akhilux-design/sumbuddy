package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.screens.activity.ActivitySamples
import com.expenses.app.ui.screens.activity.ActivityScreen
import com.expenses.app.ui.screens.activity.ActivityUiState
import com.expenses.app.ui.screens.month.MonthSamples
import com.expenses.app.ui.screens.month.MonthScreen
import com.expenses.app.ui.shell.MainScaffold

/**
 * The Activity tab in each design state, inside the main frame with the bottom navigation:
 * "screen-activity" is design/spec/screens/02-everyday/16-activity and "screen-activity-empty" is 17-activity-empty.
 * "groups-home-activity-splits" is the Splits filter and "groups-home-activity-money-in" money in that names nobody.
 * Month view, opened from Activity, is "screen-month" (18-month-view) and "screen-month-empty".
 */
val activityScreenPreviews: List<PreviewEntry> = listOf(
    activityPreview(name = "screen-activity", state = ActivitySamples.default),
    activityPreview(name = "screen-activity-empty", state = ActivitySamples.empty),
    activityPreview(name = "groups-home-activity-splits", state = ActivitySamples.splits),
    activityPreview(name = "groups-home-activity-money-in", state = ActivitySamples.moneyIn),
    PreviewEntry(name = "screen-month", fullScreen = true) { MonthScreen(state = MonthSamples.default) },
    PreviewEntry(name = "screen-month-empty", fullScreen = true) { MonthScreen(state = MonthSamples.empty) },
)

private fun activityPreview(name: String, state: ActivityUiState): PreviewEntry =
    PreviewEntry(name = name, fullScreen = true) {
        MainScaffold(active = MainTab.Activity, onSelect = {}) { padding ->
            ActivityScreen(state = state, contentPadding = padding)
        }
    }
