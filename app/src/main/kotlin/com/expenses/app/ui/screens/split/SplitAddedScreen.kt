package com.expenses.app.ui.screens.split

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.BalanceRow
import com.expenses.app.ui.components.BalanceRowDirection
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.format.upiIdLineBreaks
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.keptWhileLeaving
import com.expenses.app.ui.screens.payment.monthName
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/**
 * Split. Sorted. (design 02-everyday/13-split-added): the split tile, who owes the person now, their Balance Rows,
 * "Your share is in November" and, when the rule was kept, "Remembered". "Done" (and Back) go back to the screen that
 * opened Split it; "Undo" takes the split back.
 *
 * @param state what to show; see [SplitAddedUiState]. [SplitAddedUiState.Undone] keeps the last page drawn, without
 * acting on taps, as the caller leaves.
 * @param onEvent called with what the person did; see [SplitAddedEvent].
 * @param modifier outer modifier. The screen fills the space it is given and keeps clear of the system bars.
 */
@Composable
fun SplitAddedScreen(state: SplitAddedUiState, onEvent: (SplitAddedEvent) -> Unit, modifier: Modifier = Modifier) {
    BackHandler { onEvent(SplitAddedEvent.Done) }
    val leaving = state == SplitAddedUiState.Undone
    val shown = keptWhileLeaving(state, leaving) { it is SplitAddedUiState.Shown } as? SplitAddedUiState.Shown
    val events: (SplitAddedEvent) -> Unit = if (leaving) ({ }) else onEvent
    PageFrame(
        title = null,
        onBack = null,
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.split_added_done),
                onClick = { events(SplitAddedEvent.Done) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (shown != null) {
                AppButton(
                    label = stringResource(R.string.split_added_undo),
                    onClick = { events(SplitAddedEvent.Undo) },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Ghost,
                    size = ButtonSize.Medium,
                    enabled = !shown.busy,
                )
            }
        },
    ) {
        // The design's tile starts 40dp below the status bar: 8 from the page, 20 here and the 12dp gap.
        Spacer(Modifier.height(Space.s20))
        StickerTile(icon = AppIcon.Split, tone = StickerTone.Brand, arrival = true)
        AppText(
            text = stringResource(R.string.split_added_title),
            style = AppTheme.type.displayL,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() },
        )
        if (shown != null) Content(shown)
    }
}

@Composable
private fun Content(state: SplitAddedUiState.Shown) {
    AppText(
        text = sentenceText(state.sentence),
        style = AppTheme.type.bodyL,
        modifier = Modifier.fillMaxWidth(),
        color = AppTheme.colors.textSecondary,
    )
    if (state.balances.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            state.balances.forEach { balance ->
                BalanceRow(
                    name = balance.name,
                    // A direct split: the caption of the Groups tab's friend rows (02-everyday/20).
                    detail = balance.group ?: stringResource(
                        if (balance.owesYou) R.string.split_added_row_owes_you else R.string.split_added_row_you_owe,
                    ),
                    amount = (if (balance.owesYou) "+" else "-") + formatRupees(balance.amountMinor),
                    initial = balance.initial,
                    direction = if (balance.owesYou) BalanceRowDirection.OwesYou else BalanceRowDirection.YouOwe,
                    avatarColor = balance.avatar,
                )
            }
        }
    }
    InfoRow(
        title = stringResource(R.string.split_added_share_title, monthName(state.month)),
        subtitle = shareText(state.myShareMinor, state.totalMinor),
        icon = AppIcon.Chart,
    )
    state.rememberedFor?.let { payee ->
        InfoRow(
            title = stringResource(R.string.split_added_remembered_title),
            // A payee known only by its UPI ID breaks only after its "@" (the row reads it without the marks).
            subtitle = stringResource(R.string.split_added_remembered_body, upiIdLineBreaks(payee)),
            icon = AppIcon.Repeat,
        )
    }
}

/**
 * The line under "Your share is in November": "Only ₹150 counts in your month, not the full ₹450." as drawn; when the
 * share is the whole amount or nothing, "Only" would read wrong, so "All of the ₹450 counts in your month." or "None of
 * the ₹450 counts in your month."
 */
@Composable
private fun shareText(myShareMinor: Long, totalMinor: Long): String = when {
    myShareMinor >= totalMinor -> stringResource(R.string.split_added_share_all, formatRupees(totalMinor))
    myShareMinor <= 0L -> stringResource(R.string.split_added_share_none, formatRupees(totalMinor))
    else -> stringResource(R.string.split_added_share_body, formatRupees(myShareMinor), formatRupees(totalMinor))
}

/** The sentence under "Split. Sorted.". */
@Composable
private fun sentenceText(sentence: AddedSentence): String = when (sentence) {
    is AddedSentence.EachOwesYou ->
        stringResource(R.string.split_added_each_owe, namesText(sentence.names), formatRupees(sentence.eachMinor), sentence.what)
    is AddedSentence.OneOwesYou ->
        stringResource(R.string.split_added_one_owes, sentence.name, formatRupees(sentence.amountMinor), sentence.what)
    is AddedSentence.OweYouTogether ->
        stringResource(R.string.split_added_owe_together, namesText(sentence.names), formatRupees(sentence.totalMinor), sentence.what)
    is AddedSentence.YouOwe ->
        stringResource(R.string.split_added_you_owe, sentence.payer, formatRupees(sentence.amountMinor), sentence.what)
    is AddedSentence.InGroup -> stringResource(R.string.split_added_in_group, sentence.group)
    is AddedSentence.WithFriend -> stringResource(R.string.split_added_with_friend, sentence.name)
}
