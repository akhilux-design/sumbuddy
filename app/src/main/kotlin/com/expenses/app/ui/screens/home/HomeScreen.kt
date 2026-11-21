package com.expenses.app.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenses.app.R
import com.expenses.app.cardbill.BillState
import com.expenses.app.cardbill.CardBillSummary
import com.expenses.app.ui.motion.AnimatedItem
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.Avatar
import com.expenses.app.ui.components.BalanceCard
import com.expenses.app.ui.components.Banner
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.OnRaisedSurface
import com.expenses.app.ui.components.PlaceholderCard
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.SegmentedToggle
import com.expenses.app.ui.components.SegmentedToggleSide
import com.expenses.app.ui.components.StatTile
import com.expenses.app.ui.components.StatTilePair
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.components.StatTileTone
import com.expenses.app.ui.components.Tag
import com.expenses.app.ui.components.TagTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.core.RaisedSurface
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.format.readAs
import com.expenses.app.ui.format.upiIdLineBreaks
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.screens.activity.ActivityItem
import com.expenses.app.ui.screens.activity.ActivityItemRow
import com.expenses.app.ui.screens.cardbill.dayMonth
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Width of the yellow outline of the payment and tip cards. Figma counts it as layout, inside the 16 padding. */
private val HighlightBorderWidth = 2.dp

/** The line between the payment's actions and "Review all" (Figma 1). */
private val DividerHeight = 1.dp

/**
 * The Home tab: greeting with the Me or Family toggle, the month's spending, and below it either the payment
 * waiting for a decision with split totals and the latest expenses, an empty state for a fresh start, or the
 * manual-mode list with a tip that offers automatic tracking. An offline banner sits above the spending when
 * the phone has no connection. On the Family side the Family card follows the spending (Set up Family without a
 * family, 02-everyday/51; the invite's state with one, 06-family/03 and 04), and with a family the family month's By
 * person, Top categories and Latest replace the rest (06-family/02 to 07).
 *
 * The screen draws only what lies between the status bar and the bottom navigation; the body scrolls and runs
 * under the bottom navigation.
 *
 * @param state what to show; see [HomeUiState].
 * @param modifier outer modifier. The screen fills the space it is given.
 * @param contentPadding the space to keep clear of the status bar (top) and the bottom navigation (bottom), as
 * given by `MainScaffold`.
 * @param onEvent called with what the person tapped; see [HomeEvent].
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onEvent: (HomeEvent) -> Unit = {},
) {
    val layoutDirection = LocalLayoutDirection.current
    // The design gives the body 8 top padding on the tracking and family month screens and 12 on start fresh and
    // manual mode.
    val bodyTop = if (state.content is HomeContent.Tracking || state.content is HomeContent.FamilyMonth) Space.s8 else Space.s12
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection) + Space.s16,
            top = contentPadding.calculateTopPadding() + bodyTop,
            end = contentPadding.calculateEndPadding(layoutDirection) + Space.s16,
            bottom = contentPadding.calculateBottomPadding() + Space.s8,
        ),
        verticalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        item(key = "greeting") {
            AnimatedItem {
                Greeting(
                    user = state.user,
                    today = state.today,
                    view = state.view,
                    onViewChange = { onEvent(HomeEvent.ViewChanged(it)) },
                )
            }
        }
        if (state.offline) {
            item(key = "offline") { AnimatedItem { OfflineBanner(familyMonth = state.content is HomeContent.FamilyMonth) } }
        }
        if ((state.content as? HomeContent.FamilyMonth)?.loading == true) {
            // The first pull of the family month: the card's placeholder (06-family/06).
            item(key = "spent-loading") {
                AnimatedItem { PlaceholderCard(description = stringResource(R.string.home_family_loading)) }
            }
        } else {
            item(key = "spent") {
                AnimatedItem {
                    val month = monthName(state.month)
                    val caption = state.familyCaption
                    BalanceCard(
                        label = if (caption != null) familySpentLabel(caption, month) else stringResource(R.string.home_spent_label, month),
                        amount = formatRupees(state.spentMinor),
                        caption = if (caption != null) familyCaptionText(caption) else stringResource(state.content.spentCaption),
                        onClick = { onEvent(HomeEvent.SpentClicked) },
                        fadeKey = state.view,
                    )
                }
            }
        }
        state.familyCard?.let { card ->
            item(key = "family-card") { AnimatedItem { FamilyCardView(card = card, onEvent = onEvent) } }
        }
        when (val content = state.content) {
            is HomeContent.FamilyMonth -> familyMonthItems(content, onEvent)
            is HomeContent.Tracking -> trackingItems(content, state.cardBills, onEvent)
            HomeContent.StartFresh -> item(key = "start-fresh") {
                AnimatedItem {
                    StartFreshState(onAdd = { onEvent(HomeEvent.AddExpense) })
                }
            }
            is HomeContent.Manual -> manualItems(content, onEvent)
        }
    }
}

/** The caption under the month's amount for each kind of content. */
private val HomeContent.spentCaption: Int
    @StringRes get() = when (this) {
        is HomeContent.Tracking -> R.string.home_spent_caption_tracking
        HomeContent.StartFresh -> R.string.home_spent_caption_start_fresh
        is HomeContent.Manual -> R.string.home_spent_caption_manual
        // The family month's caption is [HomeUiState.familyCaption]; this is never read for it.
        is HomeContent.FamilyMonth -> R.string.home_spent_caption_family
    }

/**
 * The tracking body: payment waiting for a decision, split totals, CARD BILLS (design 02-everyday/145), the Latest
 * header and the latest expenses.
 */
private fun LazyListScope.trackingItems(content: HomeContent.Tracking, cardBills: List<CardBillSummary>, onEvent: (HomeEvent) -> Unit) {
    content.detected?.let { payment ->
        // Keyed by the payment, so a new one fades in where the decided one was.
        item(key = "detected-${payment.id}") { AnimatedItem { DetectedPaymentCard(payment = payment, onEvent = onEvent) } }
    }
    content.splitTotals?.let { totals ->
        item(key = "split-totals") { AnimatedItem { SplitTotalTiles(totals = totals, onClick = { onEvent(HomeEvent.SplitTotalsClicked) }) } }
    }
    if (cardBills.isNotEmpty()) {
        item(key = "card-bills-label") { AnimatedItem { SectionLabel(text = stringResource(R.string.cardbill_home_label)) } }
        item(key = "card-bills") { AnimatedItem { CardBillsCard(bills = cardBills, onOpen = { onEvent(HomeEvent.OpenCardBill(it)) }) } }
    }
    item(key = "latest-header") { AnimatedItem { LatestHeader(onAdd = { onEvent(HomeEvent.AddExpense) }) } }
    expenseItems(content.latest, onEvent)
}

/** The manual-mode body: the tracking tip, the Latest label, the latest expenses and the Add expense button. */
private fun LazyListScope.manualItems(content: HomeContent.Manual, onEvent: (HomeEvent) -> Unit) {
    if (content.showTrackingTip) {
        item(key = "tracking-tip") {
            AnimatedItem {
                TrackingTipCard(
                    onTurnOn = { onEvent(HomeEvent.TurnOnTracking) },
                    onNotNow = { onEvent(HomeEvent.DismissTrackingTip) },
                )
            }
        }
    }
    if (content.latest.isNotEmpty()) {
        item(key = "latest-label") { AnimatedItem { SectionLabel(text = stringResource(R.string.home_latest_title)) } }
        expenseItems(content.latest, onEvent)
    }
    item(key = "add-expense") {
        AnimatedItem {
            AppButton(
                label = stringResource(R.string.home_add_expense),
                onClick = { onEvent(HomeEvent.AddExpense) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Primary,
                size = ButtonSize.Large,
            )
        }
    }
}

/** One row per expense, drawn as Activity draws it; the amount is the person's share. */
private fun LazyListScope.expenseItems(expenses: List<ActivityItem>, onEvent: (HomeEvent) -> Unit) {
    items(expenses, key = { "expense-${it.id}" }) { expense ->
        AnimatedItem {
            ActivityItemRow(item = expense, onClick = { onEvent(HomeEvent.ExpenseTapped(expense.id)) })
        }
    }
}

/** The month's name as the spent card writes it, for example "November", in the phone's language. */
@Composable
private fun monthName(month: YearMonth): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("LLLL", locale) }
    return month.format(formatter)
}

/** Today's date as the greeting writes it, for example "Sunday, 16 Nov", in the phone's language. */
@Composable
private fun todayLabel(today: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("EEEE, d MMM", locale) }
    return today.format(formatter)
}

/** When a payment was seen, as the payment card writes it: "now", "5 min ago", "3 h ago" or "14 Nov". */
@Composable
private fun SeenAt.label(): String = when (this) {
    SeenAt.Now -> stringResource(R.string.home_seen_now)
    is SeenAt.MinutesAgo -> pluralStringResource(R.plurals.home_seen_minutes, minutes, minutes)
    is SeenAt.HoursAgo -> pluralStringResource(R.plurals.home_seen_hours, hours, hours)
    is SeenAt.OnDate -> {
        val locale = LocalConfiguration.current.locales[0]
        val formatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
        date.format(formatter)
    }
}

/**
 * The top row: the avatar, "Hey <name>" over today's date, and the Me or Family toggle at the end. Without a name
 * the greeting reads "Hey there" and the avatar is left out.
 *
 * @param user who the greeting is for.
 * @param today today's date.
 * @param view the chosen side of the toggle.
 * @param onViewChange called with the side the person switched to.
 */
@Composable
private fun Greeting(user: HomeUser, today: LocalDate, view: HomeView, onViewChange: (HomeView) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val name = user.firstName
        if (name != null) {
            Avatar(initial = name.take(1).uppercase(), color = user.avatarColor)
        }
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                text = if (name != null) {
                    stringResource(R.string.home_greeting, name)
                } else {
                    stringResource(R.string.home_greeting_no_name)
                },
                style = AppTheme.type.headingM,
                modifier = Modifier.semantics { heading() },
                color = AppTheme.colors.textPrimary,
            )
            AppText(text = todayLabel(today), style = AppTheme.type.caption, color = AppTheme.colors.textSecondary)
        }
        SegmentedToggle(
            left = stringResource(R.string.home_view_me),
            right = stringResource(R.string.home_view_family),
            selected = if (view == HomeView.Me) SegmentedToggleSide.Left else SegmentedToggleSide.Right,
            onSelectedChange = { side ->
                onViewChange(if (side == SegmentedToggleSide.Left) HomeView.Me else HomeView.Family)
            },
        )
    }
}

/**
 * The soft yellow banner that says the phone is offline. Screen readers announce it when it appears.
 *
 * @param familyMonth true on the Family side with a family: the family month syncs when the phone is back online
 * (06-family/07); otherwise splits do.
 */
@Composable
private fun OfflineBanner(familyMonth: Boolean) {
    Banner(
        title = stringResource(R.string.home_offline_title),
        subtitle = stringResource(if (familyMonth) R.string.home_offline_body_family else R.string.home_offline_body),
        icon = AppIcon.WifiOff,
    )
}

/**
 * The raised card with a yellow outline and a yellow band under it, used for the detected payment and the tip.
 * Radius 24, `color/bg/surface-raised`, 16 padding inside the 2dp outline, 12 between the parts.
 *
 * @param content the parts of the card, top to bottom.
 */
@Composable
internal fun HighlightCard(content: @Composable ColumnScope.() -> Unit) {
    RaisedSurface(
        faceColor = AppTheme.colors.bgSurfaceRaised,
        edgeColor = AppTheme.colors.accentYellowEdge,
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = Radius.xl,
        border = BorderStroke(HighlightBorderWidth, AppTheme.colors.accentYellow),
        contentAlignment = Alignment.TopStart,
    ) {
        // Split and Not now take the lighter Secondary face, so they do not look disabled on the grey card.
        OnRaisedSurface {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Space.s16 + HighlightBorderWidth),
                verticalArrangement = Arrangement.spacedBy(Space.s12),
                content = content,
            )
        }
    }
}

/**
 * The payment found in SMS with its Add, Split and Ignore buttons.
 *
 * @param payment the payment to decide on.
 * @param onEvent receives the choice.
 */
@Composable
private fun DetectedPaymentCard(payment: DetectedPayment, onEvent: (HomeEvent) -> Unit) {
    HighlightCard {
        // The tag, the source, the title and the note describe one payment: screen readers read them as one item,
        // with the same 12dp gap between the two parts as the card has. The buttons stay separate.
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(Space.s12),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Tag(label = stringResource(R.string.home_detected_tag), tone = TagTone.Yellow)
                // Pushed to the end; the start padding keeps it clear of the tag when a large font makes both wide.
                val seenAt = payment.seenAt.label()
                AppText(
                    text = payment.accountLast4?.let { stringResource(R.string.home_detected_source, it, seenAt) }
                        ?: seenAt,
                    style = AppTheme.type.caption,
                    modifier = Modifier.weight(1f).padding(start = Space.s8),
                    color = AppTheme.colors.textSecondary,
                    textAlign = TextAlign.End,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                val merchant = payment.merchant ?: stringResource(R.string.home_detected_no_merchant)
                // A payee known only by its UPI ID is shown whole and breaks only after its "@" ("₹312 to" /
                // "uberindia.rides@" / "hdfcbank"), never in the middle; screen readers read the plain sentence.
                val shownMerchant = upiIdLineBreaks(merchant)
                val isUpiId = shownMerchant != merchant
                val amount = formatRupees(payment.amountMinor)
                val headline = stringResource(payment.payeeWording.amountAndPayee, amount, merchant)
                AppText(
                    text = stringResource(payment.payeeWording.amountAndPayee, amount, shownMerchant),
                    style = AppTheme.type.headingL,
                    modifier = Modifier.fillMaxWidth().then(if (isUpiId) Modifier.readAs(headline) else Modifier),
                    color = AppTheme.colors.textPrimary,
                )
                payment.lastSplitWith?.let { group ->
                    val lastSplit = stringResource(R.string.home_detected_last_split, merchant, group)
                    AppText(
                        text = stringResource(R.string.home_detected_last_split, shownMerchant, group),
                        style = AppTheme.type.bodyM,
                        modifier = Modifier.fillMaxWidth().then(if (isUpiId) Modifier.readAs(lastSplit) else Modifier),
                        color = AppTheme.colors.textSecondary,
                    )
                }
            }
        }
        EqualActions(spacing = Space.s8) {
            AppButton(
                label = stringResource(R.string.home_detected_add),
                onClick = { onEvent(HomeEvent.AddDetectedPayment(payment.id)) },
                style = ButtonStyle.Primary,
                size = ButtonSize.Medium,
            )
            AppButton(
                label = stringResource(R.string.home_detected_split),
                onClick = { onEvent(HomeEvent.SplitDetectedPayment(payment.id)) },
                style = ButtonStyle.Secondary,
                size = ButtonSize.Medium,
            )
            AppButton(
                label = stringResource(R.string.home_detected_ignore),
                onClick = { onEvent(HomeEvent.IgnoreDetectedPayment(payment.id)) },
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        }
        if (payment.othersWaiting > 0) {
            // Several wait: a line under the actions, then "Review all N" opens them as a stack (design 33).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DividerHeight)
                    .background(AppTheme.colors.borderSubtle),
            )
            val waiting = payment.othersWaiting + 1
            AppButton(
                label = pluralStringResource(R.plurals.home_detected_review_all, waiting, waiting),
                onClick = { onEvent(HomeEvent.ReviewAll) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        }
    }
}

/**
 * The tip in manual mode that offers automatic tracking.
 *
 * @param onTurnOn called by "Turn on".
 * @param onNotNow called by "Not now".
 */
@Composable
private fun TrackingTipCard(onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    HighlightCard {
        Tag(label = stringResource(R.string.home_tip_tag), tone = TagTone.Yellow)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            AppText(
                text = stringResource(R.string.home_tip_title, stringResource(R.string.app_name)),
                style = AppTheme.type.headingM,
                modifier = Modifier.fillMaxWidth(),
                color = AppTheme.colors.textPrimary,
            )
            AppText(
                text = stringResource(R.string.home_tip_body),
                style = AppTheme.type.bodyM,
                modifier = Modifier.fillMaxWidth(),
                color = AppTheme.colors.textSecondary,
            )
        }
        EqualActions(spacing = Space.s12) {
            AppButton(
                label = stringResource(R.string.home_tip_turn_on),
                onClick = onTurnOn,
                style = ButtonStyle.Primary,
                size = ButtonSize.Medium,
            )
            AppButton(
                label = stringResource(R.string.home_tip_not_now),
                onClick = onNotNow,
                style = ButtonStyle.Secondary,
                size = ButtonSize.Medium,
            )
        }
    }
}

/** The two passes of [EqualActions]: the unplaced copy that gives natural widths, then the buttons shown. */
private enum class EqualActionsSlot { Probe, Buttons }

/**
 * Buttons side by side in equal widths, top aligned, as the design draws the card actions. When one label does
 * not fit its share on one line (a large font size), the buttons are stacked at full width instead, so no word is
 * broken.
 *
 * @param spacing the space between two buttons, across or down.
 * @param content the buttons, in order.
 */
@Composable
private fun EqualActions(spacing: Dp, content: @Composable () -> Unit) {
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        // A copy of the buttons measured at their natural width only to choose the arrangement; it is never
        // placed. Intrinsic measurements are not used because they keep the fallback font after the app font loads.
        val natural = subcompose(EqualActionsSlot.Probe, content).map { it.measure(Constraints()) }
        val count = natural.size
        if (count == 0) return@SubcomposeLayout layout(width, 0) {}
        val share = (width - gap * (count - 1)) / count
        val fits = natural.all { it.width <= share }
        val measurables = subcompose(EqualActionsSlot.Buttons, content)
        if (fits) {
            // The last button takes the pixels left over by the division, so the row ends exactly at the edge.
            val last = width - (share + gap) * (count - 1)
            val placeables = measurables.mapIndexed { index, button ->
                button.measure(Constraints.fixedWidth(if (index == count - 1) last else share))
            }
            layout(width, placeables.maxOf { it.height }) {
                placeables.forEachIndexed { index, button -> button.placeRelative((share + gap) * index, 0) }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            layout(width, placeables.sumOf { it.height } + gap * (count - 1)) {
                var y = 0
                placeables.forEach { button ->
                    button.placeRelative(0, y)
                    y += button.height + gap
                }
            }
        }
    }
}

/**
 * The two tiles with what others owe the person and what the person owes. Both tiles keep the same height
 * when one of them wraps at a large font size. Each opens the Groups tab, as Activity's month tiles open Month view.
 * The design draws only amounts above ₹0, in green and red; ₹0 is in the neutral text colour, as the Groups card
 * and the Group page show a settled ₹0, so the colour never says money is owed when none is.
 *
 * @param totals the two amounts.
 * @param onClick called when either tile is tapped.
 */
@Composable
private fun SplitTotalTiles(totals: SplitTotals, onClick: () -> Unit) {
    val owedToYouLabel = stringResource(R.string.home_owed_to_you)
    val youOweLabel = stringResource(R.string.home_you_owe)
    val openLabel = stringResource(R.string.home_open_groups)
    val tileModifier = Modifier
        .clip(RoundedCornerShape(Radius.lg))
        .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
    StatTilePair(
        first = {
            StatTile(
                label = owedToYouLabel,
                value = formatRupees(totals.owedToYouMinor),
                modifier = tileModifier,
                tone = if (totals.owedToYouMinor > 0) StatTileTone.Positive else StatTileTone.Neutral,
            )
        },
        second = {
            StatTile(
                label = youOweLabel,
                value = formatRupees(totals.youOweMinor),
                modifier = tileModifier,
                tone = if (totals.youOweMinor > 0) StatTileTone.Negative else StatTileTone.Neutral,
            )
        },
    )
}

/**
 * The LATEST heading with the "+ Add" button at the end.
 *
 * @param onAdd called by "+ Add".
 */
@Composable
private fun LatestHeader(onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(text = stringResource(R.string.home_latest_title))
        AppButton(
            label = stringResource(R.string.home_latest_add),
            onClick = onAdd,
            style = ButtonStyle.Ghost,
            size = ButtonSize.Medium,
        )
    }
}

/**
 * The empty state for a fresh start, with the button that adds the first expense by hand.
 *
 * @param onAdd called by "Add one by hand".
 */
@Composable
private fun StartFreshState(onAdd: () -> Unit) {
    EmptyState(
        icon = AppIcon.Sprout,
        title = stringResource(R.string.home_start_fresh_title),
        body = stringResource(R.string.home_start_fresh_body),
        action = {
            AppButton(
                label = stringResource(R.string.home_start_fresh_action),
                onClick = onAdd,
                style = ButtonStyle.Secondary,
                size = ButtonSize.Medium,
            )
        },
    )
}

/**
 * The CARD BILLS list card (design 02-everyday/145; docs/card-bills.md 2.4): one Info Row per card, "HDFC Millennia
 * bill" / "₹24,300 due in 5 days · friends owe ₹8,200". A row opens the Card bill view.
 */
@Composable
private fun CardBillsCard(bills: List<CardBillSummary>, onOpen: (Long) -> Unit) {
    SurfaceCard {
        bills.forEach { bill ->
            InfoRow(
                title = stringResource(R.string.cardbill_home_title, bill.cardName ?: stringResource(R.string.onboarding_account_credit_card)),
                subtitle = cardBillLine(bill),
                icon = AppIcon.Card,
                onClick = { onOpen(bill.billId) },
            )
        }
    }
}

/** "₹24,300 due in 5 days", "₹24,300 was due 14 Nov", "₹24,300 to pay", then " · friends owe ₹8,200" when they do. */
@Composable
private fun cardBillLine(bill: CardBillSummary): String {
    val amount = formatRupees(bill.totalMinor)
    val line = when (val state = bill.state) {
        is BillState.DueIn -> pluralStringResource(R.plurals.cardbill_home_due_in, state.days, amount, state.days)
        BillState.DueToday -> stringResource(R.string.cardbill_home_due_today, amount)
        BillState.DueTomorrow -> stringResource(R.string.cardbill_home_due_tomorrow, amount)
        is BillState.Overdue -> stringResource(R.string.cardbill_home_was_due, amount, dayMonth(state.dueDate))
        // Paid bills are not on Home; the line still reads as a bill to pay.
        BillState.NoDate, is BillState.Paid -> stringResource(R.string.cardbill_home_no_date, amount)
    }
    return if (bill.friendsMinor > 0) stringResource(R.string.cardbill_home_friends, line, formatRupees(bill.friendsMinor)) else line
}

