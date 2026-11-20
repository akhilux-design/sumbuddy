package com.expenses.app.preview.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.BottomSheet
import com.expenses.app.ui.components.ChoiceDialog
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.icons.AppIcon

/** The confirm dialog (04-you/03) and the bottom sheet (02-everyday/46) over an empty page, to compare with the design. */
val overlayPreviews: List<PreviewEntry> = listOf(
    PreviewEntry(name = "dialog", fullScreen = true) {
        PageFrame(title = "Account", onBack = {}) {}
        ChoiceDialog(
            icon = AppIcon.Trash,
            title = "Delete your account and all data? This cannot be undone.",
            body = "Groups only you use are deleted. In shared groups your name becomes “Former member”, so everyone’s balances stay right.",
            confirmLabel = "Delete everything",
            dismissLabel = "Keep my account",
            onConfirm = {},
            onDismiss = {},
        )
    },
    PreviewEntry(name = "sheet", fullScreen = true) {
        Box(modifier = Modifier.fillMaxSize()) {
            PageFrame(title = "Group settings", onBack = {}) {}
            BottomSheet(title = "Priya Nair", onDismiss = {}) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AppButton(label = "Turn off link", onClick = {}, modifier = Modifier.fillMaxWidth())
                    AppButton(
                        label = "Cancel",
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Ghost,
                        size = ButtonSize.Medium,
                    )
                }
            }
        }
    },
)
