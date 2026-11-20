package com.expenses.app.ui.screens.family

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import com.expenses.app.R
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.StatTile
import com.expenses.app.ui.components.StatTilePair
import com.expenses.app.ui.components.TopBar
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.motion.AnimatedItem
import com.expenses.app.ui.screens.activity.SectionExtraGap
import com.expenses.app.ui.screens.activity.label
import com.expenses.app.ui.screens.month.CategoryRow
import com.expenses.app.ui.screens.month.MonthButtons
import com.expenses.app.ui.screens.month.monthMove
import com.expenses.app.ui.screens.month.noMonthMove
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.Space
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Month view (Family) (design 06-family/27): "Home · November 2026", "Family spent" next to "Last month", the month
 * buttons, By person, By category with each one's share, then every family expense of the month by day (Kind=Shared,
 * Meta "Family"). The person's own rows open Expense detail; the partner's are not tappable. A month with nothing has
 * the "Nothing in the family month yet" card. Months move as Month view's do.
 *
 * @param state what to show.
 * @param onEvent called with what the person tapped.
 * @param modifier outer modifier. The screen fills the space it is given.
 */
@Composable
fun FamilyMonthScreen(state: FamilyMonthUiState, modifier: Modifier = Modifier, onEvent: (FamilyMonthEvent) -> Unit = {}) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.bgCanvas)
            .safeDrawingPadding(),
    ) {
        TopBar(
            title = stringResource(R.string.family_month_title, state.familyName, monthTitle(state.month)),
            onBack = { onEvent(FamilyMonthEvent.Back) },
        )
        val reduced = LocalReducedMotion.current
        val axis = with(LocalDensity.current) { Motion.AxisDistance.roundToPx() }
        AnimatedContent(
            targetState = state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { if (reduced) noMonthMove() else monthMove(forward = targetState.month > initialState.month, axis) },
            contentKey = { it.month },
            label = "familyMonth",
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
                monthItems(shown, onEvent)
            }
        }
    }
}

/** The month's parts: 12dp between the parts and 8dp between the rows of one part, as 27 draws them. */
private fun LazyListScope.monthItems(state: FamilyMonthUiState, onEvent: (FamilyMonthEvent) -> Unit) {
    item(key = "stats") {
        AnimatedItem {
            val spent = stringResource(R.string.family_month_spent)
            val last = stringResource(R.string.family_month_last)
            StatTilePair(
                first = { StatTile(label = spent, value = formatRupees(state.totalMinor)) },
                second = { StatTile(label = last, value = formatRupees(state.lastMonthMinor)) },
                modifier = Modifier.padding(bottom = SectionExtraGap),
            )
        }
    }
    item(key = "months") {
        AnimatedItem {
            MonthButtons(
                canGoNext = state.canGoNext,
                onPrevious = { onEvent(FamilyMonthEvent.PreviousMonth) },
                onNext = { onEvent(FamilyMonthEvent.NextMonth) },
            )
        }
    }
    item(key = "by-person") { AnimatedItem { Label(stringResource(R.string.home_family_by_person)) } }
    item(key = "people") { AnimatedItem { FamilyPeople(state.people) } }
    if (state.categories.isNotEmpty()) {
        item(key = "by-category") { AnimatedItem { Label(stringResource(R.string.month_by_category)) } }
        items(state.categories, key = { "category-${it.category}" }) { category -> AnimatedItem { CategoryRow(category) } }
    }
    if (state.days.isEmpty()) {
        item(key = "empty") { AnimatedItem { FamilyEmptyCard(modifier = Modifier.padding(top = SectionExtraGap)) } }
    }
    state.days.forEach { day ->
        item(key = "day-${day.date}") { AnimatedItem { Label(day.date.label()) } }
        items(day.rows, key = { "row-${it.groupExpenseId}" }) { row ->
            AnimatedItem { FamilyExpenseRow(row = row, onOpen = { onEvent(FamilyMonthEvent.RowTapped(it)) }) }
        }
    }
}

/** A section label with the extra space that makes 12dp around it in the 8dp list. */
@Composable
private fun Label(text: String) {
    SectionLabel(text = text, modifier = Modifier.padding(vertical = SectionExtraGap))
}

/** The month as the title writes it, for example "November 2026", in the phone's language. */
@Composable
private fun monthTitle(month: YearMonth): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    return month.format(formatter)
}
