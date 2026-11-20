package com.expenses.app.ui.screens.join

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.ui.motion.FadeThroughContent
import com.expenses.app.ui.motion.RevealValue
import com.expenses.app.groups.data.JoinPreview
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.PlaceholderCard
import com.expenses.app.ui.components.PlaceholderRow
import com.expenses.app.ui.components.PlaceholderRowCount
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.format.shortName
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.screens.family.AlreadyInFamilyScreen
import com.expenses.app.ui.screens.family.JoinFamilyScreen
import com.expenses.app.ui.screens.onboarding.OnboardingBody
import com.expenses.app.ui.screens.onboarding.OnboardingHeadline
import com.expenses.app.ui.screens.payment.monthName
import com.expenses.app.ui.screens.signin.ActionProblemBanner
import java.time.YearMonth

/**
 * How many other members the group row names before "you and {n} more". One more than this are all named: the row
 * never says "and 1 more", which would hide one name behind as many words.
 */
private const val NamesBeforeMore = 3

/**
 * Join group, opened by a friend's link: the group (02-everyday/32), "This link no longer works" (50), the loading
 * shapes of 22 while it is looked up, and the wording of 23 with Try again when it could not be. A family's link shows
 * Join Family (06-family/01) or "You're already in a family" (16). No top bar, as drawn: "Not now" (or "OK") at the
 * bottom leaves, as does Back.
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param modifier layout changes from the caller.
 */
@Composable
fun JoinGroupScreen(state: JoinGroupUiState, onEvent: (JoinGroupEvent) -> Unit, modifier: Modifier = Modifier) {
    // Loading and the invite swap with a fade-through, keyed by the kind of state.
    FadeThroughContent(targetState = state, contentKey = { it::class }) { state ->
        when (state) {
            JoinGroupUiState.Loading -> PageFrame(
                title = null,
                onBack = null,
                modifier = modifier,
                footer = { NotNowButton(onEvent) },
            ) {
                PlaceholderCard(description = stringResource(R.string.join_loading))
                repeat(PlaceholderRowCount) { PlaceholderRow() }
            }
            is JoinGroupUiState.Ready -> Invite(state, onEvent, modifier)
            // A family's link: Join Family (06-family/01, 13 to 15) and "You're already in a family" (16).
            is JoinGroupUiState.ReadyFamily -> JoinFamilyScreen(
                inviter = shortName(state.preview.inviterName, state.preview.memberNames),
                familyName = state.preview.groupName,
                earlierCount = state.earlierCount,
                problem = state.problem,
                onJoin = { onEvent(JoinGroupEvent.Join) },
                onNotNow = { onEvent(JoinGroupEvent.Leave) },
                modifier = modifier,
            )
            JoinGroupUiState.AlreadyInFamily -> AlreadyInFamilyScreen(onOk = { onEvent(JoinGroupEvent.Leave) }, modifier = modifier)
            is JoinGroupUiState.CouldNotLoad -> PageFrame(
                title = null,
                onBack = null,
                modifier = modifier,
                footer = { NotNowButton(onEvent) },
            ) {
                EmptyState(
                    icon = AppIcon.Alert,
                    title = stringResource(R.string.join_could_not_load_title),
                    body = stringResource(if (state.offline) R.string.join_could_not_load_offline else R.string.join_could_not_load_failed),
                    action = {
                        AppButton(
                            label = stringResource(R.string.join_try_again),
                            onClick = { onEvent(JoinGroupEvent.TryAgain) },
                            size = ButtonSize.Medium,
                        )
                    },
                )
            }
            JoinGroupUiState.LinkNoLongerWorks -> PageFrame(
                title = null,
                onBack = null,
                modifier = modifier,
                footer = {
                    AppButton(
                        label = stringResource(R.string.join_ok),
                        onClick = { onEvent(JoinGroupEvent.Leave) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            ) {
                StickerTile(icon = AppIcon.Link, tone = StickerTone.Yellow)
                OnboardingHeadline(text = stringResource(R.string.join_link_title))
                OnboardingBody(text = stringResource(R.string.join_link_body))
            }
        }
    }
}

/** The group of the link (32): who added the person, how many share it, and what the friend's place owes so far. */
@Composable
private fun Invite(state: JoinGroupUiState.Ready, onEvent: (JoinGroupEvent) -> Unit, modifier: Modifier) {
    val preview = state.preview
    // "Rahul added you to Flat 302": the first name, as the design writes names in a sentence.
    val inviter = shortName(preview.inviterName, preview.memberNames)
    // A direct split has no name of its own: it is called after the friend, as on the Groups tab.
    val groupTitle = preview.groupName.ifBlank { inviter }
    PageFrame(
        title = null,
        onBack = null,
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.join_action),
                onClick = { onEvent(JoinGroupEvent.Join) },
                modifier = Modifier.fillMaxWidth(),
            )
            NotNowButton(onEvent)
        },
    ) {
        RevealValue(state.problem) { problem ->
            ActionProblemBanner(
                problem = problem,
                offlineTitle = stringResource(R.string.join_offline_title),
                offlineBody = stringResource(R.string.join_offline_body),
                failedTitle = stringResource(R.string.join_failed_title),
                failedBody = stringResource(R.string.join_failed_body),
            )
        }
        StickerTile(icon = AppIcon.UserPlus, tone = StickerTone.Brand)
        OnboardingHeadline(
            text = if (preview.groupName.isBlank()) {
                stringResource(R.string.join_headline_direct, inviter)
            } else {
                stringResource(R.string.join_headline, inviter, preview.groupName)
            },
        )
        OnboardingBody(text = pluralStringResource(R.plurals.join_body, preview.memberCount, preview.memberCount))
        SurfaceCard {
            InfoRow(title = groupTitle, subtitle = membersLine(namedMembers(preview.memberNames, preview.inviterName)), icon = AppIcon.Home)
            InfoRow(title = balanceLine(preview, groupTitle), subtitle = expensesLine(preview, state.month), icon = AppIcon.Cash)
        }
    }
}

/** "Not now": the quiet way out, as drawn under "Join group". */
@Composable
private fun NotNowButton(onEvent: (JoinGroupEvent) -> Unit) {
    AppButton(
        label = stringResource(R.string.join_not_now),
        onClick = { onEvent(JoinGroupEvent.Leave) },
        modifier = Modifier.fillMaxWidth(),
        style = ButtonStyle.Ghost,
        size = ButtonSize.Medium,
    )
}

/**
 * Who the group row names.
 *
 * @param names the names written out, in order.
 * @param more how many other members are not named: 0, or 2 and more (never 1).
 */
internal data class NamedMembers(val names: List<String>, val more: Int)

/**
 * The other members the group row names, as 32 does ("Rahul, Priya and you"): everyone, with the person who sent the
 * link first, as the headline names them ("Rahul added you to Flat 302"). Only a group with more than four other
 * members is cut short, to the first three and "you and {n} more"; four are all named, since "and 1 more" would hide
 * one name behind as many words. The person from the headline is never among those left out.
 *
 * @param memberNames the other members' first names, as the server sends them.
 * @param inviterName the name of the person who sent the link, whole or first name only.
 */
internal fun namedMembers(memberNames: List<String>, inviterName: String): NamedMembers {
    val others = memberNames.filter { it.isNotBlank() }
    val inviterFirst = inviterName.trim().substringBefore(' ')
    val inviter = others.indexOfFirst { it.equals(inviterName.trim(), ignoreCase = true) }
        .takeIf { it >= 0 }
        ?: others.indexOfFirst { inviterFirst.isNotEmpty() && it.trim().substringBefore(' ').equals(inviterFirst, ignoreCase = true) }
    val ordered = if (inviter > 0) listOf(others[inviter]) + others.filterIndexed { index, _ -> index != inviter } else others
    return if (ordered.size <= NamesBeforeMore + 1) {
        NamedMembers(ordered, more = 0)
    } else {
        NamedMembers(ordered.take(NamesBeforeMore), more = ordered.size - NamesBeforeMore)
    }
}

/** "Rahul, Priya and you": the other members' first names, then the person. */
@Composable
private fun membersLine(members: NamedMembers): String = when {
    members.names.isEmpty() -> stringResource(R.string.join_members_only_you)
    members.more == 0 -> stringResource(R.string.join_members, members.names.joinToString(", "))
    else -> pluralStringResource(R.plurals.join_members_more, members.more, members.names.joinToString(", "), members.more)
}

/** "You owe ₹1,150 so far", or what the place is owed, or that nothing is owed either way. */
@Composable
private fun balanceLine(preview: JoinPreview, groupTitle: String): String = when {
    preview.youOweMinor > 0 -> stringResource(R.string.join_you_owe, formatRupees(preview.youOweMinor))
    preview.owedToYouMinor > 0 -> stringResource(R.string.join_owes_you, groupTitle, formatRupees(preview.owedToYouMinor))
    else -> stringResource(R.string.join_nothing_owed)
}

/** "5 expenses in November": this month's expenses that involve the friend's place. */
@Composable
private fun expensesLine(preview: JoinPreview, month: YearMonth): String =
    pluralStringResource(R.plurals.join_expenses, preview.expensesThisMonth, preview.expensesThisMonth, monthName(month))
