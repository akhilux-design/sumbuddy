package com.expenses.app.ui.screens.splitwise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.formatCount
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/**
 * Group imported (design 02-everyday/31, 141; docs/splitwise-import.md 5.8): the tick, "{group} is in", how many past
 * expenses came, the "Invite links" card naming the friends, "Friends can join or just pay", then Share invite links
 * (one message with each friend's own link) and Later. No top bar. The Offline or Problem banner sits on top while the
 * links cannot be made.
 *
 * @param state what to show; Loading and Gone draw an empty page while the caller acts.
 * @param onEvent called with what the person did.
 * @param modifier outer modifier.
 */
@Composable
fun GroupImportedScreen(state: GroupImportedUiState, onEvent: (GroupImportedEvent) -> Unit, modifier: Modifier = Modifier) {
    val shown = state as? GroupImportedUiState.Shown
    if (shown == null) {
        PageFrame(title = null, onBack = null, modifier = modifier) {}
        return
    }
    val one = shown.friendNames.size == 1
    PageFrame(
        title = null,
        onBack = null,
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(if (one) R.string.import_done_share_one else R.string.import_done_share),
                onClick = { onEvent(GroupImportedEvent.Share) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !shown.sharing && shown.friendNames.isNotEmpty(),
            )
            AppButton(
                label = stringResource(R.string.import_done_later),
                onClick = { onEvent(GroupImportedEvent.Later) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        // The design's body starts 40dp below the status bar: 8 from the page, 20 here and the 12dp gap.
        Spacer(Modifier.height(Space.s20))
        ImportBannerFor(shown.banner)
        StickerTile(icon = AppIcon.CheckCircle, tone = StickerTone.Brand, arrival = true)
        AppText(
            text = stringResource(R.string.import_done_title, shown.groupName),
            style = AppTheme.type.displayL,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        AppText(
            text = if (shown.expenseCount > 0) {
                pluralStringResource(R.plurals.import_done_body, shown.expenseCount, formatCount(shown.expenseCount))
            } else {
                stringResource(R.string.import_done_body_none)
            },
            style = AppTheme.type.bodyL,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
        )
        if (shown.friendNames.isNotEmpty()) InviteCard(shown.friendNames)
        InfoRow(
            title = stringResource(R.string.import_done_info_title),
            subtitle = stringResource(R.string.import_done_info_body),
            icon = AppIcon.UserPlus,
        )
    }
}

/**
 * The design's "Invite links" card: radius 16 on `color/bg/surface`, 12dp above and below, 16dp at the sides, 2dp
 * between the Label, the names in Title `color/text/brand`, and the Caption.
 */
@Composable
private fun InviteCard(names: List<String>) {
    val one = names.size == 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.bgSurface, RoundedCornerShape(Radius.lg))
            .padding(horizontal = Space.s16, vertical = Space.s12)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        SectionLabel(text = stringResource(if (one) R.string.import_done_links_label_one else R.string.import_done_links_label))
        AppText(text = joinedNames(names), style = AppTheme.type.title, color = AppTheme.colors.textBrand)
        AppText(
            text = if (one) {
                stringResource(R.string.import_done_links_note_one, names.single())
            } else {
                stringResource(R.string.import_done_links_note)
            },
            style = AppTheme.type.caption,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
        )
    }
}

/** "Priya", "Priya and Kiran", "Priya, Kiran and Meera", or "Priya, Kiran and 3 more". */
@Composable
private fun joinedNames(names: List<String>): String = when (names.size) {
    1 -> names[0]
    2 -> stringResource(R.string.import_names_two, names[0], names[1])
    3 -> stringResource(R.string.import_names_three, names[0], names[1], names[2])
    else -> pluralStringResource(R.plurals.import_names_more, names.size - 2, names[0], names[1], names.size - 2)
}
