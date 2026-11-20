package com.expenses.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.components.core.EdgeHeight
import com.expenses.app.ui.components.core.RaisedSurface
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.icons.ArtIcon
import com.expenses.app.ui.motion.rememberSuccessEntrance
import com.expenses.app.ui.motion.successPop
import com.expenses.app.ui.theme.AppColors
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

// Pieces that several screens repeat but that are not components in the Figma file.
// Sizes are taken from the screen dumps in design/spec/screens (Home, Activity, Groups, You, Privacy promise).

private val MinTouchTarget = 48.dp
private val OutlineWidth = 1.5.dp
private val StickerFaceSize = 72.dp
private val StickerBorderWidth = 2.dp
private val StickerIconSize = 32.dp
private val EmptyStateIconSize = 40.dp

/**
 * The upper-case heading above a group of rows, for example TODAY, TRACKING or PRIVACY: text style `Label`
 * in `color/text/secondary`. It has no padding of its own; the screen's 12dp gap separates it from the rows.
 *
 * @param text the heading as written, for example "Today". It is shown in upper case and read out as written.
 * @param modifier outer modifier.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val style = AppTheme.type.label
    AppText(
        text = text.uppercase(),
        style = style,
        color = AppTheme.colors.textSecondary,
        modifier = modifier
            .semantics {
                heading()
                contentDescription = text
            },
    )
}

/**
 * The header at the top of a main tab, as on Activity and Groups: the tab's title in `Display/L` on the left and a
 * Secondary Medium button on the right (the design's Header frame: space between, no gap, centred). The title
 * takes the room the button leaves and wraps when it does not fit. Screen readers announce it as a heading.
 *
 * @param title name of the tab, for example "Activity".
 * @param actionLabel text of the button, for example "+ Add".
 * @param onAction called when the button is tapped.
 * @param modifier outer modifier. The header fills the width it is given.
 */
@Composable
fun TabHeader(title: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val style = AppTheme.type.displayL
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppText(
            text = title,
            style = style,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            color = AppTheme.colors.textPrimary,
        )
        AppButton(label = actionLabel, onClick = onAction, style = ButtonStyle.Secondary, size = ButtonSize.Medium)
    }
}

/**
 * The two looks of a [SurfaceCard]. Both are flat, radius 16, filled with `color/bg/surface`, with no border.
 */
enum class SurfaceCardStyle {
    /** Holds rows, for example the Info Rows under PRIVACY on You: 12dp padding all round, 12dp between rows. */
    List,

    /**
     * Holds a short paragraph, for example the note on Privacy promise: 16dp padding left and right,
     * 12dp top and bottom, no gap between children.
     */
    Note,
}

/**
 * The rounded container on `color/bg/surface` that holds rows or a short note. It fills the width it is given
 * and is as tall as its content.
 *
 * @param modifier outer modifier.
 * @param onClick called when the card is tapped, or null when the card itself does nothing on tap. A tappable
 * card is at least 48dp tall and is announced as a button, with its content read as one item.
 * @param style the padding and gap to use: [SurfaceCardStyle.List] for rows, [SurfaceCardStyle.Note] for text.
 * @param content the rows or text, stacked top to bottom.
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    style: SurfaceCardStyle = SurfaceCardStyle.List,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tappable = if (onClick == null) {
        Modifier
    } else {
        Modifier.defaultMinSize(minHeight = MinTouchTarget).clickable(role = Role.Button, onClick = onClick)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(AppTheme.colors.bgSurface)
            .then(tappable)
            .padding(style.contentPadding),
        verticalArrangement = Arrangement.spacedBy(style.gap),
        content = content,
    )
}

private val SurfaceCardStyle.contentPadding: PaddingValues
    get() = when (this) {
        SurfaceCardStyle.List -> PaddingValues(Space.s12)
        SurfaceCardStyle.Note -> PaddingValues(horizontal = Space.s16, vertical = Space.s12)
    }

private val SurfaceCardStyle.gap: Dp
    get() = when (this) {
        SurfaceCardStyle.List -> Space.s12
        SurfaceCardStyle.Note -> Space.s0
    }

/** The colour of a [StickerTile]: a soft fill, with the border and the band under it in the same colour family. */
enum class StickerTone {
    /** Green, the usual one: the lock on Privacy promise and the tick on success screens. */
    Brand,

    /** Yellow: an offer or suggestion, for example the recurring split offer. */
    Yellow,

    /** Pink: Family. */
    Pink,
}

/**
 * The raised square tile with a 3D icon that marks the top of a screen, for example the lock on Privacy promise.
 * The face is 72dp square with radius 24 and a 2dp border, the icon is 32dp, and the 5dp band sits under the face
 * (77dp tall in all). It is decoration: the heading next to it says what the screen is about.
 *
 * On a result screen pass [arrival]: about 100 ms after the screen arrives the sticker pops from 50% and fades in,
 * and the confirm haptic plays, once per arrival ([rememberSuccessEntrance]).
 *
 * @param icon the 3D icon shown in the middle.
 * @param tone the colour family of the fill, border and band.
 * @param modifier outer modifier.
 * @param arrival true on a success screen (Expense added, Split added, Balance cleared, Group imported, All sorted).
 */
@Composable
fun StickerTile(icon: AppIcon, tone: StickerTone, modifier: Modifier = Modifier, arrival: Boolean = false) {
    val colors = tone.colors(AppTheme.colors)
    val pop = if (arrival) Modifier.successPop(rememberSuccessEntrance()) else Modifier
    RaisedSurface(
        faceColor = colors.face,
        edgeColor = colors.edge,
        modifier = modifier.then(pop).size(width = StickerFaceSize, height = StickerFaceSize + EdgeHeight),
        cornerRadius = Radius.xl,
        border = BorderStroke(StickerBorderWidth, colors.border),
    ) {
        ArtIcon(icon = icon, size = StickerIconSize)
    }
}

/** The three colours one sticker tone needs: the soft face, its border, and the band under it. */
@Immutable
private class StickerColors(val face: Color, val border: Color, val edge: Color)

private fun StickerTone.colors(theme: AppColors): StickerColors = when (this) {
    StickerTone.Brand -> StickerColors(theme.brandSoft, theme.brandPrimary, theme.brandEdge)
    StickerTone.Yellow -> StickerColors(theme.accentYellowSoft, theme.accentYellow, theme.accentYellowEdge)
    StickerTone.Pink -> StickerColors(theme.accentPinkSoft, theme.accentPink, theme.accentPinkEdge)
}

/**
 * The centred block shown where a list would be when there is nothing to list: a 40dp 3D icon, a title,
 * one or two lines of explanation and, if given, a button. It sits in an outlined card (radius 24,
 * `color/bg/surface`, 1.5dp `color/border/subtle` border, 24dp padding, 12dp between the parts) that fills
 * the width it is given.
 *
 * @param icon the 3D icon at the top.
 * @param title the short headline, for example "Nothing here yet".
 * @param body the explanation under the title.
 * @param modifier outer modifier.
 * @param action the button under the text, or null for none. Pass a button that is as wide as its label;
 * it is centred.
 */
@Composable
fun EmptyState(
    icon: AppIcon,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Radius.xl)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppTheme.colors.bgSurface, shape)
            .border(OutlineWidth, AppTheme.colors.borderSubtle, shape)
            // In the design the border takes up room, so the content starts 24dp inside the border.
            .padding(Space.s24 + OutlineWidth),
        verticalArrangement = Arrangement.spacedBy(Space.s12),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtIcon(icon = icon, size = EmptyStateIconSize)
        AppText(
            text = title,
            style = AppTheme.type.headingL,
            modifier = Modifier
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        AppText(
            text = body,
            style = AppTheme.type.bodyM,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (action != null) action()
    }
}
