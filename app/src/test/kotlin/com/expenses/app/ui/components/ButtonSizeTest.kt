package com.expenses.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A Secondary button is the same size as a Primary or Ghost one with the same label: its outline sits inside the face
 * (Button.md; owner: "why is the secondary button bigger than other buttons?", 2026-11-19).
 */
@RunWith(RobolectricTestRunner::class)
class ButtonSizeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun showAll(size: ButtonSize) {
        compose.setContent {
            AppTheme(dark = false) {
                Column {
                    ButtonStyle.entries.forEach { style ->
                        AppButton(label = "Split", onClick = {}, modifier = Modifier.testTag(style.name), style = style, size = size)
                    }
                }
            }
        }
    }

    private fun assertSameSize(expectedHeight: Dp) {
        val primary = bounds(ButtonStyle.Primary.name)
        ButtonStyle.entries.forEach { style ->
            val b = bounds(style.name)
            assertEquals("$style height", primary.height.value, b.height.value, 0.01f)
            assertEquals("$style width", primary.width.value, b.width.value, 0.01f)
        }
        // Face plus the 5dp kept free for the edge.
        assertEquals(expectedHeight.value, primary.height.value, 0.01f)
    }

    @Test fun mediumButtonsAreAllTheSameSize() {
        showAll(ButtonSize.Medium)
        assertSameSize(Dp(49f))
    }

    @Test fun largeButtonsAreAllTheSameSize() {
        showAll(ButtonSize.Large)
        assertSameSize(Dp(57f))
    }
}
