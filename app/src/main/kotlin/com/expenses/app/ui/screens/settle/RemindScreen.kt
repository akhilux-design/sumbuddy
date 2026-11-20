package com.expenses.app.ui.screens.settle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.expenses.app.R
import com.expenses.app.ui.motion.RevealValue
import com.expenses.app.ui.motion.Reveal
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.Banner
import com.expenses.app.ui.components.BannerTone
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/**
 * Remind on WhatsApp (design 03-getting-paid-back/05-remind-on-whatsapp): "Remind Priya", "Here's what Priya will
 * get", the message with the friend's pay link, "Sent from your own WhatsApp", "The link shows everything", "It clears
 * itself", and "Send on WhatsApp" / "Copy link". Offline, the banner of docs/groups-build-plan.md section 1 sits at the
 * top of the body.
 *
 * @param state what to show; [RemindUiState.Gone] draws the empty frame while the caller leaves.
 * @param onEvent called with what the person did.
 * @param modifier outer modifier.
 * @param linkCopied true after Copy link where the system confirms nothing itself (Android 8 to 12): the top of the
 * body shows "Link copied" (not designed) as the Group page shows "Reminder sent", and screen readers announce it.
 */
@Composable
fun RemindScreen(state: RemindUiState, onEvent: (RemindEvent) -> Unit, modifier: Modifier = Modifier, linkCopied: Boolean = false) {
    val shown = state as? RemindUiState.Shown
    if (shown == null) {
        PageFrame(title = null, onBack = { onEvent(RemindEvent.Back) }, modifier = modifier) {}
        return
    }
    val message = remindMessageOf(shown)
    PageFrame(
        title = stringResource(R.string.settle_remind_title, shown.friendName),
        onBack = { onEvent(RemindEvent.Back) },
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.settle_remind_send),
                onClick = { onEvent(RemindEvent.Send) },
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                label = stringResource(R.string.settle_remind_copy),
                onClick = { onEvent(RemindEvent.Copy) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        RevealValue(shown.problem) { SettleProblemBanner(it) }
        Reveal(visible = linkCopied && shown.problem == null) {
            Banner(
                title = stringResource(R.string.settle_remind_copied_title),
                subtitle = stringResource(R.string.settle_remind_copied_body, shown.friendName),
                icon = AppIcon.CheckCircle,
                tone = BannerTone.Plain,
            )
        }
        AppText(
            text = stringResource(R.string.settle_remind_heading, shown.friendName),
            style = AppTheme.type.headingL,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.bgSurfaceRaised, RoundedCornerShape(Radius.lg))
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = Space.s16, vertical = Space.s12),
            verticalArrangement = Arrangement.spacedBy(Space.s8),
        ) {
            AppText(text = message.owe, style = AppTheme.type.bodyM, modifier = Modifier.fillMaxWidth())
            AppText(text = message.linkIntro, style = AppTheme.type.bodyM, modifier = Modifier.fillMaxWidth())
            message.shownLink?.let { link ->
                AppText(text = link, style = AppTheme.type.bodyMStrong, modifier = Modifier.fillMaxWidth(), color = AppTheme.colors.textBrand)
            }
            AppText(text = message.noApp, style = AppTheme.type.bodyM, modifier = Modifier.fillMaxWidth())
        }
        AppText(
            text = stringResource(R.string.settle_remind_sent_from),
            style = AppTheme.type.caption,
            color = AppTheme.colors.textTertiary,
        )
        InfoRow(
            title = stringResource(R.string.settle_remind_link_title),
            subtitle = stringResource(R.string.settle_remind_link_body),
            icon = AppIcon.Link,
        )
        InfoRow(
            title = stringResource(R.string.settle_remind_clears_title),
            subtitle = stringResource(R.string.settle_remind_clears_body),
            icon = AppIcon.Sparkle,
        )
    }
}

/** The message of [state], from the app's strings. */
@Composable
internal fun remindMessageOf(state: RemindUiState.Shown): RemindMessage =
    RemindMessage.of(LocalResources.current, state.friendName, state.myName, state.amountMinor, state.groupName, state.link)
