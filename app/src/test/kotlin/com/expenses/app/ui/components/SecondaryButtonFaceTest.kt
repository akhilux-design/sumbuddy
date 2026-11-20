package com.expenses.app.ui.components

import androidx.compose.ui.graphics.Color
import com.expenses.app.ui.theme.AppColors
import com.expenses.app.ui.theme.DarkColors
import com.expenses.app.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A Secondary button must not be the colour of the grey card it sits on, or it reads as disabled
 * (Home's payment card, 02-everyday/00, both themes).
 */
class SecondaryButtonFaceTest {
    private val themes = listOf(DarkColors, LightColors)

    @Test fun onAGreyCardTheFaceIsOnRaised() {
        themes.forEach { assertEquals(it.bgOnRaised, it.faceFor(ButtonStyle.Secondary, onRaised = true)) }
    }

    @Test fun onAGreyCardTheFaceNeverMatchesTheCard() {
        themes.forEach { assertNotEquals(it.bgSurfaceRaised, it.faceFor(ButtonStyle.Secondary, onRaised = true)) }
    }

    @Test fun theFacesAreTheDrawnColours() {
        // color/bg/on-raised: neutral/50 in Light (as Home's card was drawn), neutral/700 in Dark.
        assertEquals(Color(0xFFF5F7F5), LightColors.bgOnRaised)
        assertEquals(Color(0xFF2B322D), DarkColors.bgOnRaised)
    }

    @Test fun elsewhereTheFaceStaysGrey() {
        themes.forEach { assertEquals(it.bgSurfaceRaised, it.faceFor(ButtonStyle.Secondary, onRaised = false)) }
    }

    @Test fun otherStylesDoNotChange() {
        themes.forEach { colors: AppColors ->
            assertEquals(colors.brandPrimary, colors.faceFor(ButtonStyle.Primary, onRaised = true))
            assertEquals(Color.Transparent, colors.faceFor(ButtonStyle.Ghost, onRaised = true))
        }
    }
}
