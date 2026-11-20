package com.expenses.app.ui.screens.settle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.expenses.app.R
import com.expenses.app.ui.components.Avatar
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.components.Banner
import com.expenses.app.ui.components.BannerTone
import com.expenses.app.ui.components.Tag
import com.expenses.app.ui.components.TagTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.nav.Home
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

// Pieces the settle-up screens share (design 03-getting-paid-back/00 to 03, 05, 07).

/** The avatar on Settle up (00, 07): 64dp. */
internal val SettleLargeAvatar: Dp = 64.dp

/** The avatar on Mark as paid (01): the list size, 40dp. */
internal val SettleSmallAvatar: Dp = 40.dp

/**
 * The top of Settle up and Mark as paid: the friend's avatar, who owes whom ("You owe Rahul", "Rahul paid you" in
 * Heading/M), the amount in Amount/XL and a Neutral Tag ("4 expenses · Flat 302"), centred, 8dp apart, with 16dp
 * above and below. Screen readers read it as one item.
 *
 * @param initial the friend's initial.
 * @param avatarColor the friend's colour, the same as on the Group page ([com.expenses.app.ui.components.AvatarColors]).
 * @param avatarSize 64dp on Settle up, 40dp on Mark as paid.
 * @param heading who owes whom.
 * @param amount the amount, formatted.
 * @param tag the tag's text, or null for none.
 */
@Composable
internal fun SettleAmountHeader(initial: String, avatarColor: AvatarColor, avatarSize: Dp, heading: String, amount: String, tag: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(vertical = Space.s16),
        verticalArrangement = Arrangement.spacedBy(Space.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(initial = initial, color = avatarColor, size = avatarSize)
        AppText(text = heading, style = AppTheme.type.headingM, color = AppTheme.colors.textSecondary, textAlign = TextAlign.Center)
        AppText(text = amount, style = AppTheme.type.amountXL, color = AppTheme.colors.textPrimary, maxLines = 1)
        if (tag != null) Tag(label = tag, tone = TagTone.Neutral)
    }
}

/** Why an online-only action did not happen (docs/groups-build-plan.md section 1). */
enum class SettleProblem {
    /** No connection: "You're offline" / "Check your connection and try again. Nothing is lost.". */
    Offline,

    /** The server refused it for a reason the screen does not name: "Could not do that now". */
    Failed,
}

/**
 * The banner at the top of the body for [problem], in the Notice tone: offline with the wifi-off icon, as Home's
 * offline banner; any other failure with the alert icon, as the Group page's and Join's "Could not do that now".
 */
@Composable
internal fun SettleProblemBanner(problem: SettleProblem) {
    when (problem) {
        SettleProblem.Offline -> Banner(
            title = stringResource(R.string.settle_offline_title),
            subtitle = stringResource(R.string.settle_offline_body),
            icon = AppIcon.WifiOff,
            tone = BannerTone.Notice,
        )
        SettleProblem.Failed -> Banner(
            title = stringResource(R.string.settle_failed_title),
            subtitle = stringResource(R.string.settle_failed_body),
            icon = AppIcon.Alert,
            tone = BannerTone.Notice,
        )
    }
}

/** The first letter of [name] in capitals, as avatars show it ("rahul" gives "R"); "?" for a blank name. */
internal fun initialOf(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val end = trimmed.offsetByCodePoints(0, 1)
    return trimmed.substring(0, end).uppercase()
}

/**
 * One-shot messages from a settle-up ViewModel to its screen (open the UPI app, send on WhatsApp). Each is delivered
 * once; messages sent while no screen listens wait until one does.
 */
internal class SettleEffects<T> {
    private val channel = Channel<T>(Channel.BUFFERED)

    /** The messages, for [CollectSettleEffects]. */
    val flow: Flow<T> = channel.receiveAsFlow()

    /** Sends [effect] to the screen. */
    fun send(effect: T) {
        channel.trySend(effect)
    }
}

/** Hands each message of [effects] to [onEffect] while this composable is shown. */
@Composable
internal fun <T> CollectSettleEffects(effects: Flow<T>, onEffect: (T) -> Unit) {
    val current by rememberUpdatedState(onEffect)
    LaunchedEffect(effects) {
        effects.collect { current(it) }
    }
}

/** Back to Home, removing the screens above it; when Home is not on the stack, back once. */
internal fun NavController.backToHome() {
    if (!popBackStack<Home>(inclusive = false)) popBackStack()
}
