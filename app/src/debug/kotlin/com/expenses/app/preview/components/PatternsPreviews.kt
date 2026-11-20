package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.EmptyState
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.components.SurfaceCardStyle
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/**
 * Preview "patterns": the shared screen pieces of Patterns.kt, top to bottom, with the text of the screens
 * they were taken from:
 * - [SectionLabel] and a list [SurfaceCard], as under PRIVACY on You (`04-you/00-you`);
 * - a note [SurfaceCard], as on Privacy promise (`01-onboarding/05-privacy-promise`);
 * - [StickerTile] in its three tones: Brand, Yellow, Pink;
 * - [EmptyState] with a Secondary button, as on Activity (empty) (`02-everyday/17-activity-empty`);
 * - [EmptyState] with a Primary button, as on Groups (empty) (`02-everyday/21-groups-empty`);
 * - [EmptyState] without a button. No screen draws this case; the text is from Home (start fresh).
 * The list scrolls, because it is taller than one screen.
 */
val patternsPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "patterns") {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.s16),
        ) {
            SectionLabel(text = "Privacy")
            SurfaceCard {
                InfoRow(
                    title = "What we've read",
                    subtitle = "214 messages · 0 sent off this phone",
                    icon = AppIcon.Eye,
                )
                InfoRow(title = "See the code", subtitle = "The SMS reader is public", icon = AppIcon.Receipt)
            }
            SurfaceCard(style = SurfaceCardStyle.Note) {
                AppText(
                    text = "Android will ask for SMS permission next. You can switch it off any time in Settings.",
                    style = AppTheme.type.caption,
                    color = AppTheme.colors.textSecondary,
                )
            }
            // Four 72dp tiles with 12dp between them are 324dp wide, which fits a 360dp screen.
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                StickerTile(icon = AppIcon.CheckCircle, tone = StickerTone.Brand)
                StickerTile(icon = AppIcon.Clock, tone = StickerTone.Yellow)
                StickerTile(icon = AppIcon.Users, tone = StickerTone.Pink)
            }
            EmptyState(
                icon = AppIcon.Receipt,
                title = "Nothing here yet",
                body = "Payments you add or split are listed here, newest first.",
                action = {
                    AppButton(
                        label = "Add one by hand",
                        onClick = {},
                        style = ButtonStyle.Secondary,
                        size = ButtonSize.Medium,
                    )
                },
            )
            EmptyState(
                icon = AppIcon.Users,
                title = "No groups yet",
                body = "Make a group for your flat, a trip or an office lunch. Friends do not need the app.",
                action = {
                    AppButton(label = "New group", onClick = {}, style = ButtonStyle.Primary, size = ButtonSize.Medium)
                },
            )
            EmptyState(
                icon = AppIcon.Sprout,
                title = "Fresh start",
                body = "When you pay for something, we'll spot it and ask what to do with it.",
            )
        }
    },
)
