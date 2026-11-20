package com.expenses.app.ui.motion

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.expenses.app.ui.components.AppChip
import com.expenses.app.ui.components.AppSwitch
import com.expenses.app.ui.components.OptionCard
import com.expenses.app.ui.components.SegmentedToggle
import com.expenses.app.ui.components.SegmentedToggleSide
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.screens.payment.AddExpenseEvent
import com.expenses.app.ui.screens.payment.AddExpenseScreen
import com.expenses.app.ui.screens.payment.AddExpenseUiState
import com.expenses.app.ui.theme.AppHaptics
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Haptic
import com.expenses.app.ui.theme.LocalAppHaptics
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The haptics the controls play (`design/spec/motion.md`, Haptics): one per change the person made, and none for a tap
 * that changes nothing. Recorded through [LocalAppHaptics], so the phone's Touch feedback setting is not involved.
 */
@RunWith(RobolectricTestRunner::class)
class HapticsWiringTest {
    @get:Rule
    val compose = createComposeRule()

    private val played = mutableListOf<Haptic>()

    private val recorder = object : AppHaptics {
        override fun play(haptic: Haptic) {
            played += haptic
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AppTheme(dark = false, reducedMotion = true) {
                CompositionLocalProvider(LocalAppHaptics provides recorder, content = content)
            }
        }
    }

    /** What played, without the 3D press of a raised button (its own haptic, checked in [PressDropTest]'s area). */
    private fun playedWithoutPress(): List<Haptic> = compose.runOnIdle { played.filter { it != Haptic.Press } }

    @Test
    fun switchRowPlaysToggleOnThenOff() {
        var checked by mutableStateOf(false)
        show {
            SettingRow(
                title = "HDFC Bank",
                subtitle = "Savings",
                checked = checked,
                onCheckedChange = { checked = it },
                trailing = { AppSwitch(checked = checked, onCheckedChange = null) },
            )
        }
        compose.onNodeWithText("HDFC Bank").performClick()
        compose.onNodeWithText("HDFC Bank").performClick()
        assertEquals(listOf(Haptic.ToggleOn, Haptic.ToggleOff), playedWithoutPress())
    }

    @Test
    fun switchOnItsOwnPlaysToggle() {
        var checked by mutableStateOf(false)
        show { AppSwitch(checked = checked, onCheckedChange = { checked = it }, contentDescription = "Reminders") }
        compose.onNodeWithContentDescription("Reminders").performClick()
        assertEquals(listOf(Haptic.ToggleOn), playedWithoutPress())
    }

    @Test
    fun choosingAnotherChipPlaysSelection() {
        var chosen by mutableStateOf("Equally")
        show {
            Column {
                listOf("Equally", "Amounts").forEach { label ->
                    AppChip(label = label, selected = chosen == label, onClick = { chosen = label })
                }
            }
        }
        compose.onNodeWithText("Amounts").performClick()
        assertEquals(listOf(Haptic.Selection), playedWithoutPress())
    }

    @Test
    fun theChosenChipAndAButtonChipPlayNothing() {
        var taps = 0
        show {
            Column {
                AppChip(label = "Equally", selected = true, onClick = { taps++ })
                AppChip(label = "+ New", selected = false, onClick = { taps++ }, role = Role.Button)
            }
        }
        compose.onNodeWithText("Equally").performClick()
        compose.onNodeWithText("+ New").performClick()
        compose.runOnIdle { assertEquals(2, taps) }
        assertEquals(emptyList<Haptic>(), playedWithoutPress())
    }

    @Test
    fun optionCardPlaysSelectionOnlyWhenTheChoiceChanges() {
        var chosen by mutableStateOf(0)
        show {
            Column {
                listOf("This month", "Last 3 months").forEachIndexed { i, title ->
                    OptionCard(
                        title = title,
                        subtitle = "Payments since the 1st are added.",
                        icon = AppIcon.Receipt,
                        selected = chosen == i,
                        onClick = { chosen = i },
                    )
                }
            }
        }
        compose.onNodeWithText("Last 3 months").performClick()
        compose.onNodeWithText("Last 3 months").performClick()
        assertEquals(listOf(Haptic.Selection), playedWithoutPress())
    }

    @Test
    fun segmentedTogglePlaysSelectionOnlyWhenTheSideChanges() {
        var side by mutableStateOf(SegmentedToggleSide.Left)
        show { SegmentedToggle(left = "Me", right = "Family", selected = side, onSelectedChange = { side = it }) }
        compose.onNodeWithText("Family").performClick()
        compose.onNodeWithText("Family").performClick()
        compose.runOnIdle { assertEquals(SegmentedToggleSide.Right, side) }
        assertEquals(listOf(Haptic.Selection), playedWithoutPress())
    }

    @Test
    fun aRefusedAddPlaysRejectEachTime() {
        val today = LocalDate.of(2026, 11, 20)
        var state by mutableStateOf(AddExpenseUiState.empty(today))
        show {
            AddExpenseScreen(
                state = state,
                onEvent = { event ->
                    // As AddExpenseViewModel does with an empty amount.
                    if (event == AddExpenseEvent.Add) {
                        state = state.copy(amountError = true, refusedSubmits = state.refusedSubmits + 1)
                    }
                },
                focusAmount = false,
            )
        }
        compose.onNodeWithText("Add to November").performClick()
        compose.onNodeWithText("Add to November").performClick()
        assertEquals(listOf(Haptic.Reject, Haptic.Reject), playedWithoutPress())
    }
}
