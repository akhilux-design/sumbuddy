package com.expenses.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.expenses.app.R

// The design uses two fonts: Bricolage Grotesque for display text and amounts, DM Sans for everything else. Both are
// bundled in res/font (static instances from Google Fonts, SIL Open Font License 1.1; the licence texts are in
// assets/licenses), so they show at once and on phones without Google Play services.
val DisplayFont: FontFamily = FontFamily(
    Font(R.font.bricolage_grotesque_bold, FontWeight.Bold),
    Font(R.font.bricolage_grotesque_extrabold, FontWeight.ExtraBold),
)
val BodyFont: FontFamily = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
    Font(R.font.dm_sans_semibold, FontWeight.SemiBold),
    Font(R.font.dm_sans_bold, FontWeight.Bold),
)

/**
 * Roboto Medium, for the "Continue with Google" label only: Google's sign-in branding asks for it, and the design
 * says "Roboto Medium 14". It is bundled like the other fonts rather than taken as the system sans-serif, which many
 * phones sold in India replace (MiSans, OnePlus Sans, Samsung's FlipFont).
 */
val GoogleLabelFont: FontFamily = FontFamily(Font(R.font.roboto_medium, FontWeight.Medium))

private val lineBox = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(font: FontFamily, weight: FontWeight, size: Int, line: Int, trackingPercent: Double = 0.0) = TextStyle(
    fontFamily = font,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = (trackingPercent / 100.0).em,
    lineHeightStyle = lineBox,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Text styles, one per Figma text style (`Heading/M` is [headingM]). Values come from design/spec/typography.json. */
@Immutable
data class AppTypography(
    val displayXL: TextStyle = style(DisplayFont, FontWeight.ExtraBold, 44, 46, -2.0),
    val displayL: TextStyle = style(DisplayFont, FontWeight.ExtraBold, 32, 36, -1.5),
    val amountXL: TextStyle = style(DisplayFont, FontWeight.ExtraBold, 52, 56, -2.0),
    val amountL: TextStyle = style(DisplayFont, FontWeight.ExtraBold, 32, 36, -1.0),
    val amountM: TextStyle = style(DisplayFont, FontWeight.Bold, 18, 22),
    val headingL: TextStyle = style(DisplayFont, FontWeight.Bold, 24, 28, -1.0),
    val headingM: TextStyle = style(DisplayFont, FontWeight.Bold, 20, 24, -0.5),
    val title: TextStyle = style(BodyFont, FontWeight.SemiBold, 16, 22),
    val bodyL: TextStyle = style(BodyFont, FontWeight.Normal, 16, 24),
    val bodyM: TextStyle = style(BodyFont, FontWeight.Normal, 14, 20),
    val bodyMStrong: TextStyle = style(BodyFont, FontWeight.SemiBold, 14, 20),
    val caption: TextStyle = style(BodyFont, FontWeight.Medium, 14, 18),
    val button: TextStyle = style(BodyFont, FontWeight.Bold, 16, 20),
    /** Section labels. The design shows these in upper case: pass the text through `uppercase()`. */
    val label: TextStyle = style(BodyFont, FontWeight.Bold, 14, 16, 6.0),
)
