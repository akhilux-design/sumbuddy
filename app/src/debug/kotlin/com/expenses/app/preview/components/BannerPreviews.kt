package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.Banner
import com.expenses.app.ui.components.BannerTone
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.PlaceholderCard
import com.expenses.app.ui.components.PlaceholderRow
import com.expenses.app.ui.components.PlaceholderRowCount
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.Space

/** The banners of 02-everyday/03, 40 and 41, and the loading placeholders of 22, to compare with the design. */
val bannerPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "banner") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            Banner(
                title = "You're offline",
                subtitle = "Your own expenses still work. Splits sync when you are back online.",
                icon = AppIcon.WifiOff,
            )
            Banner(title = "Priya joined", subtitle = "Not Priya?", icon = AppIcon.UserPlus) {
                AppButton(label = "Undo", onClick = {}, style = ButtonStyle.Secondary, size = ButtonSize.Medium)
            }
            Banner(
                title = "Join undone",
                subtitle = "Priya is back as not on the app. That link is now off.",
                icon = AppIcon.CheckCircle,
                tone = BannerTone.Plain,
            )
        }
    },
    PreviewEntry(name = "placeholders") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            PlaceholderCard(description = "Loading")
            repeat(PlaceholderRowCount) { PlaceholderRow(Modifier) }
        }
    },
)
