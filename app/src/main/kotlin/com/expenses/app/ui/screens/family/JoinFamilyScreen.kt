package com.expenses.app.ui.screens.family

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.RevealValue
import com.expenses.app.ui.screens.onboarding.OnboardingBody
import com.expenses.app.ui.screens.onboarding.OnboardingHeadline
import com.expenses.app.ui.screens.signin.ActionProblem

/**
 * Join Family on the partner's phone (design 06-family/01; docs/family.md 1.4), drawn by Join group when the link's
 * preview is a family: the brand sticker with Icon/heart, "Arjun invited you to Home", what is shared, and "Join
 * Family" / "Not now". No top bar, as drawn. The list card gains "Earlier Family expenses" when this phone has Family
 * expenses that joining shares (13); the Offline (14) and Problem (15) banners sit at the top of the body.
 *
 * @param inviter the first name of who sent the link ("Arjun").
 * @param familyName the family's name ("Home").
 * @param earlierCount how many of this phone's Family expenses joining shares; 0 hides the row.
 * @param problem the banner when "Join Family" did not happen, or null.
 * @param onJoin "Join Family".
 * @param onNotNow "Not now" (and Back): nothing is joined.
 */
@Composable
fun JoinFamilyScreen(
    inviter: String,
    familyName: String,
    earlierCount: Int,
    problem: ActionProblem?,
    onJoin: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageFrame(
        title = null,
        onBack = null,
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.family_join_button),
                onClick = onJoin,
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Primary,
                size = ButtonSize.Large,
            )
            AppButton(
                label = stringResource(R.string.family_join_not_now),
                onClick = onNotNow,
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        RevealValue(problem) { shown -> FamilyProblemBanner(shown) }
        StickerTile(icon = AppIcon.Heart, tone = StickerTone.Brand)
        OnboardingHeadline(text = stringResource(R.string.family_join_headline, inviter, familyName))
        OnboardingBody(text = stringResource(R.string.family_join_body))
        SurfaceCard {
            InfoRow(
                title = stringResource(R.string.family_fact_counts_title),
                subtitle = stringResource(R.string.family_fact_counts_body),
                icon = AppIcon.Calculator,
            )
            InfoRow(
                title = stringResource(R.string.family_fact_private_title),
                subtitle = stringResource(R.string.family_join_private_body),
                icon = AppIcon.EyeOff,
            )
            if (earlierCount > 0) EarlierExpensesRow(earlierCount)
        }
    }
}

/**
 * Join Family when the person is already in a family (design 06-family/16): the pink sticker with Icon/users, "You're
 * already in a family", that one family at a time is the rule and how to leave first, and "OK", which leaves (as Back
 * does). No top bar, as drawn.
 *
 * @param onOk "OK" and Back.
 */
@Composable
fun AlreadyInFamilyScreen(onOk: () -> Unit, modifier: Modifier = Modifier) {
    PageFrame(
        title = null,
        onBack = null,
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.family_already_ok),
                onClick = onOk,
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Primary,
                size = ButtonSize.Large,
            )
        },
    ) {
        StickerTile(icon = AppIcon.Users, tone = StickerTone.Pink)
        OnboardingHeadline(text = stringResource(R.string.family_already_headline))
        OnboardingBody(text = stringResource(R.string.family_already_body))
    }
}
