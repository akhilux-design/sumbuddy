package com.expenses.app.ui.screens.payment

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.expenses.app.R
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppChip
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.components.SurfaceCardStyle
import com.expenses.app.ui.components.Tag
import com.expenses.app.ui.components.TagTone
import com.expenses.app.ui.components.TopBar
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

// Layout pieces the payment screens share. They are made of the shared components and add no look of their own.

/** How many categories Add to expenses shows as chips before "More", as in the design. */
private const val QuickCategoryCount = 3

/**
 * The frame of a payment screen, as in the design: the top bar, a body that scrolls (16dp at the sides, 8dp above
 * and below, 12dp between the parts) and a footer pinned to the bottom (16dp above, 8dp below, 12dp between
 * buttons). The frame keeps clear of the system bars and of the keyboard, so the footer rises above the keyboard.
 *
 * @param title the screen's title in the top bar.
 * @param onBack called by the back button; null hides it.
 * @param modifier outer modifier. The frame fills the space it is given.
 * @param actionLabel text of the top bar's action, or null for none.
 * @param onAction called when the action is tapped, or null for none.
 * @param footer the buttons at the bottom, or null for no footer.
 * @param body the content of the scrolling body, top to bottom.
 */
@Composable
internal fun PaymentPage(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.s16, vertical = Space.s8),
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

/**
 * The centred block at the top of Add to expenses and Expense detail: a tag saying where the payment came from,
 * the amount in `Amount/L` and the line with when and how it was paid.
 *
 * @param tag the tag's text, for example "From SMS".
 * @param tagTone the tag's colours.
 * @param amount the formatted amount, "₹450".
 * @param caption the line under it, "Today 9:12 · HDFC card ••4521".
 */
@Composable
internal fun AmountHeader(tag: String, tagTone: TagTone, amount: String, caption: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(vertical = Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Tag(label = tag, tone = tagTone)
        AppText(text = amount, style = AppTheme.type.amountL, textAlign = TextAlign.Center)
        AppText(
            text = caption,
            style = AppTheme.type.caption,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The CATEGORY label and its chips. With [onMore] the first three categories are shown (the chosen one first when it
 * is not among them) followed by "More", as on Add to expenses; without it every category is shown, wrapping onto
 * more lines.
 *
 * @param selected the chosen category.
 * @param onSelect called with the category the person tapped.
 * @param onMore opens the full list (Choose category), or null to show every category here.
 */
@Composable
internal fun CategoryPicker(selected: Category, onSelect: (Category) -> Unit, onMore: (() -> Unit)?) {
    SectionLabel(text = stringResource(R.string.payment_label_category))
    if (onMore == null) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s12),
        ) {
            Category.entries.forEach { category ->
                CategoryChip(category = category, selected = category == selected, onSelect = onSelect)
            }
        }
    } else {
        val quick = Category.entries.take(QuickCategoryCount)
        val shown = if (selected in quick) quick else listOf(selected) + quick.take(QuickCategoryCount - 1)
        // Wraps onto a second line when a large font size makes the chips wider than the screen.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s8),
        ) {
            shown.forEach { category ->
                CategoryChip(category = category, selected = category == selected, onSelect = onSelect)
            }
            AppChip(
                label = stringResource(R.string.payment_category_more),
                selected = false,
                onClick = onMore,
                role = Role.Button,
            )
        }
    }
}

/** One category as a chip. */
@Composable
private fun CategoryChip(category: Category, selected: Boolean, onSelect: (Category) -> Unit) {
    AppChip(label = stringResource(category.label), selected = selected, onClick = { onSelect(category) })
}

/**
 * The COUNT IT IN label with the Just me and Family chips.
 *
 * @param selected the chosen scope.
 * @param onSelect called with the scope the person tapped.
 */
@Composable
internal fun ScopePicker(selected: ExpenseScope, onSelect: (ExpenseScope) -> Unit) {
    SectionLabel(text = stringResource(R.string.payment_label_count_it_in))
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.s8),
        verticalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        ExpenseScope.entries.forEach { scope ->
            AppChip(label = stringResource(scope.label), selected = scope == selected, onClick = { onSelect(scope) })
        }
    }
}

/** The name of each scope on the chips and in Expense detail. */
internal val ExpenseScope.label: Int
    @StringRes get() = when (this) {
        ExpenseScope.ME -> R.string.payment_scope_me
        ExpenseScope.FAMILY -> R.string.payment_scope_family
    }

/**
 * What a payment screen shows when its expense no longer exists: an empty state with a way back.
 *
 * @param onBack goes back to the previous screen.
 */
@Composable
internal fun PaymentGone(onBack: () -> Unit) {
    PaymentPage(title = null, onBack = null) {
        EmptyState(
            icon = AppIcon.Search,
            title = stringResource(R.string.payment_gone_title),
            body = stringResource(R.string.payment_gone_body),
            modifier = Modifier.padding(top = Space.s40),
        ) {
            AppButton(
                label = stringResource(R.string.payment_go_back),
                onClick = onBack,
                style = ButtonStyle.Secondary,
                size = ButtonSize.Medium,
            )
        }
    }
}

/** The empty frame shown for the moment it takes to read an expense from the database. */
@Composable
internal fun PaymentLoading() {
    PaymentPage(title = null, onBack = null) {}
}
