package com.expenses.app.ui.screens.splitwise

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.expenses.app.ui.theme.AppTheme
import com.expenses.splitwise.Refusal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Drop area of Import from Splitwise is as high as the design draws it at 360dp (02-everyday/28 and 30: 113, 135:
 * 95, 136: 131). Figma counts the area's 1.5 inside outline in its layout, so the texts sit 25.5 from the top and
 * bottom and 17.5 from the sides; with the padding alone the app was 3dp short and everything under the area sat 3dp
 * higher (design check, 2026-11-19). Run at xhdpi, where 1.5dp is a whole number of pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h2400dp-xhdpi")
class ImportSplitwiseDropAreaTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: ImportUiState) {
        compose.setContent { AppTheme(dark = true) { ImportSplitwiseScreen(state = state, onEvent = {}) } }
    }

    private fun captionLayout(caption: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(caption, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    /**
     * The area that holds [title] and [caption] is 328 wide from x = 16, sets its texts in the frame's 293dp, and is
     * [height] high with the caption on [lines] lines, as drawn.
     */
    private fun assertDrawn(title: String, caption: String, lines: Int, height: Int) {
        // The area is one merged node: its bounds are the outline's.
        val area = compose.onNodeWithText(title).getUnclippedBoundsInRoot()
        assertEquals("left of \"$title\"", 16f, area.left.value, TOLERANCE_DP)
        assertEquals("width of \"$title\"", 328f, area.width.value, TOLERANCE_DP)
        val layout = captionLayout(caption)
        assertEquals("text width of \"$title\"", with(compose.density) { 293.dp.roundToPx() }, layout.layoutInput.constraints.maxWidth)
        assertEquals("caption lines of \"$title\"", lines, layout.lineCount)
        assertEquals("height of \"$title\"", height.toFloat(), area.height.value, TOLERANCE_DP)
    }

    /**
     * 28 draws its caption on two lines, 113 high, and so does the app in its own font (emulator, 2026-11-19).
     * Robolectric sets the text in a narrower fallback font, where this caption fits on one line: the area is then one
     * Caption line (18) lower. The two-line height itself is pinned by 30, the same area with a two-line caption.
     */
    @Test fun noFile28Is113HighWithItsCaptionOnTwoLines() {
        show(ImportSplitwiseSamples.noFile)
        val caption = "Pick the .csv file you exported from Splitwise."
        val lines = captionLayout(caption).lineCount
        assertDrawn("No file chosen yet", caption, lines = lines, height = 113 - (2 - lines) * CAPTION_LINE_DP)
    }

    @Test fun wrongFile30Is113High() {
        show(ImportSplitwiseSamples.wrongFile)
        assertDrawn(
            "That is not a Splitwise export", "expenses-2026.pdf cannot be read. Pick the .csv file that Splitwise made.",
            lines = 2, height = 113,
        )
    }

    @Test fun reading135Is95High() {
        show(ImportSplitwiseSamples.reading)
        assertDrawn("Reading the file", "flat-302-export.csv", lines = 1, height = 95)
    }

    /** The moment before the provider gives the file's name: the name's line keeps its place, so nothing jumps. */
    @Test fun readingIsAsHighBeforeTheNameIsKnown() {
        show(ImportUiState.Reading(fileName = ""))
        assertEquals(95f, compose.onNodeWithText("Reading the file").getUnclippedBoundsInRoot().height.value, TOLERANCE_DP)
    }

    @Test fun cannotImport136Is131High() {
        show(ImportSplitwiseSamples.cannotImport(Refusal.DOES_NOT_ADD_UP))
        assertDrawn(
            "This file does not add up",
            "flat-302-export.csv was changed after Splitwise made it. Export the group again and pick the new file.",
            lines = 3, height = 131,
        )
    }

    private companion object {
        const val TOLERANCE_DP = 0.01f

        /** Line height of the Caption style. */
        const val CAPTION_LINE_DP = 18
    }
}
