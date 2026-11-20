package com.expenses.app.ui.state

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Writes what [save] picks from each value of [form] into this [SavedStateHandle], so a half-filled form comes back
 * as it was after Android stopped the app in the background (for example while the person checked their banking
 * app). The ViewModel reads the same keys back when it is made. Call it once, from the ViewModel's `init`, with its
 * `viewModelScope`.
 *
 * @param scope the ViewModel's scope; writing stops with it.
 * @param form the form's state.
 * @param save stores the fields worth keeping: plain text, numbers, booleans and enum names.
 */
fun <T> SavedStateHandle.keepForm(scope: CoroutineScope, form: Flow<T>, save: SavedStateHandle.(T) -> Unit) {
    scope.launch { form.collect { save(it) } }
}
