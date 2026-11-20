package com.expenses.app.ui.screens.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.groups.data.FamilyPersonTotal
import com.expenses.app.groups.data.FamilyRow
import com.expenses.app.ui.components.BalanceRow
import com.expenses.app.ui.components.BalanceRowDirection
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.ExpenseRow
import com.expenses.app.ui.components.ExpenseRowKind
import com.expenses.app.ui.components.PlaceholderRow
import com.expenses.app.ui.components.PlaceholderTile
import com.expenses.app.ui.components.StatTile
import com.expenses.app.ui.components.StatTilePair
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space

// The parts of the family month shared by Home's Family side (06-family/02 to 07) and Month view (Family) (27).

/** Space between the rows of By person (the design's People frame, `space/8`). */
private val PeopleGap = Space.s8

/** How many placeholder rows By person and Latest draw while the family month loads (06-family/06). */
const val FamilyPlaceholderRows: Int = 2

/**
 * By person: one Balance Row (Direction=Plain) per person of the family, the person first, 8dp apart: "Arjun (you)" /
 * "38 expenses" / "₹24,100", then "Meera" / "26 expenses" / "₹18,200". Nobody owes anybody, so the amounts are in the
 * normal text colour.
 *
 * @param people the person first, then the partner on the app.
 */
@Composable
fun FamilyPeople(people: List<FamilyPersonTotal>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PeopleGap)) {
        people.forEach { person -> FamilyPersonRow(person) }
    }
}

/** One person's part of the family month. */
@Composable
private fun FamilyPersonRow(person: FamilyPersonTotal) {
    val member = person.member
    BalanceRow(
        name = if (person.isMe) stringResource(R.string.home_family_you, member.shortName) else member.shortName,
        detail = familyExpenseCount(person.count),
        amount = formatRupees(person.totalMinor),
        initial = member.shortName.take(1).uppercase(),
        direction = BalanceRowDirection.Plain,
        avatarColor = member.avatar,
    )
}

/** "12 expenses", "1 expense". */
@Composable
fun familyExpenseCount(count: Int): String = pluralStringResource(R.plurals.home_family_expenses, count, count)

/**
 * Top categories: up to two Stat Tiles (Tone=Neutral), largest first ("Rent ₹24,000", "Groceries ₹9,840"), in equal
 * widths 12dp apart; one category gives one tile at the same width. Call it only with at least one category: the
 * section is hidden at none.
 *
 * @param categories the largest categories, largest first; only the first two are drawn.
 */
@Composable
fun FamilyTopCategories(categories: List<CategoryTotal>, modifier: Modifier = Modifier) {
    val first = categories.getOrNull(0) ?: return
    val second = categories.getOrNull(1)
    val firstLabel = stringResource(first.category.label)
    val secondLabel = second?.let { stringResource(it.category.label) }
    StatTilePair(
        first = { StatTile(label = firstLabel, value = formatRupees(first.totalMinor)) },
        second = { if (second != null && secondLabel != null) StatTile(label = secondLabel, value = formatRupees(second.totalMinor)) },
        modifier = modifier,
    )
}

/**
 * One family expense as an Expense Row (Kind=Shared, Meta "Family"): "BigBasket" / "Groceries · Meera paid" /
 * "₹2,340", or "Swiggy" / "Food · You paid" / "₹450". The person's own rows open Expense detail of their payment; the
 * partner's are not tappable (decision 15).
 *
 * @param row the family expense.
 * @param onOpen called with the person's own payment when one of their rows is tapped.
 */
@Composable
fun FamilyExpenseRow(row: FamilyRow, onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    val category = stringResource(row.category.label)
    val personal = row.personalExpenseId
    ExpenseRow(
        title = row.title,
        subtitle = if (row.paidByMe) {
            stringResource(R.string.home_family_row_you_paid, category)
        } else {
            stringResource(R.string.home_family_row_paid, category, row.paidByName)
        },
        amount = formatRupees(row.amountMinor),
        meta = stringResource(R.string.home_family_row_meta),
        modifier = modifier,
        icon = row.category.icon,
        kind = ExpenseRowKind.Shared,
        onClick = if (row.paidByMe && personal != null) ({ onOpen(personal) }) else null,
    )
}

/**
 * The empty card where Latest would be when the month has no family expense (06-family/05): Icon/users, "Nothing in
 * the family month yet", "When you add an expense, choose Family under Count it in." No button.
 */
@Composable
fun FamilyEmptyCard(modifier: Modifier = Modifier) {
    EmptyState(
        icon = AppIcon.Users,
        title = stringResource(R.string.home_family_empty_title),
        body = stringResource(R.string.home_family_empty_body),
        modifier = modifier,
    )
}

/** By person while the family month loads: two placeholder rows, 8dp apart (06-family/06). */
@Composable
fun FamilyPeoplePlaceholder(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PeopleGap)) {
        repeat(FamilyPlaceholderRows) { PlaceholderRow() }
    }
}

/** Top categories while the family month loads: two placeholder tiles (06-family/06). */
@Composable
fun FamilyTilesPlaceholder(modifier: Modifier = Modifier) {
    StatTilePair(first = { PlaceholderTile() }, second = { PlaceholderTile() }, modifier = modifier)
}
