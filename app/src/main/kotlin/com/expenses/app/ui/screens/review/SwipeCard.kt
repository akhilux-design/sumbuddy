package com.expenses.app.ui.screens.review

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.expenses.app.R
import com.expenses.app.data.model.AccountType
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.Tag
import com.expenses.app.ui.components.TagTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.core.EdgeHeight
import com.expenses.app.ui.components.core.RaisedSurface
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.format.readAs
import com.expenses.app.ui.format.upiIdLineBreaks
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.icons.ArtIcon
import com.expenses.app.ui.icons.LineIcon
import com.expenses.app.ui.motion.fadingAlpha
import com.expenses.app.ui.motion.fadingColor
import com.expenses.app.ui.screens.payment.PaymentTime
import com.expenses.app.ui.screens.payment.accountText
import com.expenses.app.ui.screens.payment.merchantText
import com.expenses.app.ui.screens.payment.label
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.motion
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Stroke of the card at rest, of the next cards and of the category chip (Figma 1.5). */
internal val SubtleStroke = 1.5.dp

/** Stroke of the card and of the stamp while dragging (Figma 2). */
private val StampStroke = 2.dp

/** The fixed height of the card's top slot, which holds the tag or the stamp. */
private val TopSlotHeight = 40.dp

/** The 3D category icon in the chip. */
private val ChipIconSize = 32.dp

/**
 * The stamp's pop at the commit line: the speed (scale per second) of the kick that takes `motion/spring/pop` (0.5 /
 * 600) from 1 to a peak near 1.08 and back (`design/spec/motion.md`, `motion/scale/pop` row and "Review swipe fling").
 */
private const val StampPopKick = 3.6f

/** The stamp fades in over this long when it first shows, with no scale (`docs/motion.md` 2.6). */
private const val StampFadeInMillis = 100

/** The stamp's padding: 6 top and bottom, 12 at the start, 14 at the end (Figma). */
private val StampPaddingVertical = 6.dp
private val StampPaddingEnd = 14.dp

/** What the top of the card shows. */
enum class SwipeStamp {
    /** At rest: the "From SMS" tag. */
    None,

    /** Dragged right: "Add" with a tick, at the start. */
    Add,

    /** Dragged left: "Ignore" with a ban sign, at the end. */
    Ignore,
}

/**
 * The Swipe Card (design component "Swipe Card", `design/spec/components/Swipe-Card.md`): one waiting payment with
 * its amount, payee, when, account, the category chip and "Split it instead". The stamp is always a word with an
 * icon, never colour alone. The card draws no drag or tilt itself; [ReviewPaymentsScreen] moves it.
 *
 * Motion (`design/spec/motion.md`, "Review swipe fling"): the outline and the edge change colour over 150 ms; the stamp
 * fades in over 100 ms when it first shows, and pops (to about 1.08 and back, `motion/spring/pop`) when the card
 * crosses the commit line ([armed]). With Remove animations on, the stamp just shows. "Split it instead" plays no
 * press haptic: a drag that starts on it would press and then cancel.
 *
 * @param card the payment.
 * @param stamp what the top slot shows.
 * @param armed true while the card is past the commit line: the stamp pops when it becomes true.
 * @param onChangeCategory the chip: opens Choose category.
 * @param onSplit "Split it instead".
 * @param modifier outer modifier.
 * @param actions screen-reader actions (Add and Ignore). They sit on the amount and payee, the card's one focus stop
 * that reads the payment, so TalkBack offers them there.
 */
@Composable
fun SwipeCard(
    card: ReviewCard,
    stamp: SwipeStamp,
    onChangeCategory: () -> Unit,
    onSplit: () -> Unit,
    modifier: Modifier = Modifier,
    armed: Boolean = false,
    actions: List<CustomAccessibilityAction> = emptyList(),
) {
    val colors = AppTheme.colors
    val (strokeTarget, edgeTarget) = when (stamp) {
        SwipeStamp.None -> colors.borderSubtle to colors.edgeNeutral
        SwipeStamp.Add -> colors.brandPrimary to colors.brandEdge
        SwipeStamp.Ignore -> colors.statusNegative to colors.edgeNeutral
    }
    // Colours and the outline's weight fade; they are read only while drawing.
    val stroke by fadingColor(strokeTarget, label = "cardOutline")
    val edge by fadingColor(edgeTarget, label = "cardEdge")
    val weight by fadingAlpha(if (stamp == SwipeStamp.None) 0f else 1f, label = "cardOutlineWeight")
    RaisedSurface(
        faceColor = colors.bgSurfaceRaised,
        // The edge is drawn here so its colour can fade; RaisedSurface keeps the space for it.
        edgeColor = null,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val face = size.height - EdgeHeight.toPx()
                val r = minOf(Radius.xl.toPx(), size.minDimension / 2f)
                drawRoundRect(edge, Offset(0f, EdgeHeight.toPx()), Size(size.width, face), CornerRadius(r, r))
            },
        cornerRadius = Radius.xl,
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.s20),
            verticalArrangement = Arrangement.spacedBy(Space.s16),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopSlot(stamp, armed)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { if (actions.isNotEmpty()) customActions = actions },
                verticalArrangement = Arrangement.spacedBy(Space.s4),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppText(
                    text = formatRupees(card.amountMinor),
                    style = AppTheme.type.amountXL,
                    textAlign = TextAlign.Center,
                )
                // A payee known only by its UPI ID is shown whole and breaks only after its "@" ("rahul.kumar@" /
                // "okaxis"), never in the middle; screen readers read the plain ID.
                val payee = merchantText(card.payee)
                val shownPayee = upiIdLineBreaks(payee)
                AppText(
                    text = shownPayee,
                    style = AppTheme.type.headingM,
                    modifier = if (shownPayee != payee) Modifier.readAs(payee) else Modifier,
                    textAlign = TextAlign.Center,
                )
            }
            Divider()
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                DetailRow(label = stringResource(R.string.review_when), value = whenText(card.time))
                DetailRow(label = stringResource(R.string.review_account), value = cardAccountText(card))
            }
            Divider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = stringResource(R.string.review_category),
                    style = AppTheme.type.bodyM,
                    modifier = Modifier.weight(1f),
                    color = colors.textSecondary,
                )
                CategoryChip(card = card, onClick = onChangeCategory)
            }
            AppButton(
                label = stringResource(R.string.review_split),
                onClick = onSplit,
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
                pressHaptic = false,
            )
        }
        // The outline, over the face and inside it, as `Modifier.border` draws it.
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val w = (SubtleStroke + (StampStroke - SubtleStroke) * weight).toPx()
                    val r = (minOf(Radius.xl.toPx(), size.minDimension / 2f) - w / 2f).coerceAtLeast(0f)
                    drawRoundRect(
                        color = stroke,
                        topLeft = Offset(w / 2f, w / 2f),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(r, r),
                        style = Stroke(w),
                    )
                },
        )
    }
}

/**
 * The 40dp slot at the top: the tag at rest, the stamp at the start (Add) or the end (Ignore) while dragging. The stamp
 * pops when [armed] turns true.
 */
@Composable
private fun TopSlot(stamp: SwipeStamp, armed: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopSlotHeight),
        contentAlignment = when (stamp) {
            SwipeStamp.None -> Alignment.Center
            SwipeStamp.Add -> Alignment.CenterStart
            SwipeStamp.Ignore -> Alignment.CenterEnd
        },
    ) {
        when (stamp) {
            SwipeStamp.None -> Tag(label = stringResource(R.string.review_from_sms), tone = TagTone.Blue)
            SwipeStamp.Add -> Stamp(
                icon = AppIcon.Check,
                label = stringResource(R.string.review_add),
                fill = AppTheme.colors.brandSoft,
                stroke = AppTheme.colors.brandPrimary,
                content = AppTheme.colors.textBrand,
                armed = armed,
            )
            SwipeStamp.Ignore -> Stamp(
                icon = AppIcon.Ban,
                label = stringResource(R.string.review_ignore),
                fill = AppTheme.colors.statusNegativeSoft,
                stroke = AppTheme.colors.statusNegative,
                content = AppTheme.colors.statusNegative,
                armed = armed,
            )
        }
    }
}

/**
 * A stamp: the line icon and the word in Heading/M, on a soft fill with a 2dp outline, radius 12. It fades in over
 * 100 ms when it first shows and pops when [armed] turns true; both in its layer only.
 */
@Composable
private fun Stamp(
    icon: AppIcon,
    label: String,
    fill: Color,
    stroke: Color,
    content: Color,
    armed: Boolean,
) {
    val shape = RoundedCornerShape(Radius.md)
    val reduced = LocalReducedMotion.current
    val alpha = remember { Animatable(if (reduced) 1f else 0f) }
    val scale = remember { Animatable(1f) }
    val fadeIn = motion(Motion.fade<Float>(StampFadeInMillis))
    LaunchedEffect(Unit) { alpha.animateTo(1f, fadeIn) }
    var wasArmed by remember { mutableStateOf(armed) }
    LaunchedEffect(armed) {
        val popped = armed && !wasArmed
        wasArmed = armed
        // The pop is a kick on spring/pop from where the stamp is, so a second crossing mid-pop carries on from it.
        if (popped && !reduced) scale.animateTo(1f, Motion.Spring.Pop.spec(), initialVelocity = scale.velocity + StampPopKick)
    }
    Row(
        modifier = Modifier
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(shape)
            .background(fill)
            .border(StampStroke, stroke, shape)
            .padding(start = Space.s12, top = StampPaddingVertical, end = StampPaddingEnd, bottom = StampPaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(icon = icon, tint = content)
        AppText(text = label, style = AppTheme.type.headingM, color = content)
    }
}

/** A 1dp line across the card in `color/border/subtle`. */
@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.borderSubtle),
    )
}

/** A label at the start and its value at the end, read as one by screen readers. */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
        verticalAlignment = Alignment.Top,
    ) {
        AppText(text = label, style = AppTheme.type.bodyM, color = AppTheme.colors.textSecondary)
        AppText(
            text = value,
            style = AppTheme.type.bodyMStrong,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * The category chip: a pill with the 3D icon, the category and "Change". 48dp inside a 1.5dp outline (51 in all),
 * so it is a full touch target.
 */
@Composable
private fun CategoryChip(card: ReviewCard, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.full)
    val category = stringResource(card.category.label)
    val description = stringResource(R.string.review_change_category, category)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(AppTheme.colors.bgSurface)
            .border(SubtleStroke, AppTheme.colors.borderSubtle, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(SubtleStroke)
            .padding(start = Space.s8, top = Space.s8, end = Space.s16, bottom = Space.s8),
        horizontalArrangement = Arrangement.spacedBy(Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtIcon(icon = card.category.icon, size = ChipIconSize)
        AppText(text = category, style = AppTheme.type.bodyMStrong)
        AppText(
            text = stringResource(R.string.review_change),
            style = AppTheme.type.bodyMStrong,
            color = AppTheme.colors.textBrand,
        )
    }
}

/** "Today, 9:12 pm", "Yesterday, 6:40 pm" or "14 Nov, 9:12 pm" (with the year when it is not this year's). */
@Composable
private fun whenText(time: PaymentTime): String {
    val locale = LocalConfiguration.current.locales[0]
    val clock = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val dayMonth = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    val dayMonthYear = remember(locale) { DateTimeFormatter.ofPattern("d MMM yyyy", locale) }
    val at = time.at.toLocalTime().format(clock)
    val date = time.at.toLocalDate()
    return when (date) {
        time.today -> stringResource(R.string.review_when_today, at)
        time.today.minusDays(1) -> stringResource(R.string.review_when_yesterday, at)
        else -> stringResource(
            R.string.review_when_date,
            date.format(if (date.year == time.today.year) dayMonth else dayMonthYear),
            at,
        )
    }
}

/**
 * The account as the card writes it: "HDFC Credit Card ••4521" for a card with a bank name, otherwise as the other
 * payment screens write it ("HDFC ••1234", "UPI arjun@okhdfc"), and how it was paid when the SMS names no account.
 */
@Composable
private fun cardAccountText(card: ReviewCard): String {
    val account = card.account ?: return stringResource(card.paidWith.label)
    val name = account.name
    val last4 = account.last4
    return when {
        name != null && last4 != null && account.type == AccountType.CREDIT_CARD ->
            stringResource(R.string.review_account_credit_card, name, last4)
        name != null && last4 != null && account.type == AccountType.DEBIT_CARD ->
            stringResource(R.string.review_account_debit_card, name, last4)
        else -> accountText(account) ?: stringResource(card.paidWith.label)
    }
}
