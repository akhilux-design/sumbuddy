package com.expenses.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.components.SurfaceCardStyle
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/**
 * How far the Privacy promise headline may run into the body's 16dp end padding.
 *
 * The design sets "Your messages never leave this phone" in two lines in its 328dp text box, the first line 320dp
 * long. The box, the padding and the `Display/L` style are the same here, but Bricolage Grotesque as the phone gets it
 * from Google Fonts sets display text about 2.6% wider than Figma does (measured on the captures of 05 and 07: "Your
 * messages" 153dp against 149, "Know the moment" 280 against 273; DM Sans body text matches within half a percent).
 * The first line then needs about 330dp and "never" wrapped to a third line. 8dp more (336 ≈ 328 × 1.026) gives the
 * design's two lines without touching the text style.
 */
private val HeadlineOverhang = Space.s8

/**
 * Measures the content [extra] wider than the space it is given and lets it run that far past its end edge, into the
 * padding after it; the layout around it does not move.
 */
private fun Modifier.overhangEnd(extra: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
    val extraPx = extra.roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extraPx))
    val width = constraints.constrainWidth(placeable.width)
    layout(width, placeable.height) {
        // placeRelative keeps the start edge in place: in right-to-left layouts the extra runs past the left edge.
        placeable.placeRelative(0, 0)
    }
}

/**
 * Privacy promise (design 01-onboarding/05-privacy-promise): what happens to the person's messages, shown before
 * Android's SMS dialog. Allow SMS access opens the real system dialog over this screen (the design's
 * 06-sms-permission-prompt is only a drawing of it).
 *
 * @param onAllow called by Allow SMS access.
 * @param onTypeByHand called by "Not now, I'll type them in".
 * @param modifier layout changes from the caller.
 */
@Composable
fun PrivacyPromiseScreen(onAllow: () -> Unit, onTypeByHand: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingFrame(
        modifier = modifier,
        bodyPadding = PaddingValues(Space.s16),
        footer = {
            AppButton(
                label = stringResource(R.string.onboarding_privacy_allow),
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                label = stringResource(R.string.onboarding_type_by_hand),
                onClick = onTypeByHand,
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        StickerTile(icon = AppIcon.Lock, tone = StickerTone.Brand)
        OnboardingHeadline(
            text = stringResource(R.string.onboarding_privacy_title),
            modifier = Modifier.overhangEnd(HeadlineOverhang),
        )
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            InfoRow(
                title = stringResource(R.string.onboarding_privacy_phone_title),
                subtitle = stringResource(R.string.onboarding_privacy_phone_body),
                icon = AppIcon.Phone,
            )
            InfoRow(
                title = stringResource(R.string.onboarding_privacy_bank_title),
                subtitle = stringResource(R.string.onboarding_privacy_bank_body),
                icon = AppIcon.Bank,
            )
            InfoRow(
                title = stringResource(R.string.onboarding_privacy_code_title),
                subtitle = stringResource(R.string.onboarding_privacy_code_body),
                icon = AppIcon.Eye,
            )
        }
        SurfaceCard(style = SurfaceCardStyle.Note) {
            AppText(
                text = stringResource(R.string.onboarding_privacy_note),
                style = AppTheme.type.caption,
                modifier = Modifier.fillMaxWidth(),
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * SMS access blocked: shown instead of the system dialog when Android will not ask again. It sends the person to
 * the app's page in Settings; the route moves on by itself once access is on. Built from the Privacy promise
 * layout, since the design has no screen for this case.
 *
 * @param onOpenSettings called by Open Settings.
 * @param onTypeByHand called by "Not now, I'll type them in".
 * @param modifier layout changes from the caller.
 */
@Composable
fun SmsBlockedScreen(onOpenSettings: () -> Unit, onTypeByHand: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingFrame(
        modifier = modifier,
        bodyPadding = PaddingValues(Space.s16),
        footer = {
            AppButton(
                label = stringResource(R.string.onboarding_sms_blocked_open_settings),
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                label = stringResource(R.string.onboarding_type_by_hand),
                onClick = onTypeByHand,
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        StickerTile(icon = AppIcon.Message, tone = StickerTone.Brand)
        OnboardingHeadline(text = stringResource(R.string.onboarding_sms_blocked_title))
        OnboardingBody(text = stringResource(R.string.onboarding_sms_blocked_body))
    }
}

/**
 * Allow notifications (design 01-onboarding/07-allow-notifications), shown on Android 13 and later only. Allow
 * notifications opens the system dialog; either answer moves on.
 *
 * @param onAllow called by Allow notifications.
 * @param onNotNow called by Not now.
 * @param modifier layout changes from the caller.
 */
@Composable
fun AllowNotificationsScreen(onAllow: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingFrame(
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.onboarding_notifications_allow),
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                label = stringResource(R.string.onboarding_not_now),
                onClick = onNotNow,
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        StickerTile(icon = AppIcon.Bell, tone = StickerTone.Brand)
        OnboardingHeadline(text = stringResource(R.string.onboarding_notifications_title))
        OnboardingBody(
            text = stringResource(R.string.onboarding_notifications_body, stringResource(R.string.app_name)),
        )
        InfoRow(
            title = stringResource(R.string.onboarding_notifications_payment_title),
            subtitle = stringResource(R.string.onboarding_notifications_payment_body),
            icon = AppIcon.Cash,
        )
        InfoRow(
            title = stringResource(R.string.onboarding_notifications_bills_title),
            subtitle = stringResource(R.string.onboarding_notifications_bills_body),
            icon = AppIcon.Clock,
        )
        InfoRow(
            title = stringResource(R.string.onboarding_notifications_nothing_title),
            subtitle = stringResource(R.string.onboarding_notifications_nothing_body),
            icon = AppIcon.BellOff,
        )
    }
}
