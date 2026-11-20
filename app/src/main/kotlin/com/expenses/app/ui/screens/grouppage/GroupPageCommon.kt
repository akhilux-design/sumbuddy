package com.expenses.app.ui.screens.grouppage

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.expenses.app.R
import com.expenses.app.groups.data.ActionResult
import com.expenses.app.ui.components.Banner
import com.expenses.app.ui.components.BannerTone
import com.expenses.app.ui.icons.AppIcon
import java.text.BreakIterator

// Pieces the Group page and Group settings share.

/**
 * The banner an online-only action leaves at the top of the body (docs/groups-build-plan.md section 1): "You're
 * offline" on yellow-soft with the wifi-off icon, "Could not do that now" the same with the alert icon, and Remind's
 * results on surface. None is designed for these screens.
 *
 * @param notice what to say.
 * @param modifier layout changes from the caller.
 */
@Composable
internal fun NoticeBanner(notice: GroupNotice, modifier: Modifier = Modifier) {
    when (notice) {
        GroupNotice.Offline -> Banner(
            title = stringResource(R.string.grouppage_offline_title),
            subtitle = stringResource(R.string.grouppage_offline_body),
            icon = AppIcon.WifiOff,
            modifier = modifier,
        )
        GroupNotice.Failed -> Banner(
            title = stringResource(R.string.grouppage_failed_title),
            subtitle = stringResource(R.string.grouppage_failed_body),
            icon = AppIcon.Alert,
            modifier = modifier,
        )
        is GroupNotice.Reminded -> Banner(
            title = stringResource(R.string.grouppage_reminded_title),
            subtitle = stringResource(R.string.grouppage_reminded_body, namesText(notice.names)),
            icon = AppIcon.CheckCircle,
            modifier = modifier,
            tone = BannerTone.Plain,
        )
        GroupNotice.NotReminded -> Banner(
            title = stringResource(R.string.grouppage_not_reminded_title),
            subtitle = stringResource(R.string.grouppage_not_reminded_body),
            icon = AppIcon.Clock,
            modifier = modifier,
            tone = BannerTone.Plain,
        )
    }
}

/** "Rahul", "Rahul and Priya", "Rahul, Priya and Kiran". */
@Composable
internal fun namesText(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names.first()
    else -> stringResource(R.string.grouppage_names_and, names.dropLast(1).joinToString(", "), names.last())
}

/**
 * What an online-only action's result shows: the offline banner, the failure banner, or nothing (done, or Sign in
 * opens).
 */
internal fun noticeOf(result: ActionResult): GroupNotice? = when (result) {
    ActionResult.Done, ActionResult.SignedOut -> null
    ActionResult.Offline -> GroupNotice.Offline
    ActionResult.NotSettled, is ActionResult.Failed -> GroupNotice.Failed
}

/**
 * The letter in an avatar: the first character of the name as a reader sees it, in upper case, cut at a character
 * boundary so a letter made of several code units stays whole.
 */
internal fun initialOf(name: String): String {
    if (name.isEmpty()) return name
    val characters = BreakIterator.getCharacterInstance().apply { setText(name) }
    return name.substring(0, characters.following(0)).uppercase()
}

/** The first name: the name up to its first space ("Rahul" of "Rahul Menon"). */
internal fun firstNameOf(name: String): String = name.trim().substringBefore(' ').ifEmpty { name }
