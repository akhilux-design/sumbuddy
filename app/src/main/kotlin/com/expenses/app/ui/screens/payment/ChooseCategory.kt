package com.expenses.app.ui.screens.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.R
import com.expenses.app.data.category.Category
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.icons.LineIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.LocalAppHaptics
import com.expenses.app.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What Choose category (design 02-everyday/06-choose-category) shows.
 *
 * @param current the expense's category now, marked in the list, or null while it is read or when the expense is gone.
 * @param done true once a category was chosen (and stored, unless it is a draft); the caller then goes back.
 * @param chosen the category chosen, once [done].
 */
data class ChooseCategoryUiState(val current: Category? = null, val done: Boolean = false, val chosen: Category? = null)

/** Everything the person can do on Choose category. */
sealed interface ChooseCategoryEvent {
    /** The row of [category] was tapped. */
    data class Chosen(val category: Category) : ChooseCategoryEvent

    /** The back button was tapped. */
    data object Back : ChooseCategoryEvent
}

/**
 * Choose category for the expense [expenseId]. The choice is stored at once, so the screen that opened this one
 * (Add to expenses or Expense detail) shows it when the person comes back. With a [draft] (an edit on Expense
 * detail that is not saved yet) nothing is stored: the caller hands the choice back to the edit.
 *
 * @param expenseId the expense whose category is chosen.
 * @param draft the category picked so far in an unsaved edit, or null.
 * @param expenses where the category is stored.
 */
class ChooseCategoryViewModel(
    private val expenseId: Long,
    private val draft: Category?,
    private val expenses: ExpenseRepository,
) : ViewModel() {
    private val chosen = MutableStateFlow<Category?>(null)

    /** The screen's state. */
    val state: StateFlow<ChooseCategoryUiState> = combine(expenses.observe(expenseId), chosen) { row, chosen ->
        ChooseCategoryUiState(current = draft ?: row?.expense?.category, done = chosen != null, chosen = chosen)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ChooseCategoryUiState())

    /** Handles what the person did. Back is handled by the caller and ignored here. */
    fun onEvent(event: ChooseCategoryEvent) {
        when (event) {
            is ChooseCategoryEvent.Chosen -> viewModelScope.launch {
                if (draft == null) expenses.setCategory(expenseId, event.category)
                chosen.value = event.category
            }
            ChooseCategoryEvent.Back -> Unit
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Choose category (design 02-everyday/06-choose-category): every category as a row with its examples. The current
 * one has a tick at the end, so it does not show by colour alone. A row shows the flat pressed overlay while
 * pressed, and choosing another category plays `haptic/selection`.
 *
 * @param state what to show; see [ChooseCategoryUiState].
 * @param onEvent called with what the person did; see [ChooseCategoryEvent].
 * @param modifier outer modifier. The screen fills the space it is given and keeps clear of the system bars.
 */
@Composable
fun ChooseCategoryScreen(
    state: ChooseCategoryUiState,
    onEvent: (ChooseCategoryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PaymentPage(
        title = stringResource(R.string.payment_category_title),
        onBack = { onEvent(ChooseCategoryEvent.Back) },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(Space.s8),
        ) {
            val currentLabel = stringResource(R.string.payment_category_current)
            val haptics = LocalAppHaptics.current
            Category.entries.forEach { category ->
                val isCurrent = category == state.current
                SettingRow(
                    title = stringResource(category.label),
                    subtitle = stringResource(category.examples),
                    modifier = Modifier.semantics { selected = isCurrent },
                    icon = category.icon,
                    onClick = {
                        // A tick when the category changes; picking the current one again is not a change.
                        if (!isCurrent) haptics.selection()
                        onEvent(ChooseCategoryEvent.Chosen(category))
                    },
                    trailing = if (isCurrent) {
                        {
                            LineIcon(
                                icon = AppIcon.Check,
                                tint = AppTheme.colors.textBrand,
                                contentDescription = currentLabel,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
