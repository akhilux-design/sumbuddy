package com.expenses.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.PlaceholderRow
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.Tag
import com.expenses.app.ui.components.TagTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.motion.AnimatedItem
import com.expenses.app.ui.screens.family.FamilyEmptyCard
import com.expenses.app.ui.screens.family.FamilyExpenseRow
import com.expenses.app.ui.screens.family.FamilyPeople
import com.expenses.app.ui.screens.family.FamilyPeoplePlaceholder
import com.expenses.app.ui.screens.family.FamilyPlaceholderRows
import com.expenses.app.ui.screens.family.FamilyTilesPlaceholder
import com.expenses.app.ui.screens.family.FamilyTopCategories
import com.expenses.app.ui.screens.family.familyExpenseCount
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

// Home's Family side (docs/family.md 1.5): the Family card and the family month's sections.

/**
 * The family month under the spent card (06-family/02 to 07): By person, Top categories (hidden at none) and Latest,
 * whose rows are the family's expenses (the person's own ones open Expense detail), or the empty card. While the month
 * loads for the first time, the placeholder shapes of 06-family/06 stand in under the same labels.
 */
internal fun LazyListScope.familyMonthItems(content: HomeContent.FamilyMonth, onEvent: (HomeEvent) -> Unit) {
    item(key = "family-by-person") { AnimatedItem { SectionLabel(text = stringResource(R.string.home_family_by_person)) } }
    if (content.loading) {
        item(key = "family-people-loading") { AnimatedItem { FamilyPeoplePlaceholder() } }
        item(key = "family-top-label") { AnimatedItem { SectionLabel(text = stringResource(R.string.home_family_top_categories)) } }
        item(key = "family-top-loading") { AnimatedItem { FamilyTilesPlaceholder() } }
        item(key = "family-latest-label") { AnimatedItem { SectionLabel(text = stringResource(R.string.home_latest_title)) } }
        items(FamilyPlaceholderRows, key = { "family-latest-loading-$it" }) { AnimatedItem { PlaceholderRow() } }
        return
    }
    item(key = "family-people") { AnimatedItem { FamilyPeople(content.people) } }
    if (content.topCategories.isNotEmpty()) {
        item(key = "family-top-label") { AnimatedItem { SectionLabel(text = stringResource(R.string.home_family_top_categories)) } }
        item(key = "family-top") { AnimatedItem { FamilyTopCategories(content.topCategories) } }
    }
    item(key = "family-latest-label") { AnimatedItem { SectionLabel(text = stringResource(R.string.home_latest_title)) } }
    if (content.latest.isEmpty()) {
        item(key = "family-empty") { AnimatedItem { FamilyEmptyCard() } }
    } else {
        items(content.latest, key = { "family-row-${it.groupExpenseId}" }) { row ->
            AnimatedItem { FamilyExpenseRow(row = row, onOpen = { onEvent(HomeEvent.FamilyRowTapped(it)) }) }
        }
    }
}

/**
 * The Family card under the spent card, on the frame of the tip card (a raised card with a yellow outline): a Tag
 * that says what it is about ("Family", or "Invite sent" in yellow), a title, a line and one Primary Medium button.
 * The Tag carries words, so the state is never told by colour alone.
 *
 * @param card which card.
 * @param onEvent receives "Set up Family", "Send link again" or "Invite partner".
 */
@Composable
internal fun FamilyCardView(card: FamilyCard, onEvent: (HomeEvent) -> Unit) {
    val (tag, tone) = when (card) {
        FamilyCard.Waiting -> stringResource(R.string.home_family_waiting_tag) to TagTone.Yellow
        FamilyCard.SetUp, is FamilyCard.Invite -> stringResource(R.string.home_family_card_tag) to TagTone.Brand
    }
    val title = when (card) {
        FamilyCard.SetUp -> stringResource(R.string.home_family_setup_title)
        FamilyCard.Waiting -> stringResource(R.string.home_family_waiting_title)
        is FamilyCard.Invite -> stringResource(R.string.home_family_alone_title)
    }
    val body = when (card) {
        FamilyCard.SetUp -> stringResource(R.string.home_family_setup_body)
        FamilyCard.Waiting -> stringResource(R.string.home_family_waiting_body)
        is FamilyCard.Invite -> stringResource(R.string.home_family_alone_body, card.familyName)
    }
    val (button, event) = when (card) {
        FamilyCard.SetUp -> stringResource(R.string.home_family_setup_button) to HomeEvent.SetUpFamily
        FamilyCard.Waiting -> stringResource(R.string.home_family_waiting_button) to HomeEvent.SendFamilyLink
        is FamilyCard.Invite -> stringResource(R.string.home_family_alone_button) to HomeEvent.SendFamilyLink
    }
    HighlightCard {
        Tag(label = tag, tone = tone)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            AppText(text = title, style = AppTheme.type.headingM, modifier = Modifier.fillMaxWidth(), color = AppTheme.colors.textPrimary)
            AppText(text = body, style = AppTheme.type.bodyM, modifier = Modifier.fillMaxWidth(), color = AppTheme.colors.textSecondary)
        }
        AppButton(
            label = button,
            onClick = { onEvent(event) },
            modifier = Modifier.fillMaxWidth(),
            style = ButtonStyle.Primary,
            size = ButtonSize.Medium,
        )
    }
}

/** The spent card's label on the Family side: "Family spent in November" with a family, else "Spent in November". */
@Composable
internal fun familySpentLabel(caption: FamilyCaption, month: String): String = when (caption) {
    FamilyCaption.Local -> stringResource(R.string.home_spent_label, month)
    is FamilyCaption.Alone, is FamilyCaption.Together -> stringResource(R.string.home_family_spent_label, month)
}

/** The spent card's caption on the Family side: "your Family expenses on this phone", "Just you · 12 expenses", "Arjun and Meera · 64 expenses". */
@Composable
internal fun familyCaptionText(caption: FamilyCaption): String = when (caption) {
    FamilyCaption.Local -> stringResource(R.string.home_spent_caption_family)
    is FamilyCaption.Alone -> stringResource(R.string.home_family_caption_alone, familyExpenseCount(caption.count))
    is FamilyCaption.Together -> stringResource(R.string.home_family_caption_two, caption.me, caption.partner, familyExpenseCount(caption.count))
}
