package com.expenses.app.ui.screens.signin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expenses.app.R
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.GoogleLabelFont
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/** The button's height in the design (52): above the 48dp touch target. */
private val GoogleButtonMinHeight = 52.dp

/** Google's "G" is 20dp, 10dp from the label. */
private val GoogleLogoSize = 20.dp
private val GoogleLogoGap = 10.dp

/** Outline of the button, drawn inside it. */
private val GoogleStroke = 1.dp

/**
 * The label in Roboto Medium 14/20, as Google's sign-in branding asks: [GoogleLabelFont], loaded through the Google
 * Fonts provider, because the system sans-serif is not Roboto on many phones.
 */
private val GoogleLabelStyle = TextStyle(fontFamily = GoogleLabelFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)

/**
 * "Continue with Google" (design `Google button` in 01-onboarding/01-sign-in): a full-width pill with Google's "G"
 * and the label, in the `color/google/...` colours from Google's Android branding guidelines for each theme.
 *
 * @param onClick called when tapped.
 * @param modifier layout changes from the caller.
 */
@Composable
fun GoogleButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(Radius.full)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = GoogleButtonMinHeight)
            .clip(shape)
            .background(colors.googleButtonFill)
            .border(BorderStroke(GoogleStroke, colors.googleButtonStroke), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s12, vertical = Space.s16),
        horizontalArrangement = Arrangement.spacedBy(GoogleLogoGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label names the button for screen readers; the logo is decoration.
        Image(painter = painterResource(R.drawable.ic_google_logo), contentDescription = null, modifier = Modifier.size(GoogleLogoSize))
        AppText(text = stringResource(R.string.signin_google), style = GoogleLabelStyle, color = colors.googleButtonText)
    }
}
