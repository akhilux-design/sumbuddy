package com.expenses.app.preview

import androidx.compose.runtime.Composable
import com.expenses.app.preview.components.appButtonPreviews
import com.expenses.app.preview.components.appChipPreviews
import com.expenses.app.preview.components.appInputPreviews
import com.expenses.app.preview.components.appSwitchPreviews
import com.expenses.app.preview.components.avatarPreviews
import com.expenses.app.preview.components.balanceCardPreviews
import com.expenses.app.preview.components.balanceRowPreviews
import com.expenses.app.preview.components.bannerPreviews
import com.expenses.app.preview.components.bottomNavPreviews
import com.expenses.app.preview.components.expenseRowPreviews
import com.expenses.app.preview.components.infoRowPreviews
import com.expenses.app.preview.components.optionCardPreviews
import com.expenses.app.preview.components.overlayPreviews
import com.expenses.app.preview.components.patternsPreviews
import com.expenses.app.preview.components.segmentedTogglePreviews
import com.expenses.app.preview.components.settingRowPreviews
import com.expenses.app.preview.components.statTilePreviews
import com.expenses.app.preview.components.tagPreviews
import com.expenses.app.preview.components.topBarPreviews
import com.expenses.app.preview.screens.activityScreenPreviews
import com.expenses.app.preview.screens.familyScreenPreviews
import com.expenses.app.preview.screens.groupPageScreenPreviews
import com.expenses.app.preview.screens.groupsScreenPreviews
import com.expenses.app.preview.screens.homeScreenPreviews
import com.expenses.app.preview.screens.joinScreenPreviews
import com.expenses.app.preview.screens.onboardingScreenPreviews
import com.expenses.app.preview.screens.paymentScreenPreviews
import com.expenses.app.preview.screens.reviewScreenPreviews
import com.expenses.app.preview.screens.settleScreenPreviews
import com.expenses.app.preview.screens.signInScreenPreviews
import com.expenses.app.preview.screens.splitScreenPreviews
import com.expenses.app.preview.screens.splitwiseScreenPreviews
import com.expenses.app.preview.screens.youScreenPreviews

/**
 * One thing that can be shown alone in [PreviewActivity].
 *
 * @param name lower-case id used on the command line, for example "button" or "screen-home".
 * @param fullScreen true for whole screens, which draw under the system bars themselves. Components are shown
 * inside padding on the canvas colour.
 * @param content what the preview draws.
 */
class PreviewEntry(val name: String, val fullScreen: Boolean = false, val content: @Composable () -> Unit)

/** Every preview in the debug build. Each feature file exposes a list; add it to [entries]. */
object PreviewRegistry {
    val entries: List<PreviewEntry> by lazy {
        listOf(
            // Components
            appButtonPreviews,
            appChipPreviews,
            appInputPreviews,
            appSwitchPreviews,
            avatarPreviews,
            balanceCardPreviews,
            balanceRowPreviews,
            bannerPreviews,
            bottomNavPreviews,
            expenseRowPreviews,
            infoRowPreviews,
            optionCardPreviews,
            overlayPreviews,
            patternsPreviews,
            segmentedTogglePreviews,
            settingRowPreviews,
            statTilePreviews,
            tagPreviews,
            topBarPreviews,
            // Screens
            activityScreenPreviews,
            familyScreenPreviews,
            groupPageScreenPreviews,
            groupsScreenPreviews,
            homeScreenPreviews,
            joinScreenPreviews,
            onboardingScreenPreviews,
            paymentScreenPreviews,
            reviewScreenPreviews,
            settleScreenPreviews,
            signInScreenPreviews,
            splitScreenPreviews,
            splitwiseScreenPreviews,
            youScreenPreviews,
        ).flatten().sortedBy { it.name }
    }

    /** Returns the entry called [name], or null when there is none, so the list of previews is shown instead. */
    fun find(name: String?): PreviewEntry? = entries.firstOrNull { it.name == name }
}
