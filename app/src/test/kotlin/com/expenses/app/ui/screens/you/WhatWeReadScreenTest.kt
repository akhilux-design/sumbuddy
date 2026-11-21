package com.expenses.app.ui.screens.you

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.expenses.app.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What we've read (04-you/04): its footer's See the code. */
@RunWith(RobolectricTestRunner::class)
class WhatWeReadScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun seeTheCodeIsTheFooterButton() {
        val events = mutableListOf<WhatWeReadEvent>()
        compose.setContent { AppTheme(dark = true) { WhatWeReadScreen(state = YouSamples.whatWeRead, onEvent = { events += it }) } }
        compose.onNodeWithText("See the code").performClick()
        assertEquals(listOf<WhatWeReadEvent>(WhatWeReadEvent.OpenSourceCode), events)
    }
}
