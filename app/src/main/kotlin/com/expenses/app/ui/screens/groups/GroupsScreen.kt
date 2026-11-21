package com.expenses.app.ui.screens.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.expenses.app.R
import com.expenses.app.ui.motion.AnimatedItem
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.BalanceCard
import com.expenses.app.ui.components.BalanceCardTone
import com.expenses.app.ui.components.BalanceRow
import com.expenses.app.ui.components.BalanceRowDirection
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PlaceholderCard
import com.expenses.app.ui.components.PlaceholderRow
import com.expenses.app.ui.components.PlaceholderRowCount
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.TabHeader
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.screens.signin.GoogleButton
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space
import java.text.BreakIterator
import kotlin.math.abs

/** Space between rows of one list (`space/8`). The body puts [Space.s12] between its other parts. */
private val RowGap = Space.s8

/** Extra space added on top of [RowGap] where a new part of the body starts, so parts are 12dp apart. */
private val PartGap = Space.s12 - RowGap

/** The header's height in the design (52): the height of the "+ New" button, kept on 39 where there is none. */
private val HeaderHeight = 52.dp

/**
 * The Groups tab: the overall balance, the person's groups and the friends they split with. It also shows the
 * empty state for a first-time user, grey placeholders while loading, Could not load when the groups could not be
 * fetched and none is on this phone, and Sign in while nobody is signed in.
 *
 * The screen draws only what lies between the status bar and the bottom navigation; the body scrolls and runs
 * under the bottom navigation.
 *
 * @param state what to show; see [GroupsUiState].
 * @param modifier layout changes from the caller, applied to the whole screen.
 * @param contentPadding space to keep clear of the status bar (top) and the bottom navigation (bottom), as given
 * by the shell. The top is kept above the first element; the bottom is added after the last one.
 * @param onEvent called with what the person tapped; see [GroupsEvent].
 */
@Composable
fun GroupsScreen(
    state: GroupsUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onEvent: (GroupsEvent) -> Unit = {},
) {
    GroupsBody(contentPadding, modifier) {
        when (state) {
            is GroupsUiState.Loaded -> {
                header(onEvent)
                loadedContent(state, onEvent)
            }
            GroupsUiState.Empty -> {
                header(onEvent)
                emptyContent(onEvent)
            }
            GroupsUiState.Loading -> {
                header(onEvent)
                loadingContent()
            }
            GroupsUiState.CouldNotLoad -> {
                titleOnlyHeader()
                couldNotLoadContent(onEvent)
            }
            GroupsUiState.NotSignedIn -> {
                titleOnlyHeader()
                notSignedInContent(onEvent)
            }
        }
    }
}

/**
 * The scrolling body: 16dp at the sides, 8dp above the first element and below the last one, plus the space
 * the shell asks to keep clear.
 */
@Composable
private fun GroupsBody(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val direction = LocalLayoutDirection.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(direction) + Space.s16,
            top = contentPadding.calculateTopPadding() + Space.s8,
            end = contentPadding.calculateEndPadding(direction) + Space.s16,
            bottom = contentPadding.calculateBottomPadding() + Space.s8,
        ),
        verticalArrangement = Arrangement.spacedBy(RowGap),
        content = content,
    )
}

/** The screen title and the "+ New" button. */
private fun LazyListScope.header(onEvent: (GroupsEvent) -> Unit) {
    item(key = "header") {
        AnimatedItem {
            TabHeader(
                title = stringResource(R.string.groups_title),
                actionLabel = stringResource(R.string.groups_new),
                onAction = { onEvent(GroupsEvent.NewGroupClicked) },
            )
        }
    }
}

/**
 * The screen title alone, as 39 draws the header: signed out nothing can be made yet, and on Could not load the
 * person may already have the group they would make (23's top bar has no action either).
 */
private fun LazyListScope.titleOnlyHeader() {
    item(key = "header") {
        AnimatedItem {
            // The design's Header frame keeps its 52dp height without the "+ New" button, so the card starts where it
            // does on the other states.
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = HeaderHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                AppText(
                    text = stringResource(R.string.groups_title),
                    style = AppTheme.type.displayL,
                    modifier = Modifier.semantics { heading() },
                    color = AppTheme.colors.textPrimary,
                )
            }
        }
    }
}

/**
 * Signed out (39), under the title alone: the card that asks to sign in with "Continue with Google", and the line
 * that says friends do not need the app.
 */
private fun LazyListScope.notSignedInContent(onEvent: (GroupsEvent) -> Unit) {
    item(key = "sign-in") {
        AnimatedItem {
            EmptyState(
                icon = AppIcon.Users,
                title = stringResource(R.string.groups_signin_title),
                body = stringResource(R.string.groups_signin_body),
                modifier = Modifier.padding(top = PartGap),
                action = { GoogleButton(onClick = { onEvent(GroupsEvent.ContinueWithGoogle) }) },
            )
        }
    }
    item(key = "no-app-needed") {
        AnimatedItem {
            InfoRow(
                title = stringResource(R.string.groups_signin_info_title),
                subtitle = stringResource(R.string.groups_signin_info_body),
                modifier = Modifier.padding(top = PartGap),
                icon = AppIcon.Link,
            )
        }
    }
}

/** The overall balance card, the groups, and the friends under their heading. */
private fun LazyListScope.loadedContent(state: GroupsUiState.Loaded, onEvent: (GroupsEvent) -> Unit) {
    item(key = "overall") {
        AnimatedItem {
            OverallCard(state, Modifier.padding(top = PartGap))
        }
    }
    itemsIndexed(state.groups, key = { _, group -> "group-${group.id}" }) { index, group ->
        AnimatedItem {
            val detail = stringResource(
                R.string.groups_row_detail,
                pluralStringResource(R.plurals.groups_row_people, group.memberCount, group.memberCount),
                balanceWord(group.balancePaise),
            )
            BalanceRow(
                name = group.name,
                detail = detail,
                amount = signedAmount(group.balancePaise),
                initial = initialOf(group.name),
                modifier = if (index == 0) Modifier.padding(top = PartGap) else Modifier,
                direction = directionOf(group.balancePaise),
                avatarColor = group.avatarColor,
                onClick = { onEvent(GroupsEvent.GroupClicked(group.id)) },
            )
        }
    }
    if (state.friends.isNotEmpty()) {
        item(key = "friends-label") {
            AnimatedItem {
                SectionLabel(
                    text = stringResource(R.string.groups_friends),
                    modifier = Modifier.padding(top = PartGap),
                )
            }
        }
        itemsIndexed(state.friends, key = { _, friend -> "friend-${friend.id}" }) { index, friend ->
            AnimatedItem {
                BalanceRow(
                    name = friend.name,
                    detail = balanceWord(friend.balancePaise),
                    amount = signedAmount(friend.balancePaise),
                    initial = initialOf(friend.name),
                    modifier = if (index == 0) Modifier.padding(top = PartGap) else Modifier,
                    direction = directionOf(friend.balancePaise),
                    avatarColor = friend.avatarColor,
                    // Only a friend with a direct split on this phone has a page of their own to open.
                    onClick = friend.groupId?.let { groupId -> { onEvent(GroupsEvent.FriendClicked(groupId)) } },
                )
            }
        }
    }
}

/**
 * The overall balance across all groups and direct splits. The label says in words which way the money goes; the
 * caption counts the named groups listed, or the friends when there is no named group (only direct splits), so it
 * never says "across 0 groups".
 */
@Composable
private fun OverallCard(state: GroupsUiState.Loaded, modifier: Modifier = Modifier) {
    val overall = state.overallPaise
    val (label, tone) = when {
        overall > 0 -> R.string.groups_overall_owed to BalanceCardTone.Positive
        overall < 0 -> R.string.groups_overall_owe to BalanceCardTone.Negative
        else -> R.string.groups_overall_settled to BalanceCardTone.Neutral
    }
    BalanceCard(
        label = stringResource(label),
        amount = formatRupees(abs(overall)),
        caption = if (state.groupCount == 0 && state.friends.isNotEmpty()) {
            pluralStringResource(R.plurals.groups_overall_caption_friends, state.friends.size, state.friends.size)
        } else {
            pluralStringResource(R.plurals.groups_overall_caption, state.groupCount, state.groupCount)
        },
        modifier = modifier,
        tone = tone,
    )
}

/**
 * First-run state: the explanation with a New group button. The design's "Coming from Splitwise?" line under it (21) is
 * left out (docs/product.md, decided 2026-11-18): Splitwise import is offered from New group and You > More only.
 */
private fun LazyListScope.emptyContent(onEvent: (GroupsEvent) -> Unit) {
    item(key = "empty") {
        AnimatedItem {
            EmptyState(
                icon = AppIcon.Users,
                title = stringResource(R.string.groups_empty_title),
                body = stringResource(R.string.groups_empty_body),
                modifier = Modifier.padding(top = PartGap),
                action = {
                    AppButton(
                        label = stringResource(R.string.groups_empty_action),
                        onClick = { onEvent(GroupsEvent.NewGroupClicked) },
                        size = ButtonSize.Medium,
                    )
                },
            )
        }
    }
}

/**
 * The groups could not be fetched and none is on this phone: the card of 23 (alert icon, "Nothing is lost", Try
 * again) in place of "No groups yet".
 */
private fun LazyListScope.couldNotLoadContent(onEvent: (GroupsEvent) -> Unit) {
    item(key = "could-not-load") {
        AnimatedItem {
            EmptyState(
                icon = AppIcon.Alert,
                title = stringResource(R.string.groups_error_title),
                body = stringResource(R.string.groups_error_body),
                modifier = Modifier.padding(top = PartGap),
                action = {
                    AppButton(
                        label = stringResource(R.string.groups_try_again),
                        onClick = { onEvent(GroupsEvent.TryAgainClicked) },
                        size = ButtonSize.Medium,
                    )
                },
            )
        }
    }
}

/** Grey shapes where the balance card and the rows will be. Screen readers hear "Loading groups" once. */
private fun LazyListScope.loadingContent() {
    item(key = "placeholder-card") {
        AnimatedItem {
            PlaceholderCard(stringResource(R.string.groups_loading_description), Modifier.padding(top = PartGap))
        }
    }
    items(count = PlaceholderRowCount, key = { index -> "placeholder-row-$index" }) {
        AnimatedItem {
            PlaceholderRow(Modifier.padding(top = PartGap))
        }
    }
}

/** The words that say which way the money goes, so the amount's colour is never the only signal. */
@Composable
private fun balanceWord(balancePaise: Long): String = stringResource(
    when {
        balancePaise > 0 -> R.string.groups_row_owes_you
        balancePaise < 0 -> R.string.groups_row_you_owe
        else -> R.string.groups_row_settled
    },
)

/** Which colour the row's amount takes. */
private fun directionOf(balancePaise: Long): BalanceRowDirection = when {
    balancePaise > 0 -> BalanceRowDirection.OwesYou
    balancePaise < 0 -> BalanceRowDirection.YouOwe
    else -> BalanceRowDirection.Settled
}

/** The amount with its sign as drawn: "+₹2,350", "-₹800", "₹0". */
private fun signedAmount(balancePaise: Long): String =
    if (balancePaise > 0) "+${formatRupees(balancePaise)}" else formatRupees(balancePaise)

/**
 * The letter in the avatar: the first character of the name as a reader sees it, in upper case. It is cut at a
 * character boundary, so a letter made of several code units (a symbol outside the basic plane, or a
 * Devanagari letter with its marks) stays whole.
 */
private fun initialOf(name: String): String {
    if (name.isEmpty()) return name
    val characters = BreakIterator.getCharacterInstance().apply { setText(name) }
    return name.substring(0, characters.following(0)).uppercase()
}
