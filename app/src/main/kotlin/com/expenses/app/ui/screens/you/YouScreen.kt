package com.expenses.app.ui.screens.you

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.expenses.app.R
import com.expenses.app.ui.components.AppSwitch
import com.expenses.app.ui.components.Avatar
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.formatCount
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Diameter of the avatar at the top of the You tab. */
private val ProfileAvatarSize = 56.dp

/**
 * The You tab (design 04-you/00-you; 06-you-not-signed-in): the person's name and whether they are signed in, then the
 * tracking settings and what was read on this phone. Signed in, the name opens Account; signed out, the "Sign in with
 * Google" card under it opens Sign in. Under MORE, "Import from Splitwise" opens the import (04-you/00); Plus and "See
 * the code" are left out (docs/product.md).
 * It draws only what is between the status bar and the bottom navigation, and scrolls.
 *
 * @param state what to show; see [YouUiState].
 * @param modifier layout changes from the caller, applied to the scrolling column.
 * @param contentPadding the space to keep clear of the status bar (top) and the bottom navigation (bottom), as
 * given by the main scaffold. The list scrolls under the bottom navigation and ends above it.
 * @param onEvent called with what the person tapped; see [YouEvent].
 */
@Composable
fun YouScreen(
    state: YouUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onEvent: (YouEvent) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = Space.s16,
                end = Space.s16,
                top = contentPadding.calculateTopPadding() + Space.s8,
                bottom = contentPadding.calculateBottomPadding() + Space.s8,
            ),
        verticalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        Profile(
            profile = state.profile,
            signedIn = state.signedIn,
            onOpenAccount = { onEvent(YouEvent.OpenAccount) },
        )
        if (!state.signedIn) {
            SurfaceCard {
                InfoRow(
                    title = stringResource(R.string.you_sign_in_title),
                    subtitle = stringResource(R.string.you_sign_in_body),
                    icon = AppIcon.User,
                    onClick = { onEvent(YouEvent.SignIn) },
                )
            }
        }
        SectionLabel(text = stringResource(R.string.you_section_tracking))
        TrackingSection(state = state, onEvent = onEvent)
        SectionLabel(text = stringResource(R.string.you_section_privacy))
        SurfaceCard {
            InfoRow(
                title = stringResource(R.string.you_what_we_read_title),
                subtitle = stringResource(
                    R.string.you_what_we_read_subtitle,
                    pluralStringResource(
                        R.plurals.you_what_we_read_messages,
                        state.messagesRead,
                        formatCount(state.messagesRead),
                    ),
                    state.messagesSent,
                ),
                icon = AppIcon.Eye,
                onClick = { onEvent(YouEvent.OpenWhatWeRead) },
            )
        }
        SectionLabel(text = stringResource(R.string.you_section_family))
        SurfaceCard {
            InfoRow(
                title = stringResource(R.string.you_family_title),
                subtitle = familySubtitle(state.family),
                icon = AppIcon.Users,
                onClick = { onEvent(YouEvent.OpenFamily) },
            )
        }
        SectionLabel(text = stringResource(R.string.you_section_more))
        SurfaceCard {
            InfoRow(
                title = stringResource(R.string.you_import_title),
                subtitle = stringResource(R.string.you_import_body),
                icon = AppIcon.Repeat,
                onClick = { onEvent(YouEvent.OpenImport) },
            )
        }
    }
}

/**
 * The avatar with the name next to it and, under the name, "Signed in with Google" or "Not signed in". Signed in, the
 * whole line opens Account (it is 56dp tall, above the 48dp touch target). Without a name the avatar is left out and
 * the name reads "You".
 */
@Composable
private fun Profile(profile: YouProfile, signedIn: Boolean, onOpenAccount: () -> Unit) {
    val open = if (signedIn) Modifier.clickable(role = Role.Button, onClick = onOpenAccount) else Modifier
    Row(
        modifier = Modifier.fillMaxWidth().then(open).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Space.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val name = profile.name
        if (name != null) {
            Avatar(initial = name.take(1).uppercase(), color = profile.avatarColor, size = ProfileAvatarSize)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s0)) {
            AppText(
                text = name ?: stringResource(R.string.you_profile_no_name),
                style = AppTheme.type.headingL,
                color = AppTheme.colors.textPrimary,
            )
            AppText(
                text = stringResource(if (signedIn) R.string.you_profile_signed_in else R.string.you_profile_signed_out),
                style = AppTheme.type.caption,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * The three rows under TRACKING, 8dp apart: SMS auto-tracking (the row opens Accounts and cards, its switch turns
 * tracking on or off), Automatic reminders (the whole row is the switch) and the tracking start date.
 */
@Composable
private fun TrackingSection(state: YouUiState, onEvent: (YouEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        val smsTitle = stringResource(R.string.you_sms_tracking_title)
        SettingRow(
            title = smsTitle,
            subtitle = pluralStringResource(
                R.plurals.you_sms_tracking_subtitle,
                state.trackedAccountCount,
                state.trackedAccountCount,
            ),
            icon = AppIcon.Mail,
            onClick = { onEvent(YouEvent.OpenAccountsAndCards) },
            trailing = {
                AppSwitch(
                    checked = state.smsTrackingOn,
                    onCheckedChange = { onEvent(YouEvent.SetSmsTracking(it)) },
                    contentDescription = smsTitle,
                )
            },
        )
        SettingRow(
            title = stringResource(R.string.you_reminders_title),
            subtitle = pluralStringResource(
                R.plurals.you_reminders_subtitle,
                state.reminderDaysBefore,
                state.reminderDaysBefore,
            ),
            icon = AppIcon.Clock,
            checked = state.remindersOn,
            onCheckedChange = { onEvent(YouEvent.SetReminders(it)) },
            trailing = { AppSwitch(checked = state.remindersOn, onCheckedChange = null) },
        )
        SurfaceCard {
            InfoRow(
                title = stringResource(R.string.you_start_date_title),
                subtitle = state.trackingStartDate?.let { formatDate(it) }
                    ?: stringResource(R.string.you_start_date_not_set),
                icon = AppIcon.Calendar,
                onClick = { onEvent(YouEvent.OpenTrackingStartDate) },
            )
        }
    }
}

/**
 * The FAMILY row's subtitle (04-you/00 and 06): "Share a monthly total with your partner", "Home · Arjun and Meera",
 * "Home · waiting for your partner" or "Home · just you".
 */
@Composable
private fun familySubtitle(family: YouFamily): String = when (family) {
    YouFamily.None -> stringResource(R.string.you_family_none)
    is YouFamily.Together -> stringResource(R.string.you_family_two, family.name, family.me, family.partner)
    is YouFamily.Waiting -> stringResource(R.string.you_family_waiting, family.name)
    is YouFamily.Alone -> stringResource(R.string.you_family_alone, family.name)
}

/** A date as the design writes it, for example "1 Nov 2026", with month names in the phone's language. */
@Composable
internal fun formatDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM yyyy", locale) }
    return date.format(formatter)
}
