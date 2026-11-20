package com.expenses.app.ui.screens.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.ExpenseRow
import com.expenses.app.ui.components.ExpenseRowKind
import com.expenses.app.ui.components.Tag
import com.expenses.app.ui.components.TagTone
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.core.EdgeHeight
import com.expenses.app.ui.components.core.RaisedSurface
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalReducedMotion
import com.expenses.app.ui.theme.Motion
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Height of the illustration at the top of Welcome. */
private val HeroHeight = 270.dp

/** Diameter of the pink rupee coin in the illustration. */
private val CoinSize = 60.dp

/** The "No typing" badge is the Tag component drawn 1.7 times its size, as in the design. */
private const val HeroTagScale = 1.7f

/** Amounts on the three illustration cards, in paise. */
private const val SampleFoodMinor = 45_000L
private const val SampleMonthMinor = 1_824_000L
private const val SampleRentMinor = 800_000L

/**
 * Welcome (design 01-onboarding/00-welcome): a tilted stack of sample expense cards, the headline, what the app
 * does, Get started, and "I already have an account", which opens Sign in (onboarding itself has no sign-in).
 *
 * @param onGetStarted called when Get started is tapped.
 * @param modifier layout changes from the caller.
 * @param onHaveAccount called when "I already have an account" is tapped.
 */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit, modifier: Modifier = Modifier, onHaveAccount: () -> Unit = {}) {
    OnboardingFrame(
        modifier = modifier,
        bodyGap = Space.s32,
        centerBody = true,
        footer = {
            AppButton(
                label = stringResource(R.string.onboarding_welcome_start),
                onClick = onGetStarted,
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                label = stringResource(R.string.onboarding_welcome_sign_in),
                onClick = onHaveAccount,
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        WelcomeHero()
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            val colors = AppTheme.colors
            val start = stringResource(R.string.onboarding_welcome_headline_start)
            val end = stringResource(R.string.onboarding_welcome_headline_end)
            AppText(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.textPrimary)) { append(start) }
                    withStyle(SpanStyle(color = colors.textBrand)) { append(end) }
                },
                style = AppTheme.type.displayXL,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            AppText(
                text = stringResource(R.string.onboarding_welcome_body, stringResource(R.string.app_name)),
                style = AppTheme.type.bodyL,
                modifier = Modifier.fillMaxWidth(),
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * The illustration: three sample expense cards at slight angles, the yellow "No typing" badge and the pink rupee
 * coin. Positions and angles are the design's, each turned about its top left corner as Figma does. It is
 * decoration, so screen readers skip it.
 */
@Composable
private fun WelcomeHero() {
    // The three sample rows rise into place, 60 ms apart, the first time Welcome shows (not again after a rotation).
    val played = rememberSaveable { mutableStateOf(false) }
    val rises = List(SAMPLE_ROWS) { index -> rememberRise(index, played.value) }
    LaunchedEffect(Unit) { played.value = true }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HeroHeight)
            .clearAndSetSemantics {},
    ) {
        ExpenseRow(
            title = stringResource(R.string.onboarding_welcome_sample_food_title),
            subtitle = stringResource(R.string.onboarding_welcome_sample_food_subtitle),
            amount = formatRupees(SampleFoodMinor),
            meta = stringResource(R.string.onboarding_welcome_sample_food_meta),
            icon = AppIcon.Food,
            modifier = Modifier.fillMaxWidth().rise(rises[0]).tilted(x = 0.dp, y = 74.dp, degrees = 5f),
        )
        ExpenseRow(
            title = stringResource(R.string.onboarding_welcome_sample_month_title),
            subtitle = stringResource(R.string.onboarding_welcome_sample_month_subtitle),
            amount = formatRupees(SampleMonthMinor),
            meta = stringResource(R.string.onboarding_welcome_sample_month_meta),
            icon = AppIcon.Chart,
            modifier = Modifier.fillMaxWidth().rise(rises[1]).tilted(x = 6.dp, y = 126.dp, degrees = -4f),
        )
        ExpenseRow(
            title = stringResource(R.string.onboarding_welcome_sample_rent_title),
            subtitle = stringResource(R.string.onboarding_welcome_sample_rent_subtitle),
            amount = formatRupees(SampleRentMinor),
            meta = stringResource(R.string.onboarding_welcome_sample_rent_meta),
            icon = AppIcon.Home,
            kind = ExpenseRowKind.Shared,
            modifier = Modifier.fillMaxWidth().rise(rises[2]).tilted(x = 0.dp, y = 218.dp, degrees = 2f),
        )
        Tag(
            label = stringResource(R.string.onboarding_welcome_tag),
            tone = TagTone.Yellow,
            modifier = Modifier.tilted(x = 177.dp, y = (-6).dp, degrees = -8f, scale = HeroTagScale),
        )
        RaisedSurface(
            faceColor = AppTheme.colors.accentPink,
            edgeColor = AppTheme.colors.accentPinkEdge,
            modifier = Modifier
                .offset(x = Space.s24)
                .size(width = CoinSize, height = CoinSize + EdgeHeight),
            cornerRadius = Radius.full,
        ) {
            AppText(
                text = stringResource(R.string.onboarding_welcome_coin),
                style = AppTheme.type.headingL,
                color = AppTheme.colors.textOnBrand,
            )
        }
    }
}

/**
 * Places an illustration piece at ([x], [y]) and turns it by [degrees] about its top left corner, the way Figma
 * stores a rotated layer. Figma counts degrees anticlockwise; Compose counts them clockwise.
 */
private fun Modifier.tilted(x: Dp, y: Dp, degrees: Float, scale: Float = 1f): Modifier =
    offset(x = x, y = y).graphicsLayer {
        transformOrigin = TransformOrigin(0f, 0f)
        rotationZ = -degrees
        scaleX = scale
        scaleY = scale
    }

/** How many sample rows rise in. */
private const val SAMPLE_ROWS = 3

/** The sample rows start this far below their place. */
private val RiseDistance = 16.dp

/** The sample rows start this long apart. */
private const val RISE_STAGGER_MILLIS = 60L

/** One sample row's rise: [offset] 1 to 0 (of [RiseDistance]) on `motion/spring/screen`, [alpha] 0 to 1 over 250 ms. */
private class Rise(val offset: Animatable<Float, *>, val alpha: Animatable<Float, *>)

/**
 * The rise of the sample row at [index]: it starts [index] x 60 ms after Welcome shows. Already [played], or with Remove
 * animations on, the row is simply in place.
 */
@Composable
private fun rememberRise(index: Int, played: Boolean): Rise {
    val still = played || LocalReducedMotion.current
    val rise = remember { Rise(Animatable(if (still) 0f else 1f), Animatable(if (still) 1f else 0f)) }
    LaunchedEffect(Unit) {
        if (rise.alpha.value < 1f) {
            delay(index * RISE_STAGGER_MILLIS)
            launch { rise.alpha.animateTo(1f, Motion.fade(Motion.ResultFade.InMillis, easing = Motion.Easings.Enter)) }
            rise.offset.animateTo(0f, Motion.Spring.Screen.spec(RISE_THRESHOLD))
        }
    }
    return rise
}

/** The rise's offset stops within this fraction of its distance. */
private const val RISE_THRESHOLD = 0.001f

/** Draws this row where [rise] has it: moved down and faded, read in the layer only. */
private fun Modifier.rise(rise: Rise): Modifier = graphicsLayer {
    translationY = rise.offset.value * RiseDistance.toPx()
    alpha = rise.alpha.value
}
