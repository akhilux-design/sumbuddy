package com.expenses.app.ui.screens.groups

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How Add friend shows a phone number ([IndianMobileTransformation], design 02-everyday/27): a whole Indian mobile
 * number reads "+91 90000 00001", anything else reads as typed, and the cursor has a place for every position in
 * both directions.
 */
class PhoneNumberTransformationTest {
    private val shown = "+91 90000 00001"

    /** Every way a whole Indian mobile number may be in the field, typed or picked from contacts. */
    private val wholeNumbers = listOf(
        "9000000001",
        "90000 00001",
        "+919000000001",
        "+91 9000000001",
        "+9190000 00001",
        "+91 90000 00001",
    )

    private fun transform(typed: String) = IndianMobileTransformation.filter(AnnotatedString(typed))

    @Test fun aWholeIndianMobileNumberIsGroupedAsDrawn() {
        wholeNumbers.forEach { typed -> assertEquals(typed, shown, transform(typed).text.text) }
        // Every first digit a mobile number may start with.
        assertEquals("+91 60000 00000", transform("6000000000").text.text)
        assertEquals("+91 70000 00000", transform("7000000000").text.text)
        assertEquals("+91 80000 00000", transform("8000000000").text.text)
    }

    @Test fun anythingElseIsShownAsTyped() {
        val asTyped = listOf(
            "",
            "9",
            "900000000", // nine digits: still being typed
            "90000000013", // eleven
            "5000000001", // not a mobile number
            "09000000001", // a trunk 0
            "919000000001", // the country code without its plus
            "+91900000000",
            "+91 90000 0000",
            "+14155550123", // another country
            "90000-00001",
            "90000  00001",
            " 9000000001",
            "9000000001 ",
            "(90000) 00001",
            "+91-9000000001",
        )
        asTyped.forEach { typed ->
            val result = transform(typed)
            assertEquals(typed, result.text.text)
            assertSame(typed, OffsetMapping.Identity, result.offsetMapping)
            assertNull(typed, groupIndianMobile(typed))
        }
    }

    @Test fun onlyCharactersAreAddedSoEveryTypedOneStaysInOrder() {
        wholeNumbers.forEach { typed ->
            val grouped = groupIndianMobile(typed)!!
            // The typed characters are a subsequence of what is shown: nothing is dropped, replaced or moved.
            var at = 0
            typed.forEach { char ->
                at = grouped.text.indexOf(char, at) + 1
                assertTrue("$typed: '$char' is shown after the ones before it", at > 0)
            }
        }
    }

    @Test fun theCursorHasAPlaceForEveryPositionBothWays() {
        wholeNumbers.forEach { typed ->
            val mapping = transform(typed).offsetMapping
            // Typed position to shown position: in range, never going back, and the way back gives the same position.
            var last = -1
            for (offset in 0..typed.length) {
                val there = mapping.originalToTransformed(offset)
                assertTrue("$typed: $offset maps to $there", there in 0..shown.length)
                assertTrue("$typed: $offset maps before ${offset - 1}", there >= last)
                assertTrue("$typed: $offset and ${offset - 1} share a place", there > last)
                assertEquals("$typed: $offset there and back", offset, mapping.transformedToOriginal(there))
                last = there
            }
            assertEquals("$typed: the end", shown.length, mapping.originalToTransformed(typed.length))
            // Shown position (a tap, a drag of a handle) to typed position: in range and never going back.
            var lastBack = 0
            for (offset in 0..shown.length) {
                val back = mapping.transformedToOriginal(offset)
                assertTrue("$typed: shown $offset maps to $back", back in 0..typed.length)
                assertTrue("$typed: shown $offset maps before ${offset - 1}", back >= lastBack)
                lastBack = back
            }
            assertEquals("$typed: the start", 0, mapping.transformedToOriginal(0))
            assertEquals("$typed: the end back", typed.length, mapping.transformedToOriginal(shown.length))
        }
    }

    @Test fun theCursorStandsNextToWhatWasTyped() {
        // "9000000001" shown as "+91 90000 00001".
        val bare = transform("9000000001").offsetMapping
        // Before the first digit: after the added "+91 ", where typing goes on.
        assertEquals(4, bare.originalToTransformed(0))
        // After "90000": right after the fifth digit, in front of the added space.
        assertEquals(9, bare.originalToTransformed(5))
        // After "900000": after the first digit of the second group.
        assertEquals(11, bare.originalToTransformed(6))
        assertEquals(15, bare.originalToTransformed(10))
        // A tap on or before an added character lands before the typed character that follows.
        assertEquals(listOf(0, 0, 0, 0, 0), (0..4).map(bare::transformedToOriginal))
        assertEquals(5, bare.transformedToOriginal(9))
        assertEquals(5, bare.transformedToOriginal(10))
        assertEquals(6, bare.transformedToOriginal(11))

        // "+919000000001": the code was typed, only the two spaces are added.
        val withCode = transform("+919000000001").offsetMapping
        assertEquals(listOf(0, 1, 2, 3), (0..3).map(withCode::originalToTransformed))
        assertEquals(5, withCode.originalToTransformed(4))
        assertEquals(9, withCode.originalToTransformed(8))
        assertEquals(11, withCode.originalToTransformed(9))
        assertEquals(3, withCode.transformedToOriginal(3))
        assertEquals(3, withCode.transformedToOriginal(4))
        assertEquals(8, withCode.transformedToOriginal(10))

        // Typed exactly as drawn: every position is its own.
        val asDrawn = transform(shown).offsetMapping
        for (offset in 0..shown.length) {
            assertEquals(offset, asDrawn.originalToTransformed(offset))
            assertEquals(offset, asDrawn.transformedToOriginal(offset))
        }
    }

    @Test fun everyEditOfAWholeNumberKeepsTheCursorInsideTheText() {
        // Deleting or typing one character at any position of a whole number gives text that is shown as typed or
        // grouped again; either way every cursor position of the new text has a place.
        wholeNumbers.forEach { typed ->
            val edits = buildList {
                for (at in typed.indices) add(typed.removeRange(at, at + 1))
                for (at in 0..typed.length) "0 +9".forEach { char -> add(typed.substring(0, at) + char + typed.substring(at)) }
            }
            edits.forEach { edited ->
                val result = transform(edited)
                for (offset in 0..edited.length) {
                    val there = result.offsetMapping.originalToTransformed(offset)
                    assertTrue("$edited: $offset maps to $there", there in 0..result.text.length)
                }
                for (offset in 0..result.text.length) {
                    val back = result.offsetMapping.transformedToOriginal(offset)
                    assertTrue("$edited: shown $offset maps to $back", back in 0..edited.length)
                }
            }
        }
    }
}
