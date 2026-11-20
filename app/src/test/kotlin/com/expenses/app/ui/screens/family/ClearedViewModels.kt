package com.expenses.app.ui.screens.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ViewModels a test makes, cleared from `@After` before Main is reset. A ViewModel that reads the database as soon as it
 * is made (the family screens count this phone's earlier Family expenses at once) can still have a query on its way
 * when the test ends; its answer resuming into Main while `resetMain` swaps it fails a later test with "Dispatchers.Main
 * is used concurrently with setting it". [clear] cancels them and waits until their coroutines ended.
 */
internal class ClearedViewModels {
    private val store = ViewModelStore()
    private val made = CopyOnWriteArrayList<ViewModel>()

    /** The ViewModel [make] gives, cleared by [clear]. */
    fun <T : ViewModel> keep(type: Class<T>, make: () -> T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
        }
        return ViewModelProvider(store, factory)["vm${made.size}", type].also { made += it }
    }

    /** The [T] [make] gives, cleared by [clear]. */
    inline fun <reified T : ViewModel> keep(noinline make: () -> T): T = keep(T::class.java, make)

    /** Clears every ViewModel made and waits (up to 5 s) until their coroutines ended. Call it before `resetMain`. */
    fun clear() {
        store.clear()
        runBlocking { withTimeoutOrNull(WAIT_MS) { made.forEach { it.viewModelScope.coroutineContext.job.join() } } }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
