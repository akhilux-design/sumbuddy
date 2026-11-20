package com.expenses.app.ui.screens.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.TopBar
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.screens.onboarding.OnboardingBody
import com.expenses.app.ui.screens.onboarding.OnboardingFrame
import com.expenses.app.ui.screens.onboarding.OnboardingHeadline
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/** Why the last sign-in did not work, shown in the note above the explanation. */
enum class SignInProblem {
    /** "Could not sign in" (design 02-sign-in-could-not-sign-in). */
    FAILED,

    /** Debug builds only: no Google client id in this build. Not in the design. */
    NOT_CONFIGURED,
}

/**
 * What Sign in shows.
 *
 * @param problem the note about the last attempt, or null for none.
 * @param busy true while a sign-in runs; taps wait for it.
 * @param devSignIn true in debug builds, which offer "Dev sign-in" against the local server.
 * @param forJoin true when a friend's link opened Sign in (02-everyday/49): "Join a group", its reason and "You were
 * invited" in place of "Split with friends", its reason and "Groups and balances".
 * @param forFamily true from Set up Family or Family settings (06-family/08): "Share a family month", its reason,
 * "Family month" and the family's "Personal stays private".
 */
@Immutable
data class SignInUiState(
    val problem: SignInProblem? = null,
    val busy: Boolean = false,
    val devSignIn: Boolean = false,
    val forJoin: Boolean = false,
    val forFamily: Boolean = false,
)

/** What the person does on Sign in. */
sealed interface SignInEvent {
    /** "Continue with Google". */
    data object ContinueWithGoogle : SignInEvent

    /** "Not now", or Back. */
    data object NotNow : SignInEvent

    /** Debug builds: "Dev sign-in" with [name]. */
    data class DevSignIn(val name: String) : SignInEvent
}

/** Stroke of the "Could not sign in" note: red, so the state also reads from the alert icon and title. */
private val ErrorNoteStroke = 1.5.dp

/**
 * Sign in (design 01-onboarding/01-sign-in, and 02-sign-in-could-not-sign-in when [SignInUiState.problem] is set; for
 * a friend's link 02-everyday/49-join-group-sign-in-first when [SignInUiState.forJoin] is set; for the family
 * 06-family/08-sign-in-family when [SignInUiState.forFamily] is set). Asked only when
 * splitting first needs it: Groups, Split on a payment, You, a friend's link, or Welcome's "I already have an account".
 * Google's account sheet may also appear by itself when the screen opens; the button stays for phones without a
 * Google account.
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param modifier layout changes from the caller.
 */
@Composable
fun SignInScreen(state: SignInUiState, onEvent: (SignInEvent) -> Unit, modifier: Modifier = Modifier) {
    OnboardingFrame(
        modifier = modifier,
        topBar = { TopBar(title = stringResource(R.string.signin_title), onBack = { onEvent(SignInEvent.NotNow) }) },
        footer = {
            GoogleButton(onClick = { onEvent(SignInEvent.ContinueWithGoogle) })
            AppButton(
                label = stringResource(R.string.signin_not_now),
                onClick = { onEvent(SignInEvent.NotNow) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
            if (state.devSignIn) DevSignInEntry(onSignIn = { onEvent(SignInEvent.DevSignIn(it)) })
        },
    ) {
        val copy = when {
            state.forJoin -> SignInCopy.Join
            state.forFamily -> SignInCopy.Family
            else -> SignInCopy.Groups
        }
        OnboardingHeadline(text = stringResource(copy.headline))
        OnboardingBody(text = stringResource(copy.reason))
        state.problem?.let { SignInErrorNote(it) }
        // The first line says what is kept in the account; the second what stays private.
        when (copy) {
            SignInCopy.Groups -> InfoRow(
                title = stringResource(R.string.signin_groups_title),
                subtitle = stringResource(R.string.signin_groups_body),
                icon = AppIcon.Users,
            )
            SignInCopy.Join -> InfoRow(
                title = stringResource(R.string.signin_invited_title),
                subtitle = stringResource(R.string.signin_invited_body),
                icon = AppIcon.UserPlus,
            )
            SignInCopy.Family -> InfoRow(
                title = stringResource(R.string.signin_family_kept_title),
                subtitle = stringResource(R.string.signin_family_kept_body),
                icon = AppIcon.Users,
            )
        }
        InfoRow(
            title = stringResource(if (copy == SignInCopy.Family) R.string.signin_family_private_title else R.string.signin_private_title),
            subtitle = stringResource(if (copy == SignInCopy.Family) R.string.signin_family_private_body else R.string.signin_private_body),
            icon = AppIcon.Lock,
        )
    }
}

/** Which reason for signing in the screen gives: splitting (01), a friend's link (49) or the family (06-family/08). */
private enum class SignInCopy(@StringRes val headline: Int, @StringRes val reason: Int) {
    Groups(R.string.signin_headline, R.string.signin_reason),
    Join(R.string.signin_join_headline, R.string.signin_join_reason),
    Family(R.string.signin_family_headline, R.string.signin_family_reason),
}

/**
 * The design's `Error note`: an Info Row with the alert icon and the title "Could not sign in" inside a surface card
 * with a red outline. Announced to screen readers when it appears.
 */
@Composable
private fun SignInErrorNote(problem: SignInProblem) {
    val shape = RoundedCornerShape(Radius.lg)
    val (title, body) = when (problem) {
        SignInProblem.FAILED -> R.string.signin_failed_title to R.string.signin_failed_body
        SignInProblem.NOT_CONFIGURED -> R.string.signin_not_configured_title to R.string.signin_not_configured_body
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppTheme.colors.bgSurface)
            .border(ErrorNoteStroke, AppTheme.colors.statusNegative, shape)
            .padding(Space.s12)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        InfoRow(title = stringResource(title), subtitle = stringResource(body), icon = AppIcon.Alert)
    }
}
