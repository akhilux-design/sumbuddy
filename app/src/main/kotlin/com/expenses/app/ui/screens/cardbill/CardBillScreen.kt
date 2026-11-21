package com.expenses.app.ui.screens.cardbill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.expenses.app.R
import com.expenses.app.cardbill.BillState
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.BalanceRow
import com.expenses.app.ui.components.BalanceRowDirection
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.ProgressBar
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.core.RaisedSurface
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.RevealValue
import com.expenses.app.ui.screens.grouppage.NoticeBanner
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The face of the green card is 156 high in the design (161 with its band). */
private val CardFaceHeight = 156.dp

/** The darker circle in the card's top corner: 170 across, 35% opaque, its centre 33 from the end, 60 above the top. */
private val BlobSize = 170.dp
private val BlobCentreFromEnd = 33.dp
private val BlobAboveTop = 60.dp
private const val BLOB_ALPHA = 0.35f

/** The insight card's yellow outline. */
private val InsightStroke = 1.5.dp

/**
 * The Card bill view (design 03-getting-paid-back/04; states 20 to 26, docs/card-bills.md 2.3): the card's bill on a
 * green card, what friends owe of it on a yellow-outlined card with a bar, who owes what, and Remind everyone. Signed
 * out it offers Sign in; when nobody owes part of it, it says so. The result of Remind everyone shows as the Group
 * page's banner at the top of the body.
 *
 * @param state what to show; see [CardBillUiState].
 * @param modifier outer modifier. The screen fills the space it is given.
 * @param onEvent called with what the person tapped; see [CardBillEvent].
 */
@Composable
fun CardBillScreen(state: CardBillUiState, modifier: Modifier = Modifier, onEvent: (CardBillEvent) -> Unit = {}) {
    when (state) {
        // The data is on the phone: the canvas shows for the moment the first read takes, or while the view leaves.
        CardBillUiState.Loading, CardBillUiState.Gone ->
            Box(modifier = modifier.fillMaxSize().background(AppTheme.colors.bgCanvas))
        is CardBillUiState.Shown -> ShownBill(state, onEvent, modifier)
    }
}

@Composable
private fun ShownBill(state: CardBillUiState.Shown, onEvent: (CardBillEvent) -> Unit, modifier: Modifier) {
    // Remind everyone sits in the footer and its result banner at the top of the body: bring the top back into view.
    val scroll = rememberScrollState()
    val reduced = LocalReducedMotion.current
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            if (reduced) scroll.scrollTo(0) else scroll.animateScrollTo(0, Motion.Spring.Default.spec())
        }
    }
    PageFrame(
        title = stringResource(R.string.cardbill_title),
        onBack = { onEvent(CardBillEvent.Back) },
        modifier = modifier,
        scrollState = scroll,
        footer = if (state.canRemind) {
            {
                AppButton(
                    label = stringResource(R.string.cardbill_remind_everyone),
                    onClick = { onEvent(CardBillEvent.RemindEveryone) },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Primary,
                    size = ButtonSize.Large,
                    enabled = !state.busy,
                )
            }
        } else {
            null
        },
    ) {
        RevealValue(state.notice) { NoticeBanner(it) }
        BillCard(state)
        when (val body = state.body) {
            is CardBillBody.FriendsOwe -> {
                Insight(body)
                SectionLabel(text = stringResource(R.string.cardbill_who_owes))
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                    body.rows.forEach { row ->
                        BalanceRow(
                            name = row.name,
                            detail = rowDetail(row),
                            amount = "+" + formatRupees(row.owedMinor),
                            initial = row.initial,
                            direction = BalanceRowDirection.OwesYou,
                            avatarColor = row.avatar,
                            onClick = { onEvent(CardBillEvent.OpenGroup(row.groupId)) },
                        )
                    }
                }
            }
            CardBillBody.NobodyOwes -> SurfaceCard {
                InfoRow(
                    title = stringResource(R.string.cardbill_nothing_owed_title),
                    subtitle = stringResource(R.string.cardbill_nothing_owed_body),
                    icon = AppIcon.Users,
                )
            }
            CardBillBody.SignedOut -> SurfaceCard {
                InfoRow(
                    title = stringResource(R.string.you_sign_in_title),
                    subtitle = stringResource(R.string.you_sign_in_body),
                    icon = AppIcon.User,
                    onClick = { onEvent(CardBillEvent.SignIn) },
                )
            }
        }
    }
}

/**
 * The green card: the card's name and digits at the top, the bill's state and amount at the bottom, a darker circle
 * in the top corner. One node for screen readers: "HDFC Millennia, card ending 2217. Bill due in 5 days. ₹24,300."
 */
@Composable
private fun BillCard(state: CardBillUiState.Shown) {
    val colors = AppTheme.colors
    val name = state.cardName ?: stringResource(R.string.onboarding_account_credit_card)
    val status = statusText(state.status)
    val amount = formatRupees(state.totalMinor)
    val description = state.last4?.let { stringResource(R.string.cardbill_card_description, name, it, status, amount) }
        ?: stringResource(R.string.cardbill_card_description_no_digits, name, status, amount)
    RaisedSurface(
        faceColor = colors.brandPrimary,
        edgeColor = colors.brandEdge,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        cornerRadius = Radius.xl,
        contentAlignment = Alignment.TopStart,
    ) {
        val blob = colors.brandEdge
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CardFaceHeight)
                // Drawn, not laid out, so the circle never makes the face taller; the face clips it.
                .drawBehind {
                    val radius = BlobSize.toPx() / 2
                    drawCircle(
                        color = blob,
                        radius = radius,
                        center = Offset(size.width - BlobCentreFromEnd.toPx(), radius - BlobAboveTop.toPx()),
                        alpha = BLOB_ALPHA,
                    )
                }
                .padding(Space.s20),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                AppText(text = name, style = AppTheme.type.title, color = colors.textOnBrand, modifier = Modifier.weight(1f))
                state.last4?.let { digits ->
                    AppText(text = stringResource(R.string.cardbill_digits, digits), style = AppTheme.type.title, color = colors.textOnBrand, maxLines = 1)
                }
            }
            Column(modifier = Modifier.padding(top = Space.s12), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                AppText(text = status.uppercase(), style = AppTheme.type.label, color = colors.textOnBrand)
                AppText(text = amount, style = AppTheme.type.amountL, color = colors.textOnBrand, maxLines = 1)
            }
        }
    }
}

/**
 * The yellow-outlined card: "Friends owe you ₹8,200 of this" with the amount in `color/text/warning`, the bar of the
 * friends' part, and the caption that says the same in words.
 */
@Composable
private fun Insight(body: CardBillBody.FriendsOwe) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(Radius.lg)
    val amount = formatRupees(body.friendsMinor)
    val heading = body.oneFriend?.let { stringResource(R.string.cardbill_friend_owes, it, amount) }
        ?: stringResource(R.string.cardbill_friends_owe, amount)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bgSurface)
            .border(InsightStroke, colors.accentYellow, shape)
            // The design counts the stroke inside the card: the content starts 16 in from the stroke, not the edge.
            .padding(InsightStroke)
            .padding(Space.s16),
        verticalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        AppText(
            text = highlighted(heading, amount, colors.textWarning, colors.textPrimary),
            style = AppTheme.type.headingM,
            color = colors.textPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
        ProgressBar(done = body.friendsMinor, total = body.totalMinor, fillColor = colors.accentYellow)
        AppText(
            text = captionText(body.caption),
            style = AppTheme.type.caption,
            color = colors.textSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** [text] in [base], with its last [part] in [accent]. */
private fun highlighted(text: String, part: String, accent: Color, base: Color): AnnotatedString {
    val at = text.lastIndexOf(part)
    return buildAnnotatedString {
        if (at < 0) {
            withStyle(SpanStyle(color = base)) { append(text) }
        } else {
            withStyle(SpanStyle(color = base)) { append(text.substring(0, at)) }
            withStyle(SpanStyle(color = accent)) { append(part) }
            withStyle(SpanStyle(color = base)) { append(text.substring(at + part.length)) }
        }
    }
}

/** The line over the amount: "Bill due in 5 days", "Bill was due 14 Nov", "Bill paid on 19 Nov". */
@Composable
internal fun statusText(status: BillState): String = when (status) {
    is BillState.DueIn -> pluralStringResource(R.plurals.cardbill_due_in, status.days, status.days)
    BillState.DueToday -> stringResource(R.string.cardbill_due_today)
    BillState.DueTomorrow -> stringResource(R.string.cardbill_due_tomorrow)
    is BillState.Overdue -> stringResource(R.string.cardbill_was_due, dayMonth(status.dueDate))
    is BillState.Paid -> stringResource(R.string.cardbill_paid_on, dayMonth(status.on))
    BillState.NoDate -> stringResource(R.string.cardbill_no_date)
}

@Composable
private fun captionText(caption: CardBillCaption): String {
    val own = formatRupees(caption.ownMinor)
    return when (caption) {
        is CardBillCaption.GetBackBy -> stringResource(R.string.cardbill_get_back_by, dayMonth(caption.dueDate), own)
        is CardBillCaption.GetBackNoDate -> stringResource(R.string.cardbill_get_back_no_date, own)
        is CardBillCaption.OwnIs -> stringResource(R.string.cardbill_own_overdue, own)
        is CardBillCaption.OwnWas -> stringResource(R.string.cardbill_own_paid, own)
    }
}

/** "Goa trip · 3 expenses", "3 expenses" for a direct split, "2 groups · 4 expenses". */
@Composable
private fun rowDetail(row: CardBillRow): String {
    val expenses = pluralStringResource(R.plurals.cardbill_expenses, row.expenseCount, row.expenseCount)
    return when {
        row.groupCount > 1 -> stringResource(
            R.string.cardbill_row_detail,
            pluralStringResource(R.plurals.cardbill_groups, row.groupCount, row.groupCount),
            expenses,
        )
        row.groupName != null -> stringResource(R.string.cardbill_row_detail, row.groupName, expenses)
        else -> expenses
    }
}

/** A date as the card bill writes it: "21 Nov", with month names in the phone's language. */
@Composable
internal fun dayMonth(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    return date.format(formatter)
}
